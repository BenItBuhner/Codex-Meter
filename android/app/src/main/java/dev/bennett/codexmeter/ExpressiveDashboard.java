package dev.bennett.codexmeter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.google.android.material.loadingindicator.LoadingIndicator;
import dev.bennett.codexmeter.glass.GlassModel;
import java.util.List;

/**
 * The Material 3 Expressive design's dashboard, drawn with Material Components' own Expressive
 * views: a large header, filled cards (meters grouped as one segmented list) with wavy progress
 * bars, pull to refresh with the contained loading indicator, and a floating toolbar of provider
 * tabs. Settings use the same design ({@link ExpressiveSettings}).
 *
 * <p>It shows the same cards as Liquid Glass ({@link GlassCards}), so both designs always list the
 * same things.
 */
final class ExpressiveDashboard {
    /** The One UI blue the app uses when Material You is off, as the color scheme's seed. */
    private static final int SEED = 0xFF0381FE;

    private final MainActivity activity;
    private final boolean dark;
    private final View root;
    private final NestedScrollView scroll;
    private final ExpressivePull pull;
    private final TextView title;
    private final LoadingIndicator loading;
    private final LinearLayout cards;
    private final LinearLayout tabs;
    private final View toolbar;
    private boolean refreshing;
    private final ExpressiveCards look;
    private final ExpressiveRows rows;
    private final View edit;
    private final View done;
    private final View settings;
    private final LinearLayout editList;
    private boolean editing;
    /** The tab drawn last, so a new one fades through and a redraw of the same one doesn't. */
    private String shownTab;
    private final androidx.activity.OnBackPressedCallback editBack =
            new androidx.activity.OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    setEditing(false);
                }
            };

    /** Edit mode's rows carry their own handlers; there are no settings preferences behind them. */
    private static final ExpressiveSettings.Model NO_SETTINGS = new ExpressiveSettings.Model() {
        @Override
        public GlassModel.Page page() {
            return null;
        }

        @Override
        public void toggle(String key, boolean on) {
        }

        @Override
        public void choose(String key, int index) {
        }

        @Override
        public void press(String key) {
        }
    };

    /**
     * Sets the Expressive theme and its colors. Call in place of {@link Ui#applySelectedTheme}
     * before {@code super.onCreate}: Material You takes the wallpaper colors, otherwise the scheme
     * is built from the app's blue. Light or dark follows the app's theme setting.
     */
    static void applyTheme(android.app.Activity activity) {
        Ui.applySelectedTheme(activity);
        activity.setTheme(R.style.AppTheme_Expressive);
        if (AppPreferences.isMaterialYouEnabled(activity)) {
            DynamicColors.applyToActivityIfAvailable(activity);
        } else {
            DynamicColors.applyToActivityIfAvailable(activity,
                    new DynamicColorsOptions.Builder().setContentBasedSource(SEED).build());
        }
    }

    /**
     * A context with the Expressive theme and colors, for Expressive views inside an activity
     * that keeps another theme (Settings, whose preference fragments need the One UI theme).
     */
    static android.content.Context themed(android.content.Context base) {
        android.content.Context themed = new android.view.ContextThemeWrapper(base, R.style.AppTheme_Expressive);
        if (AppPreferences.isMaterialYouEnabled(base)) {
            return DynamicColors.wrapContextIfAvailable(themed);
        }
        return DynamicColors.wrapContextIfAvailable(themed,
                new DynamicColorsOptions.Builder().setContentBasedSource(SEED).build());
    }

    ExpressiveDashboard(MainActivity activity, boolean dark) {
        this.activity = activity;
        this.dark = dark;
        this.root = LayoutInflater.from(activity).inflate(R.layout.expressive_dashboard, null);
        activity.setContentView(this.root);
        this.scroll = this.root.findViewById(R.id.expressive_scroll);
        this.pull = this.root.findViewById(R.id.expressive_pull);
        this.title = this.root.findViewById(R.id.expressive_title);
        this.loading = this.root.findViewById(R.id.expressive_loading);
        this.cards = this.root.findViewById(R.id.expressive_cards);
        this.tabs = this.root.findViewById(R.id.expressive_tabs);
        this.toolbar = this.root.findViewById(R.id.expressive_toolbar);
        this.look = new ExpressiveCards(activity, dark, this.cards);
        this.root.setBackgroundColor(this.look.color(dark ? R.attr.colorSurface : R.attr.colorSurfaceContainer));
        ExpressiveCards.weight(this.title, 560);
        this.pull.setup(this.scroll, this.loading, activity::glassRefresh);

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowCompat.getInsetsController(activity.getWindow(), this.root)
                .setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(activity.getWindow(), this.root)
                .setAppearanceLightNavigationBars(!dark);
        ViewCompat.setOnApplyWindowInsetsListener(this.root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // The status bar stays clear (cards don't scroll under it); the floating toolbar and the
            // last card sit above the navigation bar.
            view.setPadding(0, bars.top, 0, 0);
            this.scroll.setPadding(bars.left, 0, bars.right, bars.bottom);
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) this.toolbar.getLayoutParams();
            params.bottomMargin = bars.bottom + Ui.dp(activity, 16);
            this.toolbar.setLayoutParams(params);
            return WindowInsetsCompat.CONSUMED;
        });

        this.edit = this.root.findViewById(R.id.expressive_edit);
        this.done = this.root.findViewById(R.id.expressive_done);
        this.settings = this.root.findViewById(R.id.expressive_settings);
        this.editList = this.root.findViewById(R.id.expressive_edit_list);
        this.rows = new ExpressiveRows(activity, dark, NO_SETTINGS, () -> this.root.post(this::renderEdit));
        this.edit.setOnClickListener(v -> {
            DiagnosticLog.info(activity, "user", "dashboard_edit_opened");
            setEditing(true);
        });
        this.done.setOnClickListener(v -> setEditing(false));
        // Back leaves Edit mode first, as in the other designs.
        activity.getOnBackPressedDispatcher().addCallback(activity, this.editBack);
        this.settings.setOnClickListener(v -> {
            DiagnosticLog.info(activity, "user", "settings_opened");
            Ui.startSecondaryActivity(activity, SettingsActivity.class);
        });
    }

    void setRefreshing(boolean on) {
        if (this.refreshing == on) {
            return;
        }
        this.refreshing = on;
        this.pull.setRefreshing(on);
    }

    /** Draws the selected tab's cards (a provider's, or the Priority page) and the tabs. */
    void render(UsageProvider provider, List<UsageProvider> enabled) {
        boolean priority = this.activity.onPriorityTab();
        String tab = priority ? PriorityPage.TAB_ID : provider.id;
        boolean switched = this.shownTab != null && !this.shownTab.equals(tab);
        this.shownTab = tab;
        if (switched && this.editing) {
            setEditing(false);
        }
        GlassModel.Dashboard model = priority ? GlassCards.priorityPage(this.activity)
                : GlassCards.build(this.activity, provider);
        this.title.setText(model.title);
        this.cards.removeAllViews();
        List<GlassModel.Card> list = model.cards;
        for (int i = 0; i < list.size(); i++) {
            GlassModel.Card c = list.get(i);
            if (c.kind == GlassModel.Card.Kind.HEADING) {
                this.cards.addView(this.look.heading(c.title));
                continue;
            }
            // Meters in a row form one segmented group: large outer corners, small inner ones.
            boolean joinsPrev = i > 0 && grouped(c) && grouped(list.get(i - 1));
            boolean joinsNext = i + 1 < list.size() && grouped(c) && grouped(list.get(i + 1));
            this.cards.addView(this.look.card(c, joinsPrev, joinsNext));
        }
        boolean editable = DashboardEdit.sections(this.activity) != null;
        this.edit.setVisibility(editable && !this.editing ? View.VISIBLE : View.GONE);
        if (this.editing) {
            renderEdit();
        }
        renderTabs(priority ? null : provider, enabled);
        if (switched) {
            ExpressiveMotion.fadeThrough(this.cards);
            ExpressiveMotion.fadeThrough(this.title);
        }
    }

    /**
     * Edit mode: the cards give way to the tab's list (each card or section with a switch and a
     * drag handle), Edit and Settings to Done, and the tabs slide away; Done or back returns.
     */
    void setEditing(boolean on) {
        if (on && DashboardEdit.sections(this.activity) == null) {
            return;
        }
        if (this.editing == on) {
            return;
        }
        this.editing = on;
        this.editBack.setEnabled(on);
        this.pull.setEnabled(!on);
        AppHaptics.tick(this.root);
        if (on) {
            renderEdit();
        }
        View out = on ? this.cards : this.editList;
        View in = on ? this.editList : this.cards;
        out.setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        ExpressiveMotion.fadeThrough(in);
        this.edit.setVisibility(on ? View.GONE : View.VISIBLE);
        this.settings.setVisibility(on ? View.GONE : View.VISIBLE);
        this.done.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            ExpressiveMotion.fadeThrough(this.done);
        }
        this.scroll.scrollTo(0, 0);
        if (on) {
            ExpressiveMotion.slideToolbar(this.toolbar, false);
        } else {
            // the cards pick up what changed, and the tabs come back if there are several
            this.activity.rebuild();
            ExpressiveMotion.slideToolbar(this.toolbar, true);
        }
    }

    private void renderEdit() {
        this.editList.removeAllViews();
        GlassModel.Section section = DashboardEdit.sections(this.activity);
        if (section == null) {
            return;
        }
        // The section's header is the list's instructions: drawn as an intro, not a label.
        String intro = section.header;
        section.header = null;
        if (intro != null) {
            TextView text = this.look.text(intro, R.attr.textAppearanceBodyLarge, R.attr.colorOnSurfaceVariant);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.setMargins(Ui.dp(this.activity, 8), 0, Ui.dp(this.activity, 8), Ui.dp(this.activity, 16));
            this.editList.addView(text, ip);
        }
        this.rows.section(this.editList, section, this.look);
    }

    private static boolean grouped(GlassModel.Card c) {
        return c.kind == GlassModel.Card.Kind.METER;
    }

    private void renderTabs(UsageProvider selected, List<UsageProvider> enabled) {
        this.tabs.removeAllViews();
        boolean withPriority = this.activity.priorityTabShown();
        this.toolbar.setVisibility(enabled.size() + (withPriority ? 1 : 0) > 1 && !this.editing
                ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this.activity);
        if (withPriority) {
            // The Priority tab leads, selected when no provider is (selected == null).
            boolean on = selected == null;
            MaterialButton tab = (MaterialButton) inflater.inflate(
                    on ? R.layout.expressive_tab_selected : R.layout.expressive_tab, this.tabs, false);
            tab.setIconResource(R.drawable.ic_priority);
            tab.setContentDescription(PriorityPage.TITLE);
            if (on) {
                tab.setText(PriorityPage.TITLE);
            }
            tab.setOnClickListener(v -> {
                AppHaptics.tick(v);
                this.activity.selectPriority();
            });
            this.tabs.addView(tab);
        }
        for (UsageProvider provider : enabled) {
            boolean on = provider == selected;
            MaterialButton tab = (MaterialButton) inflater.inflate(
                    on ? R.layout.expressive_tab_selected : R.layout.expressive_tab, this.tabs, false);
            tab.setIconResource(ProvidersActivity.iconRes(provider));
            tab.setContentDescription(provider.displayName);
            if (on) {
                tab.setText(provider.displayName);
            }
            tab.setOnClickListener(v -> {
                AppHaptics.tick(v);
                this.activity.glassSelectProvider(provider);
            });
            this.tabs.addView(tab);
        }
    }
}
