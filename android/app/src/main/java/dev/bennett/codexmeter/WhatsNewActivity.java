package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;

import com.benitbuhner.glasse.android.GlasseHost;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import dev.bennett.codexmeter.glass.CodexGlass;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

import java.util.Collections;

/**
 * The full-screen What's new page ({@link WhatsNew}) after a big update, in the design's own
 * full-screen layout: the app's logo, a large title, every new feature and one button.
 * One UI 8.5 and One UI 9 follow One UI's app info page (large centred logo and name over rounded
 * cards), Material 3 Expressive uses Material's display type and tonal icon shapes, and Liquid Glass
 * is drawn by Glassé like iOS's welcome screens ({@link CodexGlass#whatsNew}).
 *
 * <p>It opens on its own once per {@link WhatsNew#RELEASE} ({@link #showOnce}); About opens it again.
 * Continue or Back marks it seen.
 */
public final class WhatsNewActivity extends AppCompatActivity {
    private GlasseHost glasse;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    /** Opens the page over {@code from} when this release's page hasn't been shown yet. */
    static void showOnce(Activity from) {
        if (WhatsNew.shouldShow(AppPreferences.getWhatsNewSeen(from),
                AppPreferences.isOnboardingComplete(from))) {
            open(from);
        }
    }

    static void open(Context from) {
        Intent intent = new Intent(from, WhatsNewActivity.class);
        if (!(from instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        from.startActivity(intent);
    }

    @SuppressLint("ThreadConstraint")
    @Override
    protected void onCreate(Bundle bundle) {
        boolean expressive = AppPreferences.isMaterialExpressiveDesign(this);
        if (expressive) {
            ExpressiveDashboard.applyTheme(this);
        } else {
            Ui.applySelectedTheme(this);
        }
        super.onCreate(bundle);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                done();
            }
        });
        if (AppPreferences.isLiquidGlassDesign(this)) {
            showGlass();
        } else if (expressive) {
            showExpressive();
        } else {
            showOneUi();
        }
        DiagnosticLog.info(this, "user", "whats_new_shown", "release", WhatsNew.RELEASE);
    }

    private void done() {
        AppPreferences.markWhatsNewSeen(this);
        finish();
    }

    // ------------------------------------------------------------------ Liquid Glass

    // The 96dp logo is encoded to PNG on the main thread before the page draws. Lint 9.5 marks
    // Bitmap.compress @WorkerThread (flagged at onCreate); it is a few kilobytes, as in GlassWidgetScreens.
    private void showGlass() {
        GlassApp.applyLook(this);
        GlassModel.Intro intro = new GlassModel.Intro();
        intro.title = WhatsNew.TITLE;
        intro.button = WhatsNew.BUTTON;
        intro.logo = GlassScreens.png(this, R.mipmap.ic_launcher,
                Math.round(96 * getResources().getDisplayMetrics().density));
        for (WhatsNew.Feature feature : WhatsNew.FEATURES) {
            GlassModel.Row row = new GlassModel.Row(GlassModel.Row.Kind.INFO, feature.icon);
            row.title = feature.title;
            row.summary = feature.body;
            row.icon = glassIcon(feature.icon);
            intro.features.add(row);
        }
        // Glassé runs the button on its own thread of events; finishing belongs on the UI thread.
        intro.onDone = () -> runOnUiThread(this::done);
        this.glasse = new GlasseHost(this, env -> CodexGlass.whatsNew(env, intro),
                Collections.emptyMap(), ev -> super.dispatchTouchEvent(ev));
        this.glasse.start(true);
        this.glasse.setThemeMode(AppPreferences.getAppTheme(this));
    }

    static String glassIcon(String icon) {
        switch (icon) {
            case "providers": return "grid";
            case "priority": return "flag";
            case "designs": return "paintbrush";
            case "resets": return "refresh";
            case "live": return "bolt";
            case "alerts": return "bell";
            case "value": return "creditcard";
            case "status": return "waveform";
            case "widgets": return "widget";
            case "duo": return "sidebar";
            case "desktop": return "desktop";
            default: return "gear";
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return this.glasse != null ? this.glasse.dispatchTouchEvent(ev) : super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this.glasse != null) this.glasse.onResume();
    }

    @Override
    protected void onPause() {
        if (this.glasse != null) this.glasse.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (this.glasse != null) this.glasse.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (this.glasse != null) this.glasse.onConfigurationChanged(config);
    }

    // ------------------------------------------------------------------ One UI 8.5 and One UI 9

    static int oneUiIcon(String icon) {
        switch (icon) {
            case "providers": return R.drawable.ic_oui_apps_outline;
            case "priority": return R.drawable.ic_priority;
            case "designs": return R.drawable.ic_oui_palette;
            case "resets": return R.drawable.ic_oui_restart;
            case "live": return R.drawable.ic_oui_lightning_outline;
            case "alerts": return R.drawable.ic_oui_notification_outline;
            case "value": return R.drawable.ic_oui_symbol_dollar;
            case "status": return R.drawable.ic_oui_speed;
            case "widgets": return R.drawable.ic_oui_widget_outline;
            case "duo": return R.drawable.ic_oui_folding_status;
            case "desktop": return R.drawable.ic_oui_link_to_windows;
            default: return R.drawable.ic_oui_settings_outline;
        }
    }

    /**
     * One UI's app info layout: the logo large and centred in the top of the screen, the name under
     * it, then the features in a rounded card list and the button pinned at the bottom.
     */
    private void showOneUi() {
        boolean dark = Ui.isDark(this);
        boolean oneUi9 = AppPreferences.isOneUi9Design(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.background(this, dark));

        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Ui.dp(this, oneUi9 ? 16 : 12);
        column.setPadding(side, 0, side, 0);
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription("Codex Meter");
        int logoSize = Ui.dp(this, 108);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.topMargin = Ui.dp(this, 72);
        column.addView(logo, logoParams);

        TextView title = Ui.text(this, WhatsNew.TITLE, oneUi9 ? 30 : 28, Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 8));
        column.addView(title, titleParams);

        TextView version = Ui.text(this, getString(R.string.about_version, Ui.versionName(this)),
                15, Ui.secondaryText(dark));
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-1, -2);
        versionParams.bottomMargin = Ui.dp(this, 48);
        column.addView(version, versionParams);

        RoundedLinearLayout card = Ui.seslRowCard(this, dark);
        for (int i = 0; i < WhatsNew.FEATURES.size(); i++) {
            WhatsNew.Feature feature = WhatsNew.FEATURES.get(i);
            CardItemView row = Ui.actionRow(this, feature.title, feature.body,
                    oneUiIcon(feature.icon), null);
            row.setShowTopDivider(i > 0);
            if (row.getIconImageView() != null) {
                row.getIconImageView().setImageTintList(
                        ColorStateList.valueOf(Ui.accent(this, dark)));
            }
            card.addView(row);
        }
        column.addView(card);

        Button next = Ui.nativePrimaryButton(this, WhatsNew.BUTTON);
        next.setOnClickListener(view -> done());
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Ui.background(this, dark));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                Math.min(Ui.dp(this, 320), getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, 24)),
                Ui.dp(this, 52));
        bar.addView(next, nextParams);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        edgeToEdge(root, scroll, bar, dark, Ui.dp(this, 14), Ui.dp(this, 20));
    }

    // ------------------------------------------------------------------ Material 3 Expressive

    /**
     * Material 3 Expressive: the logo over a display-size headline, each feature beside its icon
     * in a tonal rounded shape, and a large filled button at the bottom.
     */
    private void showExpressive() {
        boolean dark = Ui.isDark(this);
        Context ctx = this;
        FrameLayout root = new FrameLayout(ctx);
        int surface = color(ctx, dark ? R.attr.colorSurface
                : R.attr.colorSurfaceContainer);
        root.setBackgroundColor(surface);

        NestedScrollView scroll = new NestedScrollView(ctx);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 24);
        column.setPadding(pad, 0, pad, 0);
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        ImageView logo = new ImageView(ctx);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription("Codex Meter");
        int logoSize = Ui.dp(ctx, 96);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.topMargin = Ui.dp(ctx, 56);
        column.addView(logo, logoParams);

        TextView title = text(ctx, WhatsNew.TITLE,
                R.attr.textAppearanceDisplaySmallEmphasized,
                R.attr.colorOnSurface);
        ExpressiveCards.weight(title, 560);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(ctx, 28), 0, Ui.dp(ctx, 36));
        column.addView(title, titleParams);

        int container = color(ctx, R.attr.colorPrimaryContainer);
        int onContainer = color(ctx, R.attr.colorOnPrimaryContainer);
        for (WhatsNew.Feature feature : WhatsNew.FEATURES) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(oneUiIcon(feature.icon));
            icon.setImageTintList(ColorStateList.valueOf(onContainer));
            int box = Ui.dp(ctx, 52);
            int inset = Ui.dp(ctx, 13);
            icon.setPadding(inset, inset, inset, inset);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(container);
            shape.setCornerRadius(Ui.dp(ctx, 18));
            icon.setBackground(shape);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(box, box));

            LinearLayout words = new LinearLayout(ctx);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(ctx, feature.title,
                    R.attr.textAppearanceTitleMediumEmphasized,
                    R.attr.colorOnSurface);
            words.addView(name);
            TextView body = text(ctx, feature.body,
                    R.attr.textAppearanceBodyMedium,
                    R.attr.colorOnSurfaceVariant);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
            bodyParams.topMargin = Ui.dp(ctx, 2);
            words.addView(body, bodyParams);
            LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1f);
            wordsParams.setMarginStart(Ui.dp(ctx, 18));
            row.addView(words, wordsParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = Ui.dp(ctx, 22);
            column.addView(row, rowParams);
        }

        MaterialButton next = new MaterialButton(ctx);
        next.setText(WhatsNew.BUTTON);
        next.setOnClickListener(view -> done());
        TextViewCompat.setTextAppearance(next, resolve(ctx,
                R.attr.textAppearanceTitleMedium));
        next.setTextColor(color(ctx, R.attr.colorOnPrimary));
        next.setCornerRadius(Ui.dp(ctx, 28));
        LinearLayout bar = new LinearLayout(ctx);
        bar.setBackgroundColor(surface);
        bar.addView(next, new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 56)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        edgeToEdge(root, scroll, bar, dark, Ui.dp(ctx, 16), pad);
    }

    private static TextView text(Context ctx, String value, int appearance, int colorAttr) {
        TextView view = new TextView(ctx);
        int style = resolve(ctx, appearance);
        if (style != 0) {
            TextViewCompat.setTextAppearance(view, style);
        }
        view.setTextColor(color(ctx, colorAttr));
        view.setText(value);
        return view;
    }

    private static int resolve(Context ctx, int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        return ctx.getTheme().resolveAttribute(attr, tv, true) ? tv.resourceId : 0;
    }

    private static int color(Context ctx, int attr) {
        return MaterialColors.getColor(ctx, attr, Color.GRAY);
    }

    // ------------------------------------------------------------------ shared

    /**
     * Draws under the status and navigation bars: the page scrolls under a status bar in the page colour, and the
     * button bar sits above the navigation bar with the list running out behind it.
     */
    // Android 15 draws apps edge to edge and ignores bar colors; the minSdk 26-34 devices still need them.
    @SuppressWarnings("deprecation")
    private void edgeToEdge(ViewGroup root, NestedScrollView scroll, LinearLayout bar, boolean dark,
            int barTop, int barSide) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(!dark);
        // The status bar keeps the page colour, as in One UI's and Material's own pages, so scrolled
        // rows never run under the clock and icons.
        View scrim = new View(this);
        Drawable barFill = bar.getBackground();
        if (barFill instanceof ColorDrawable) {
            scrim.setBackgroundColor(((ColorDrawable) barFill).getColor());
        } else {
            scrim.setBackgroundColor(Ui.background(this, dark));
        }
        root.addView(scrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, 0);
            scrim.getLayoutParams().height = bars.top;
            scrim.requestLayout();
            bar.setPadding(barSide + bars.left, barTop, barSide + bars.right, bars.bottom + barTop);
            return WindowInsetsCompat.CONSUMED;
        });
        // The list ends above the button bar, whatever height the bar takes.
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            View list = scroll.getChildAt(0);
            int reserve = (b - t) + Ui.dp(this, 16);
            if (list != null && list.getPaddingBottom() != reserve) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), reserve);
            }
        });
    }
}
