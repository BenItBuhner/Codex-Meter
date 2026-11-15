package dev.bennett.codexmeter;

import android.view.Gravity;
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
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;
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

        this.root.findViewById(R.id.expressive_edit).setOnClickListener(v ->
                activity.glassEditDashboard());
        this.root.findViewById(R.id.expressive_settings).setOnClickListener(v -> {
            DiagnosticLog.info(activity, "user", "settings_opened");
            Ui.startSecondaryActivity(activity, SettingsActivity.class);
        });
        this.root.findViewById(R.id.expressive_refresh).setOnClickListener(v ->
                activity.glassRefresh());
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
        this.root.findViewById(R.id.expressive_edit).setVisibility(priority ? View.GONE : View.VISIBLE);
        renderTabs(priority ? null : provider, enabled);
    }

    private static boolean grouped(GlassModel.Card c) {
        return c.kind == GlassModel.Card.Kind.METER;
    }

    private void renderTabs(UsageProvider selected, List<UsageProvider> enabled) {
        this.tabs.removeAllViews();
        boolean withPriority = this.activity.priorityTabShown();
        this.toolbar.setVisibility(enabled.size() + (withPriority ? 1 : 0) > 1 ? View.VISIBLE : View.GONE);
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
            tab.setOnClickListener(v -> this.activity.selectPriority());
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
            tab.setOnClickListener(v -> this.activity.glassSelectProvider(provider));
            this.tabs.addView(tab);
        }
    }
}
