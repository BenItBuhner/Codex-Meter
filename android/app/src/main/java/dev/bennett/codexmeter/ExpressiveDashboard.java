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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import dev.bennett.codexmeter.glass.GlassModel;
import java.util.List;

/**
 * The Material 3 Expressive design's dashboard, drawn with Material Components' own Expressive
 * views: a large flexible header, filled cards with wavy progress bars, a loading indicator while
 * refreshing, and a floating toolbar of provider tabs.
 *
 * <p>It shows the same cards as Liquid Glass ({@link GlassCards}), so both designs always list the
 * same things. Settings and the secondary pages open as the One UI pages for now.
 */
final class ExpressiveDashboard {
    /** The One UI blue the app uses when Material You is off, as the color scheme's seed. */
    private static final int SEED = 0xFF0381FE;

    private final MainActivity activity;
    private final View root;
    private final NestedScrollView scroll;
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
    static void applyTheme(MainActivity activity) {
        Ui.applySelectedTheme(activity);
        activity.setTheme(R.style.AppTheme_Expressive);
        if (AppPreferences.isMaterialYouEnabled(activity)) {
            DynamicColors.applyToActivityIfAvailable(activity);
        } else {
            DynamicColors.applyToActivityIfAvailable(activity,
                    new DynamicColorsOptions.Builder().setContentBasedSource(SEED).build());
        }
    }

    ExpressiveDashboard(MainActivity activity, boolean dark) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.expressive_dashboard, null);
        activity.setContentView(this.root);
        this.scroll = this.root.findViewById(R.id.expressive_scroll);
        this.title = this.root.findViewById(R.id.expressive_title);
        this.subtitle = this.root.findViewById(R.id.expressive_subtitle);
        this.loading = this.root.findViewById(R.id.expressive_loading);
        this.cards = this.root.findViewById(R.id.expressive_cards);
        this.tabs = this.root.findViewById(R.id.expressive_tabs);
        this.toolbar = this.root.findViewById(R.id.expressive_toolbar);

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowCompat.getInsetsController(activity.getWindow(), this.root)
                .setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(activity.getWindow(), this.root)
                .setAppearanceLightNavigationBars(!dark);
        ViewCompat.setOnApplyWindowInsetsListener(this.root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            this.scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
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
        this.loading.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** Draws the selected provider's cards and the provider tabs. */
    void render(UsageProvider provider, List<UsageProvider> enabled) {
        GlassModel.Dashboard model = GlassCards.build(this.activity, provider);
        this.title.setText(model.title);
        this.subtitle.setText(model.subtitle == null ? "" : model.subtitle);
        this.subtitle.setVisibility(model.subtitle == null ? View.INVISIBLE : View.VISIBLE);
        this.cards.removeAllViews();
        for (GlassModel.Card card : model.cards) {
            this.cards.addView(card(card));
        }
        renderTabs(provider, enabled);
    }

    private void renderTabs(UsageProvider selected, List<UsageProvider> enabled) {
        this.tabs.removeAllViews();
        this.toolbar.setVisibility(enabled.size() > 1 ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this.activity);
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

    private View card(GlassModel.Card c) {
        LayoutInflater inflater = LayoutInflater.from(this.activity);
        MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.expressive_card,
                this.cards, false);
        LinearLayout body = card.findViewById(R.id.expressive_card_body);
        switch (c.kind) {
            case METER:
                meter(body, c);
                break;
            case HISTORY:
                header(body, c.title, c.value);
                body.addView(new Bars(this.activity, c.bars,
                                color(R.attr.colorPrimary),
                                color(R.attr.colorSurfaceContainerHighest)),
                        new LinearLayout.LayoutParams(-1, Ui.dp(this.activity, 72)));
                if (c.startLabel != null && !c.startLabel.isEmpty()) {
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
                        R.attr.colorOnSurfaceVariant, 10);
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
                if (c.kind == GlassModel.Card.Kind.UPDATE) {
                    card.setCardBackgroundColor(color(c.warning
                            ? R.attr.colorErrorContainer
                            : R.attr.colorPrimaryContainer));
                }
                line(body, c.title, R.attr.textAppearanceHeadlineSmall,
                        R.attr.colorOnSurface, 0);
                line(body, c.summary, R.attr.textAppearanceBodyLarge,
                        R.attr.colorOnSurfaceVariant, 8);
                if (c.button != null && c.onPress != null) {
                    button(body, c.button, c.onPress, false, !c.buttonEnabled);
                }
                if (c.progress >= 0) {
                    LinearProgressIndicator bar = (LinearProgressIndicator) inflater.inflate(
                            R.layout.expressive_meter, body, false);
                    bar.setProgressCompat((int) Math.round(c.progress * 100), false);
                    body.addView(bar);
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
            case MESSAGE:
            case NOTE:
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

    /** A usage window: name, % left in large type, the wavy bar, reset time and pace. */
    private void meter(LinearLayout body, GlassModel.Card c) {
        line(body, c.title, R.attr.textAppearanceTitleMedium,
                R.attr.colorOnSurfaceVariant, 0);
        TextView value = line(body, c.value + " left",
                R.attr.textAppearanceDisplaySmallEmphasized,
                c.fast ? R.attr.colorError
                        : R.attr.colorOnSurface, 2);
        value.setContentDescription(c.title + ", " + c.value + " left");
        LinearProgressIndicator bar = (LinearProgressIndicator) LayoutInflater.from(this.activity)
                .inflate(R.layout.expressive_meter, body, false);
        bar.setProgressCompat((int) Math.round(Math.max(0, Math.min(1, c.remaining)) * 100), false);
        if (c.fast) {
            bar.setIndicatorColor(color(R.attr.colorError));
        }
        // The wave is the Expressive bar's own; it flattens as the window empties.
        bar.setWaveAmplitude(c.remaining < 0.08 ? 0 : Ui.dp(this.activity, 3));
        body.addView(bar);
        line(body, c.detail, R.attr.textAppearanceBodyMedium,
                R.attr.colorOnSurfaceVariant, 12);
        if (c.paceLabel != null && c.pace != null) {
            line(body, c.paceLabel + " · " + c.pace,
                    R.attr.textAppearanceLabelLarge,
                    c.fast ? R.attr.colorError
                            : R.attr.colorPrimary, 4);
        }
        line(body, c.summary, R.attr.textAppearanceBodySmall,
                R.attr.colorOnSurfaceVariant, 4);
    }

    private void header(LinearLayout body, String name, String value) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(name, R.attr.textAppearanceTitleMedium,
                R.attr.colorOnSurface);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            TextView right = text(value, R.attr.textAppearanceTitleMedium,
                    R.attr.colorPrimary);
            row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View statRow(String label, String value) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setPadding(0, Ui.dp(this.activity, 8), 0, 0);
        row.addView(text(label, R.attr.textAppearanceBodyLarge,
                R.attr.colorOnSurfaceVariant),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(value, R.attr.textAppearanceTitleMedium,
                R.attr.colorOnSurface),
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
