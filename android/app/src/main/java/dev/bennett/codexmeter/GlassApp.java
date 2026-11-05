package dev.bennett.codexmeter;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.benitbuhner.glasse.android.GlasseHost;
import com.benitbuhner.glasse.ui.ScreenMaker;
import dev.bennett.codexmeter.glass.CodexGlass;
import dev.bennett.codexmeter.glass.GlassIcons;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Liquid Glass design: the dashboard and Settings drawn by Glassé's kit ({@link CodexGlass})
 * in place of the One UI views, inside {@link MainActivity}. This class is the app behind those
 * screens: the dashboard's cards come from the same data as the One UI dashboard
 * ({@link GlassCards}), and Settings runs the app's own settings code ({@link GlassSettings}).
 * The other pages (Dashboard tabs, Lock screen widgets, About, usage history, reset credits, Edit
 * dashboard, updates, widget settings) are glass pages too ({@link GlassScreens}): the intents
 * that would open their One UI activities open them here instead. Widget settings, which the
 * launcher opens as an activity of its own, host a GlassApp that starts at their page.
 */
final class GlassApp implements GlassSource {
    /** MainActivity extra: open glass Settings at this page. */
    static final String EXTRA_SETTINGS_PAGE = "glass_settings_page";
    private static final String STATE_PAGE = "glass_settings_page";

    /** An activity that shows Liquid Glass. */
    interface Host {
        GlassApp glass();
    }

    /** MainActivity extra: open the glass page for this intent (a ported activity's own). */
    static final String EXTRA_GLASS_INTENT = "glass_intent";

    private final AppCompatActivity activity;
    private final String landing;
    private final GlassSettings settings;
    private final GlassScreens screens;
    private final GlasseHost glasse;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> changedSoon();
    private boolean refreshing;
    private Nav nav;
    private String shownPage = "root";
    private GlassDialog dialog;
    private GlassModel.Dialog model;
    private String dialogScreen;
    private boolean pending;

    interface Touch {
        boolean dispatch(MotionEvent ev);
    }

    GlassApp(MainActivity activity, Touch superTouch) {
        this(activity, superTouch, null);
    }

    /** {@code landing}: the page this activity shows (widget settings), or null for the dashboard. */
    GlassApp(AppCompatActivity activity, Touch superTouch, String landing) {
        this.activity = activity;
        this.landing = landing;
        this.settings = new GlassSettings(activity);
        this.screens = new GlassScreens(activity, this);
        GlassIcons.register();
        for (UsageProvider provider : UsageProvider.values()) {
            registerIcon(activity, provider);
        }
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, this));
        glasse = new GlasseHost(activity, landing != null
                ? env -> CodexGlass.page(env, this, landing)
                : env -> CodexGlass.dashboard(env, this), makers, superTouch::dispatch);
        if (landing != null) {
            shownPage = landing;
        }
    }

    private MainActivity main() {
        return (MainActivity) activity;
    }

    /**
     * In Liquid Glass, a ported activity hands its intent to MainActivity, which shows its glass
     * page, and finishes. Call right after super.onCreate; returns true when it did.
     */
    static boolean redirect(AppCompatActivity from) {
        if (!AppPreferences.isLiquidGlassDesign(from)) {
            return false;
        }
        from.startActivity(new Intent(from, MainActivity.class)
                .putExtra(EXTRA_GLASS_INTENT, new Intent(from.getIntent()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        from.finish();
        from.overridePendingTransition(0, 0);
        return true;
    }

    /** The glass app on {@code context}'s activity, or null when it shows the One UI views. */
    static GlassApp of(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Host) {
                return ((Host) context).glass();
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    void start(Bundle state) {
        glasse.start(true);
        // The settings pages' own fragments live here, out of sight: their preferences are the model.
        FrameLayout fragments = new FrameLayout(activity);
        fragments.setId(R.id.glass_fragments);
        fragments.setVisibility(View.GONE);
        glasse.getView().addView(fragments, 0, new FrameLayout.LayoutParams(1, 1));
        applyTheme();
        // Back closes the glass page, sheet or menu on top. The callback is on only while there is one,
        // so on the dashboard Android's own back runs (with its predictive back-to-home animation).
        // Android 13+ also gets the host's own predictive-back callback, which animates the page.
        OnBackPressedCallback back = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (!glasse.onBackPressed()) {
                    setEnabled(false);
                    activity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, back);
        glasse.setOnBackActive(on -> {
            back.setEnabled(on);
            return kotlin.Unit.INSTANCE;
        });
        back.setEnabled(glasse.backActive());
        activity.getSharedPreferences("codex_meter_settings_v1", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
        String page = state == null ? null : state.getString(STATE_PAGE);
        if (page != null && landing == null) {
            if (page.startsWith(GlassScreens.PREFIX)) {
                openPage(page, null);
            } else {
                openSettings(page, true);
            }
        }
        if (landing != null) {
            screens.opened(landing, activity.getIntent());
        }
    }

    void save(Bundle out) {
        if (CodexGlass.SETTINGS.equals(glasse.getHost().app)) {
            out.putString(STATE_PAGE, shownPage);
        }
    }

    /** Follows Settings › Appearance › Dark mode. */
    void applyTheme() {
        glasse.setThemeMode(AppPreferences.getAppTheme(activity));
        changed();
    }

    void onResume() {
        glasse.onResume();
        GlassScreens.Screen screen = screens.screen(shownPage);
        if (screen != null) {
            screen.resumed();
        }
        changed();
    }

    void onPause() {
        glasse.onPause();
    }

    void onDestroy() {
        activity.getSharedPreferences("codex_meter_settings_v1", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
        ui.removeCallbacksAndMessages(null);
        screens.dispose();
        glasse.onDestroy();
    }

    void onConfigurationChanged(android.content.res.Configuration config) {
        glasse.onConfigurationChanged(config);
    }

    boolean dispatchTouchEvent(MotionEvent ev) {
        return glasse.dispatchTouchEvent(ev);
    }


    /**
     * Opens that would leave for the One UI Settings activity open the glass Settings page
     * instead. Returns true when the intent was handled here.
     */
    boolean intercept(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return false;
        }
        if (SettingsActivity.class.getName().equals(intent.getComponent().getClassName())) {
            if (landing != null) {
                return false;
            }
            openSettings(intent.getStringExtra(SettingsActivity.EXTRA_PAGE), false);
            return true;
        }
        String page = GlassScreens.pageFor(intent);
        if (page == null) {
            return false;
        }
        openPage(page, intent);
        return true;
    }

    /** Starts {@code intent}, as a glass page when it opens one. */
    void start(Intent intent) {
        if (!intercept(intent)) {
            activity.startActivity(intent);
        }
    }

    void post(Runnable action) {
        ui.post(action);
    }

    /**
     * Opens glass page {@code id}: on top of the Settings pages when they're showing, otherwise on
     * its own (back returns to the dashboard).
     */
    void openPage(String id, Intent intent) {
        screens.opened(id, intent);
        if (glasse.getHost().app != null || landing != null) {
            nav = new Nav(false, id);
        } else {
            nav = new Nav(true, id, true, false);
            glasse.getHost().open(CodexGlass.SETTINGS, false);
        }
        changed();
    }

    /** Closes the page on top (a page that finished its job, like a saved widget). */
    void pop() {
        nav = new Nav(false, null, false, true);
        changed();
    }

    void openSettings(String page, boolean instant) {
        String id = SettingsActivity.normalizePage(page);
        if (CodexGlass.SETTINGS.equals(glasse.getHost().app)) {
            nav = new Nav(false, id);
        } else {
            nav = new Nav(true, id);
            glasse.getHost().open(CodexGlass.SETTINGS, instant);
        }
        changed();
    }

    /** Tells the screens to draw again from the app's current state. */
    void changed() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** Settings code often finishes its work a moment later (a posted update); draw after it. */
    private void changedSoon() {
        if (pending) {
            return;
        }
        pending = true;
        ui.post(() -> {
            pending = false;
            changed();
        });
    }

    void setRefreshing(boolean value) {
        if (refreshing != value) {
            refreshing = value;
            changed();
        }
    }

    // ------------------------------------------------------------------ dialogs

    void showDialog(GlassDialog value) {
        dialog = value;
        String app = glasse.getHost().app;
        dialogScreen = app != null ? app : landing != null ? CodexGlass.SETTINGS : CodexGlass.DASHBOARD;
        changed();
    }

    void closeDialog(GlassDialog value) {
        if (dialog == value) {
            dialog = null;
            changed();
        }
        changedSoon();
    }

    /** Shows a glass dialog built here rather than from an AppDialog (a picker sheet, say). */
    void showDialog(GlassModel.Dialog value) {
        model = value;
        String app = glasse.getHost().app;
        dialogScreen = app != null ? app : landing != null ? CodexGlass.SETTINGS : CodexGlass.DASHBOARD;
        changed();
    }

    void closeDialog(GlassModel.Dialog value) {
        if (model == value) {
            model = null;
            changed();
        }
    }

    @Override
    public GlassModel.Dialog dialog(String screen) {
        if (!screen.equals(dialogScreen)) {
            return null;
        }
        return dialog != null ? dialog.model() : model;
    }

    // ------------------------------------------------------------------ GlassSource

    @Override
    public Runnable subscribe(Runnable onChange) {
        listeners.add(onChange);
        return () -> listeners.remove(onChange);
    }

    @Override
    public List<GlassModel.Tab> tabs() {
        List<GlassModel.Tab> tabs = new ArrayList<>();
        for (UsageProvider provider : ProviderPreferences.getEnabled(activity)) {
            tabs.add(new GlassModel.Tab(provider.id, provider.displayName, "provider_" + provider.id));
        }
        return tabs;
    }

    @Override
    public String selectedTab() {
        return main().glassProvider().id;
    }

    @Override
    public void selectTab(String id) {
        main().glassSelectProvider(UsageProvider.fromId(id));
    }

    @Override
    public GlassModel.Dashboard dashboard() {
        return GlassCards.build(main(), main().glassProvider());
    }

    @Override
    public boolean refreshing() {
        return refreshing;
    }

    @Override
    public void refresh() {
        main().glassRefresh();
    }

    @Override
    public List<MenuEntry> menu() {
        List<MenuEntry> menu = new ArrayList<>();
        menu.add(new MenuEntry("Edit dashboard", "squarePencil", false, main()::glassEditDashboard));
        menu.add(new MenuEntry("Settings", "gearshape", false, () -> {
            DiagnosticLog.info(activity, "user", "settings_opened");
            openSettings("root", false);
        }));
        return menu;
    }

    @Override
    public GlassModel.Page page(String id) {
        if (id != null && id.startsWith(GlassScreens.PREFIX)) {
            return screens.page(id);
        }
        return settings.page(id);
    }

    @Override
    public void toggle(String page, String key, boolean on) {
        settings.toggle(page, key, on);
        changed();
        changedSoon();
    }

    @Override
    public void choose(String page, String key, int index) {
        settings.choose(page, key, index);
        changed();
        changedSoon();
    }

    @Override
    public void press(String page, String key) {
        settings.press(page, key);
        changed();
        changedSoon();
    }

    @Override
    public void closeSettings() {
        if (landing != null) {
            activity.finish();
        } else {
            glasse.getHost().close();
        }
    }

    @Override
    public Nav takeNav() {
        Nav out = nav;
        nav = null;
        return out;
    }

    @Override
    public void shown(String page) {
        shownPage = page;
        GlassScreens.Screen screen = screens.screen(page);
        if (screen != null) {
            screen.shown();
        }
    }

    // ------------------------------------------------------------------ icons

    /** The provider's logo for its tab: the path of its vector drawable. */
    private static void registerIcon(Context context, UsageProvider provider) {
        StringBuilder d = new StringBuilder();
        try (XmlResourceParser xml = context.getResources().getXml(ProvidersActivity.iconRes(provider))) {
            int event;
            while ((event = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG && "path".equals(xml.getName())) {
                    String path = xml.getAttributeValue("http://schemas.android.com/apk/res/android",
                            "pathData");
                    if (path != null) {
                        d.append(path).append(' ');
                    }
                }
            }
        } catch (Exception ignored) {
            // No logo: the tab shows its name only.
        }
        if (d.length() > 0) {
            GlassIcons.provider(provider.id, d.toString().trim());
        }
    }
}
