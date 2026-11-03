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
 * Pages without a glass version yet (Dashboard tabs, Lock screen widgets, About, usage history,
 * reset credits, Edit dashboard, updates) open as their One UI 9 activities.
 */
final class GlassApp implements GlassSource {
    /** MainActivity extra: open glass Settings at this page. */
    static final String EXTRA_SETTINGS_PAGE = "glass_settings_page";
    private static final String STATE_PAGE = "glass_settings_page";

    private final MainActivity activity;
    private final GlassSettings settings;
    private final GlasseHost glasse;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> changedSoon();
    private boolean refreshing;
    private Nav nav;
    private String shownPage = "root";
    private GlassDialog dialog;
    private String dialogScreen;
    private boolean pending;

    interface Touch {
        boolean dispatch(MotionEvent ev);
    }

    GlassApp(MainActivity activity, Touch superTouch) {
        this.activity = activity;
        this.settings = new GlassSettings(activity);
        GlassIcons.register();
        for (UsageProvider provider : UsageProvider.values()) {
            registerIcon(activity, provider);
        }
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, this));
        glasse = new GlasseHost(activity, env -> CodexGlass.dashboard(env, this), makers,
                superTouch::dispatch);
    }

    /** The glass app on {@code context}'s activity, or null when it shows the One UI views. */
    static GlassApp of(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity) {
                return ((MainActivity) context).glass();
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
        // Back closes the glass page, sheet or menu on top (Android 13+ also gets the host's own
        // predictive-back callback while a page is open); otherwise it leaves as usual.
        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!glasse.onBackPressed()) {
                    setEnabled(false);
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        activity.getSharedPreferences("codex_meter_settings_v1", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
        String page = state == null ? null : state.getString(STATE_PAGE);
        if (page != null) {
            openSettings(page, true);
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
        changed();
    }

    void onPause() {
        glasse.onPause();
    }

    void onDestroy() {
        activity.getSharedPreferences("codex_meter_settings_v1", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
        ui.removeCallbacksAndMessages(null);
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
        if (intent == null || intent.getComponent() == null
                || !SettingsActivity.class.getName().equals(intent.getComponent().getClassName())) {
            return false;
        }
        openSettings(intent.getStringExtra(SettingsActivity.EXTRA_PAGE), false);
        return true;
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
        dialogScreen = app == null ? CodexGlass.DASHBOARD : app;
        changed();
    }

    void closeDialog(GlassDialog value) {
        if (dialog == value) {
            dialog = null;
            changed();
        }
        changedSoon();
    }

    @Override
    public GlassModel.Dialog dialog(String screen) {
        return dialog != null && screen.equals(dialogScreen) ? dialog.model() : null;
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
        return activity.glassProvider().id;
    }

    @Override
    public void selectTab(String id) {
        activity.glassSelectProvider(UsageProvider.fromId(id));
    }

    @Override
    public GlassModel.Dashboard dashboard() {
        return GlassCards.build(activity, activity.glassProvider());
    }

    @Override
    public boolean refreshing() {
        return refreshing;
    }

    @Override
    public void refresh() {
        activity.glassRefresh();
    }

    @Override
    public List<MenuEntry> menu() {
        List<MenuEntry> menu = new ArrayList<>();
        menu.add(new MenuEntry("Usage history", "history", false, () -> UsageHistoryActivity.open(
                activity, activity.glassProvider())));
        menu.add(new MenuEntry("Edit dashboard", "edit", false, activity::glassEditDashboard));
        menu.add(new MenuEntry("Add widget", "widget", false, activity::requestPinWidget));
        menu.add(new MenuEntry("Settings", "gear", false, () -> {
            DiagnosticLog.info(activity, "user", "settings_opened");
            openSettings("root", false);
        }));
        return menu;
    }

    @Override
    public GlassModel.Page page(String id) {
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
        glasse.getHost().close();
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
