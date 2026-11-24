package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import dev.bennett.codexmeter.glass.GlassModel;

/**
 * A page in the Material 3 Expressive design: Settings and every other page (usage history, reset
 * credits, updates, About, dashboard tabs, Edit dashboard, release history, lock screen widgets).
 * The pages' behavior is the app's own code, exactly as Liquid Glass uses it: Settings come from
 * the One UI preference fragment running out of sight ({@link GlassSettings}), the other pages from
 * {@link GlassScreens}. This draws them with Material views: a large title, section labels, rows
 * grouped into segmented cards, Material switches, sliders, dialogs, and the dashboard's cards
 * ({@link ExpressiveCards}) for pages that show charts and lists.
 */
final class ExpressiveSettings {
    /** Where a page's rows and actions come from. */
    interface Model {
        GlassModel.Page page();

        /** A switch on a settings preference (rows with their own {@code onToggle} use that). */
        void toggle(String key, boolean on);

        void choose(String key, int index);

        void press(String key);
    }

    private final AppCompatActivity activity;
    private final Model model;
    private final Context ctx;
    private final boolean dark;
    private final LinearLayout list;
    private final LinearLayout actions;
    private final TextView heading;
    private final View root;
    private final ExpressiveCards look;
    private final ExpressiveRows rows;
    private final NestedScrollView scroll;
    private final MaterialButton back;
    /** A step label and wavy bar over the title (first-run setup); hidden until {@link #setProgress}. */
    private final LinearLayout progress;
    private final TextView progressLabel;
    private final ExpressiveWave progressBar;

    /** A Settings page, drawn from its preferences. */
    ExpressiveSettings(AppCompatActivity activity, String page, String title) {
        this(activity, title, settingsModel(new GlassSettings(activity), page));
    }

    private static Model settingsModel(GlassSettings settings, String page) {
        return new Model() {
            @Override
            public GlassModel.Page page() {
                return settings.page(page);
            }

            @Override
            public void toggle(String key, boolean on) {
                settings.toggle(page, key, on);
            }

            @Override
            public void choose(String key, int index) {
                settings.choose(page, key, index);
            }

            @Override
            public void press(String key) {
                settings.press(page, key);
            }
        };
    }

  // Android 15 draws apps edge to edge and ignores bar colors; the minSdk 26-34 devices still need them.
  @SuppressWarnings("deprecation")
    ExpressiveSettings(AppCompatActivity activity, String title, Model model) {
        this.activity = activity;
        this.model = model;
        this.ctx = ExpressiveDashboard.themed(activity);
        ExpressiveMotion.sharedAxis(activity);
        this.dark = Ui.isDark(activity);

        FrameLayout frame = new FrameLayout(this.ctx);
        frame.setBackgroundColor(color(this.dark ? R.attr.colorSurface : R.attr.colorSurfaceContainer));
        // The page's preference fragment lives here, out of sight: its preferences are the model.
        FrameLayout fragments = new FrameLayout(activity);
        fragments.setId(R.id.glass_fragments);
        fragments.setVisibility(View.GONE);
        frame.addView(fragments, new FrameLayout.LayoutParams(1, 1));

        NestedScrollView scroll = new NestedScrollView(this.ctx);
        this.scroll = scroll;
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(this.ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, 0, pad, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        MaterialButton back = new MaterialButton(this.ctx, null,
                R.attr.materialIconButtonStyle);
        back.setIconResource(R.drawable.ic_m3_arrow_back);
        back.setIconTint(ColorStateList.valueOf(color(R.attr.colorOnSurface)));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> activity.getOnBackPressedDispatcher().onBackPressed());
        this.back = back;
        // Back on the left, the page's own actions (Customize, Edit…) on the right.
        LinearLayout bar = new LinearLayout(this.ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        bar.addView(new View(this.ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        this.actions = new LinearLayout(this.ctx);
        this.actions.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(this.actions, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        column.addView(bar, bp);

        this.progress = new LinearLayout(this.ctx);
        this.progress.setOrientation(LinearLayout.VERTICAL);
        this.progress.setVisibility(View.GONE);
        this.progressLabel = text("", R.attr.textAppearanceLabelLargeEmphasized, R.attr.colorPrimary);
        this.progress.addView(this.progressLabel, new LinearLayout.LayoutParams(-1, -2));
        this.progressBar = new ExpressiveWave(this.ctx, color(R.attr.colorPrimary),
                color(R.attr.colorSecondaryContainer));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(10);
        this.progress.addView(this.progressBar, wp);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.setMargins(dp(8), dp(20), dp(8), 0);
        column.addView(this.progress, pp);

        this.heading = text(title, R.attr.textAppearanceDisplaySmallEmphasized, R.attr.colorOnSurface);
        ExpressiveCards.weight(this.heading, 560);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(dp(8), dp(20), dp(8), dp(12));
        column.addView(this.heading, hp);

        this.list = new LinearLayout(this.ctx);
        this.list.setOrientation(LinearLayout.VERTICAL);
        column.addView(this.list, new LinearLayout.LayoutParams(-1, -2));
        this.look = new ExpressiveCards(this.ctx, this.dark, this.list);
        this.rows = new ExpressiveRows(this.ctx, this.dark, model, this::scheduleRender);

        this.root = frame;
        activity.setContentView(frame);
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowCompat.getInsetsController(activity.getWindow(), frame).setAppearanceLightStatusBars(!this.dark);
        WindowCompat.getInsetsController(activity.getWindow(), frame).setAppearanceLightNavigationBars(!this.dark);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, 0);
            scroll.setPadding(0, 0, 0, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        // Rows read settings and the live monitor's state, and an action may change either later (a
        // dialog, a permission prompt, a service starting), so redraw when they change, not on a timer.
        frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                AppPreferences.addChangeListener(view.getContext(), stateChanged);
                NowBarManager.addStateListener(view.getContext(), stateChanged);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                AppPreferences.removeChangeListener(view.getContext(), stateChanged);
                NowBarManager.removeStateListener(view.getContext(), stateChanged);
                view.removeCallbacks(renderOnce);
            }
        });
    }

    private final Runnable renderOnce = this::render;

    /** Held in a field: SharedPreferences keeps listeners weakly. One redraw covers a burst of edits. */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener stateChanged =
            (prefs, key) -> scheduleRender();

    private void scheduleRender() {
        this.root.removeCallbacks(this.renderOnce);
        this.root.post(this.renderOnce);
    }

    /** Shows or hides the back button (hidden on a flow's first step, where back leaves the app). */
    void setBackVisible(boolean visible) {
        this.back.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * A step label ("Step 2 of 5") and a wavy progress bar over the title, for a flow of pages
     * such as first-run setup; a null label hides them.
     */
    void setProgress(String label, double fraction) {
        if (label == null) {
            this.progress.setVisibility(View.GONE);
            return;
        }
        this.progress.setVisibility(View.VISIBLE);
        this.progressLabel.setText(label);
        this.progressBar.setFraction(fraction);
    }

    void scrollToTop() {
        this.scroll.post(() -> this.scroll.scrollTo(0, 0));
    }

    /** Draws the page from its model; call again after anything changes. */
    void render() {
        GlassModel.Page page = this.model.page();
        this.list.removeAllViews();
        this.actions.removeAllViews();
        if (page == null) {
            return;
        }
        if (page.title != null && !page.title.isEmpty()) {
            this.heading.setText(page.title);
        }
        for (GlassModel.Action a : page.actions) {
            MaterialButton action = new MaterialButton(this.ctx, null,
                    a.prominent ? R.attr.materialButtonStyle : R.attr.materialButtonTonalStyle);
            action.setText(a.label);
            action.setOnClickListener(v -> {
                if (a.onPress != null) {
                    a.onPress.run();
                }
                this.root.post(this::render);
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
            ap.leftMargin = dp(8);
            this.actions.addView(action, ap);
        }
        if (page.intro != null && !page.intro.isEmpty()) {
            TextView intro = text(page.intro, R.attr.textAppearanceBodyLarge, R.attr.colorOnSurfaceVariant);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(dp(8), 0, dp(8), dp(12));
            this.list.addView(intro, ip);
        }
        for (GlassModel.Section section : page.sections) {
            this.rows.section(this.list, section, this.look);
        }
    }

    /**
     * The chosen entry, then the summary. A summary that already states the choice, such as
     * "Automatic · every 30 minutes now" for "Automatic (recommended)", stands alone.
     */
    static String choiceSummary(String entry, String summary) {
        if (summary == null || summary.isEmpty()) return entry;
        String plain = entry.replace(" (recommended)", "");
        if (summary.equals(entry) || summary.startsWith(plain)) return summary;
        return entry + "\n" + summary;
    }


    private TextView text(String value, int appearance, int colorAttr) {
        TextView view = new TextView(this.ctx);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (this.ctx.getTheme().resolveAttribute(appearance, tv, true)) {
            TextViewCompat.setTextAppearance(view, tv.resourceId);
        }
        view.setTextColor(color(colorAttr));
        view.setText(value == null ? "" : value);
        return view;
    }

    private int color(int attr) {
        return MaterialColors.getColor(this.ctx, attr, 0xFF808080);
    }

    private int dp(float v) {
        return Ui.dp(this.ctx, v);
    }
}
