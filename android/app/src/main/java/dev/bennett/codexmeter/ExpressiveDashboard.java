package dev.bennett.codexmeter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
    private final TextView subtitle;
    private final LoadingIndicator loading;
    private final LinearLayout cards;
    private final LinearLayout tabs;
    private final View toolbar;
    private boolean refreshing;

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
        this.subtitle = this.root.findViewById(R.id.expressive_subtitle);
        this.loading = this.root.findViewById(R.id.expressive_loading);
        this.cards = this.root.findViewById(R.id.expressive_cards);
        this.tabs = this.root.findViewById(R.id.expressive_tabs);
        this.toolbar = this.root.findViewById(R.id.expressive_toolbar);
        this.root.setBackgroundColor(color(dark ? R.attr.colorSurface : R.attr.colorSurfaceContainer));
        weight(this.title, 560);
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
        this.subtitle.setText(model.subtitle == null ? "" : model.subtitle);
        this.subtitle.setVisibility(model.subtitle == null ? View.INVISIBLE : View.VISIBLE);
        this.cards.removeAllViews();
        List<GlassModel.Card> list = model.cards;
        for (int i = 0; i < list.size(); i++) {
            GlassModel.Card c = list.get(i);
            if (c.kind == GlassModel.Card.Kind.HEADING) {
                this.cards.addView(heading(c.title));
                continue;
            }
            // Meters in a row form one segmented group: large outer corners, small inner ones.
            boolean joinsPrev = i > 0 && grouped(c) && grouped(list.get(i - 1));
            boolean joinsNext = i + 1 < list.size() && grouped(c) && grouped(list.get(i + 1));
            this.cards.addView(card(c, joinsPrev, joinsNext));
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

    private int cardColor() {
        return color(this.dark ? R.attr.colorSurfaceContainerHigh : R.attr.colorSurfaceContainerLowest);
    }

    private View card(GlassModel.Card c, boolean joinsPrev, boolean joinsNext) {
        LayoutInflater inflater = LayoutInflater.from(this.activity);
        MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.expressive_card,
                this.cards, false);
        card.setCardBackgroundColor(cardColor());
        float big = Ui.dp(this.activity, 28);
        float small = Ui.dp(this.activity, 8);
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(joinsPrev ? small : big).setTopRightCornerSize(joinsPrev ? small : big)
                .setBottomLeftCornerSize(joinsNext ? small : big)
                .setBottomRightCornerSize(joinsNext ? small : big).build());
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        if (margins == null) {
            margins = new ViewGroup.MarginLayoutParams(-1, -2);
        }
        margins.bottomMargin = Ui.dp(this.activity, joinsNext ? 3 : 14);
        card.setLayoutParams(margins);
        LinearLayout body = card.findViewById(R.id.expressive_card_body);
        switch (c.kind) {
            case METER:
                meter(body, c);
                break;
            case HISTORY:
                header(body, c.title, c.value);
                boolean hasBars = c.startLabel != null && !c.startLabel.isEmpty();
                if (hasBars) {
                    body.addView(new Bars(this.activity, c.bars, color(R.attr.colorPrimary),
                                    color(R.attr.colorSecondaryContainer)),
                            new LinearLayout.LayoutParams(-1, Ui.dp(this.activity, 88)));
                    body.addView(ends(c.startLabel, c.endLabel));
                }
                line(body, c.summary, R.attr.textAppearanceBodyMedium,
                        R.attr.colorOnSurfaceVariant, 10);
                if (c.onPress != null) {
                    card.setOnClickListener(v -> c.onPress.run());
                } else {
                    UsageProvider provider = this.activity.glassProvider();
                    card.setOnClickListener(v -> UsageHistoryActivity.open(this.activity, provider));
                }
                break;
            case STATS:
                header(body, c.title, null);
                for (String[] row : c.stats) {
                    body.addView(statRow(row[0], row.length > 1 ? row[1] : ""));
                }
                line(body, c.summary, R.attr.textAppearanceBodyMedium,
                        R.attr.colorOnSurfaceVariant, 12);
                if (c.onPress != null) {
                    button(body, c.value == null || c.value.isEmpty() ? "More" : c.value,
                            c.onPress, true, false);
                }
                break;
            case ERROR:
                card.setCardBackgroundColor(color(R.attr.colorErrorContainer));
                line(body, c.title, R.attr.textAppearanceTitleMedium,
                        R.attr.colorOnErrorContainer, 0);
                line(body, c.summary, R.attr.textAppearanceBodyMedium,
                        R.attr.colorOnErrorContainer, 6);
                break;
            case UPDATE:
            case SIGN_IN:
                int onColor = R.attr.colorOnSurface;
                int onVariant = R.attr.colorOnSurfaceVariant;
                if (c.kind == GlassModel.Card.Kind.UPDATE) {
                    card.setCardBackgroundColor(color(c.warning
                            ? R.attr.colorErrorContainer
                            : R.attr.colorSecondaryContainer));
                    onColor = c.warning ? R.attr.colorOnErrorContainer
                            : R.attr.colorOnSecondaryContainer;
                    onVariant = onColor;
                }
                weight(line(body, c.title, R.attr.textAppearanceTitleLarge, onColor, 0), 560);
                line(body, c.summary, R.attr.textAppearanceBodyMedium, onVariant, 6);
                if (c.button != null && c.onPress != null) {
                    button(body, c.button, c.onPress, false, !c.buttonEnabled);
                }
                if (c.progress >= 0) {
                    ExpressiveWave bar = new ExpressiveWave(this.activity, color(R.attr.colorPrimary),
                            color(R.attr.colorSurfaceContainerHighest));
                    bar.setFraction(c.progress);
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
                    bp.topMargin = Ui.dp(this.activity, 14);
                    body.addView(bar, bp);
                }
                break;
            case LINK:
            case BALANCE:
                header(body, c.title, c.value);
                line(body, c.summary, R.attr.textAppearanceBodyMedium,
                        R.attr.colorOnSurfaceVariant, 6);
                line(body, c.detail, R.attr.textAppearanceBodySmall,
                        R.attr.colorOnSurfaceVariant, 4);
                if (c.onPress != null) {
                    card.setOnClickListener(v -> c.onPress.run());
                }
                break;
            case NOTE:
                // An overline ("Priority", "Service status") above the message, like M3 cards.
                line(body, c.detail, R.attr.textAppearanceLabelLargeEmphasized, R.attr.colorPrimary, 0);
                weight(line(body, c.title, R.attr.textAppearanceTitleMedium, R.attr.colorOnSurface,
                        c.detail == null ? 0 : 6), 560);
                line(body, c.summary, R.attr.textAppearanceBodyMedium,
                        R.attr.colorOnSurfaceVariant, 8);
                if (c.onPress != null) {
                    card.setOnClickListener(v -> c.onPress.run());
                }
                break;
            case MESSAGE:
            case CHART:
            default:
                card.setCardBackgroundColor(color(R.attr.colorSurfaceContainerHigh));
                if (c.kind == GlassModel.Card.Kind.MESSAGE) {
                    line(body, c.title, R.attr.textAppearanceBodyLarge,
                            R.attr.colorOnSurfaceVariant, 0);
                } else {
                    line(body, c.title, R.attr.textAppearanceTitleMedium,
                            R.attr.colorOnSurface, 0);
                }
                line(body, c.summary, R.attr.textAppearanceBodyMedium,
                        R.attr.colorOnSurfaceVariant, 6);
                line(body, c.detail, R.attr.textAppearanceBodySmall,
                        R.attr.colorOnSurfaceVariant, 6);
                if (c.onPress != null) {
                    card.setOnClickListener(v -> c.onPress.run());
                }
                break;
        }
        return card;
    }

    /**
     * A usage window: its name and reset time on one line, what's left in large type, the wavy
     * bar, and the pace as a tonal pill (error colors when it runs out early).
     */
    private void meter(LinearLayout body, GlassModel.Card c) {
        LinearLayout top = new LinearLayout(this.activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(c.title, R.attr.textAppearanceTitleMediumEmphasized, R.attr.colorOnSurface);
        weight(name, 560);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (c.detail != null && !c.detail.isEmpty()) {
            top.addView(text(c.detail, R.attr.textAppearanceLabelLarge, R.attr.colorOnSurfaceVariant),
                    new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout amount = new LinearLayout(this.activity);
        amount.setGravity(Gravity.BOTTOM);
        boolean empty = c.remaining <= 0.001;
        TextView value = text(c.value, R.attr.textAppearanceDisplayMediumEmphasized,
                empty ? R.attr.colorError : R.attr.colorOnSurface);
        weight(value, 600);
        value.setIncludeFontPadding(false);
        amount.addView(value, new LinearLayout.LayoutParams(-2, -2));
        TextView left = text(" left", R.attr.textAppearanceTitleMedium, R.attr.colorOnSurfaceVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = Ui.dp(this.activity, 6);
        amount.addView(left, lp);
        amount.setContentDescription(c.title + ", " + c.value + " left");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this.activity, 10);
        body.addView(amount, ap);

        ExpressiveWave bar = new ExpressiveWave(this.activity,
                color(c.fast || empty ? R.attr.colorError : R.attr.colorPrimary),
                color(c.fast || empty ? R.attr.colorErrorContainer : R.attr.colorSecondaryContainer));
        bar.setFraction(c.remaining);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(this.activity, 12);
        body.addView(bar, bp);

        if (c.paceLabel != null && c.pace != null) {
            TextView pill = text(c.paceLabel + " · " + c.pace, R.attr.textAppearanceLabelLarge,
                    c.fast ? R.attr.colorOnErrorContainer : R.attr.colorOnSecondaryContainer);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Ui.dp(this.activity, 16));
            bg.setColor(color(c.fast ? R.attr.colorErrorContainer : R.attr.colorSecondaryContainer));
            pill.setBackground(bg);
            int px = Ui.dp(this.activity, 12);
            int py = Ui.dp(this.activity, 6);
            pill.setPadding(px, py, px, py);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
            pp.topMargin = Ui.dp(this.activity, 14);
            body.addView(pill, pp);
        }
        line(body, c.summary, R.attr.textAppearanceBodySmall,
                R.attr.colorOnSurfaceVariant, 8);
    }

    private void header(LinearLayout body, String name, String value) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(name, R.attr.textAppearanceTitleMediumEmphasized,
                R.attr.colorOnSurface);
        weight(left, 560);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            TextView right = text(value, R.attr.textAppearanceLabelLarge,
                    R.attr.colorPrimary);
            row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View statRow(String label, String value) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this.activity, 10), 0, 0);
        row.addView(text(label, R.attr.textAppearanceBodyLarge,
                R.attr.colorOnSurface),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(value, R.attr.textAppearanceLabelLarge,
                R.attr.colorOnSurfaceVariant),
                new LinearLayout.LayoutParams(-2, -2));
        return row;
    }
    private View ends(String start, String end) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setPadding(0, Ui.dp(this.activity, 6), 0, 0);
        row.addView(text(start, R.attr.textAppearanceLabelMedium,
                R.attr.colorOnSurfaceVariant),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(end == null ? "" : end,
                R.attr.textAppearanceLabelMedium,
                R.attr.colorOnSurfaceVariant),
                new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void button(LinearLayout body, String label, Runnable onPress, boolean tonal,
            boolean disabled) {
        MaterialButton button = (MaterialButton) LayoutInflater.from(this.activity).inflate(
                tonal ? R.layout.expressive_tonal_button : R.layout.expressive_button, body, false);
        button.setText(label);
        button.setEnabled(!disabled);
        button.setOnClickListener(v -> onPress.run());
        body.addView(button);
    }

    private TextView line(LinearLayout body, String text, int appearance, int colorAttr, int top) {
        TextView view = text(text, appearance, colorAttr);
        if (text == null || text.isEmpty()) {
            return view;
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(this.activity, top);
        body.addView(view, params);
        return view;
    }

    /** Sets a variable weight (the theme's emphasized styles fall back to regular weight). */
    static TextView weight(TextView view, int weight) {
        if (view != null && android.os.Build.VERSION.SDK_INT >= 28) {
            view.setTypeface(android.graphics.Typeface.create(view.getTypeface(), weight, false));
        }
        return view;
    }

    /** A section label over the cards that follow it (the Priority page's groups). */
    private View heading(String text) {
        TextView view = text(text, R.attr.textAppearanceTitleSmallEmphasized, R.attr.colorPrimary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Ui.dp(this.activity, 16), Ui.dp(this.activity, 8), Ui.dp(this.activity, 16),
                Ui.dp(this.activity, 8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView text(String text, int appearance, int colorAttr) {
        TextView view = new TextView(this.activity);
        android.util.TypedValue value = new android.util.TypedValue();
        if (this.activity.getTheme().resolveAttribute(appearance, value, true)) {
            TextViewCompat.setTextAppearance(view, value.resourceId);
        }
        view.setTextColor(color(colorAttr));
        view.setText(text == null ? "" : text);
        return view;
    }

    private int color(int attr) {
        return MaterialColors.getColor(this.root, attr);
    }

    /** Usage history: the current window's samples as rounded bars. */
    private static final class Bars extends View {
        private final double[] bars;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Bars(MainActivity context, double[] bars, int color, int trackColor) {
            super(context);
            this.bars = bars == null ? new double[0] : bars;
            this.fill.setColor(color);
            this.track.setColor(trackColor);
            setPadding(0, Ui.dp(context, 12), 0, 0);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int n = Math.max(1, this.bars.length);
            float w = getWidth();
            float h = getHeight() - getPaddingTop();
            float gap = Math.min(6f * getResources().getDisplayMetrics().density, w / n / 3f);
            float barW = (w - gap * (n - 1)) / n;
            float r = Math.min(barW / 2f, 8f * getResources().getDisplayMetrics().density);
            for (int i = 0; i < this.bars.length; i++) {
                float x = i * (barW + gap);
                this.rect.set(x, getPaddingTop(), x + barW, getHeight());
                canvas.drawRoundRect(this.rect, r, r, this.track);
                float top = getHeight() - (float) (h * Math.max(0, Math.min(1, this.bars[i])));
                this.rect.set(x, top, x + barW, getHeight());
                canvas.drawRoundRect(this.rect, r, r, this.fill);
            }
        }
    }
}
