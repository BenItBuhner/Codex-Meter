package dev.bennett.codexmeter;

import android.content.Context;
import java.util.List;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.ShapeAppearanceModel;
import dev.bennett.codexmeter.glass.GlassModel;

/**
 * Material 3 Expressive's cards, drawn from the same card data as Liquid Glass
 * ({@link GlassModel.Card}): the dashboard ({@link ExpressiveDashboard}) and every other page
 * ({@link ExpressivePages}) use them, so meters, charts, lists and notes look the same wherever
 * they appear.
 */
final class ExpressiveCards {
    private final Context ctx;
    private final boolean dark;
    private final ViewGroup parent;

    /** {@code ctx} must carry the Expressive theme ({@link ExpressiveDashboard#themed}). */
    ExpressiveCards(Context ctx, boolean dark, ViewGroup parent) {
        this.ctx = ctx;
        this.dark = dark;
        this.parent = parent;
    }

    int cardColor() {
        return color(this.dark ? R.attr.colorSurfaceContainerHigh : R.attr.colorSurfaceContainerLowest);
    }

    View card(GlassModel.Card c, boolean joinsPrev, boolean joinsNext) {
        LayoutInflater inflater = LayoutInflater.from(this.ctx);
        MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.expressive_card,
                this.parent, false);
        card.setCardBackgroundColor(cardColor());
        float big = Ui.dp(this.ctx, 28);
        float small = Ui.dp(this.ctx, ExpressiveRows.INNER);
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(joinsPrev ? small : big).setTopRightCornerSize(joinsPrev ? small : big)
                .setBottomLeftCornerSize(joinsNext ? small : big)
                .setBottomRightCornerSize(joinsNext ? small : big).build());
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        if (margins == null) {
            margins = new ViewGroup.MarginLayoutParams(-1, -2);
        }
        margins.bottomMargin = Ui.dp(this.ctx, joinsNext ? ExpressiveRows.GAP : 14);
        card.setLayoutParams(margins);
        LinearLayout body = card.findViewById(R.id.expressive_card_body);
        switch (c.kind) {
            case METER:
                meter(body, c);
                break;
            case HISTORY:
            case CHART:
                chartCard(body, c);
                break;
            case STATS:
                header(body, c.title, null);
                for (int i = 0; i < c.stats.size(); i++) {
                    String[] row = c.stats.get(i);
                    Runnable dismiss = i < c.statDismiss.size() ? c.statDismiss.get(i) : null;
                    View statRow;
                    if (dismiss != null) {
                        // two lines, as in the other designs: what it is, then its fact
                        statRow = twoLineRow(row[0], row.length > 1 ? row[1] : "");
                        SwipeDismiss.attach(statRow, dismiss, c.dismissAll);
                    } else {
                        statRow = statRow(row[0], row.length > 1 ? row[1] : "");
                    }
                    body.addView(statRow);
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
                    ExpressiveWave bar = new ExpressiveWave(this.ctx, color(R.attr.colorPrimary),
                            color(R.attr.colorSurfaceContainerHighest));
                    bar.setFraction(c.progress);
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
                    bp.topMargin = Ui.dp(this.ctx, 14);
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
     * A burn chart: the dashboard's Usage history (a header that names the window, the chart, and
     * View history) or the history page's window (the chart, its legend, and rows that pick an
     * earlier window to compare). Both draw {@link GlassModel.Chart}, the data Liquid Glass draws.
     */
    private void chartCard(LinearLayout body, GlassModel.Card c) {
        boolean dashboard = c.kind == GlassModel.Card.Kind.HISTORY;
        header(body, c.title, dashboard ? c.value : null);
        GlassModel.Chart data = c.chart;
        if (data != null) {
            LinearLayout top = new LinearLayout(this.ctx);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(data.label, R.attr.textAppearanceLabelLarge, R.attr.colorOnSurfaceVariant),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            top.addView(text(data.sampleLabel, R.attr.textAppearanceLabelMedium, R.attr.colorOnSurfaceVariant));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
            tp.topMargin = Ui.dp(this.ctx, 4);
            body.addView(top, tp);
            ExpressiveChartView chart = new ExpressiveChartView(this.ctx,
                    color(R.attr.colorPrimary), color(R.attr.colorError),
                    color(R.attr.colorOutline), cardColor());
            String rest = data.emptyText != null ? data.emptyText : data.hint == null ? "" : data.hint;
            TextView readout = text(rest, R.attr.textAppearanceBodySmall, R.attr.colorOnSurfaceVariant);
            chart.setChart(data, value -> readout.setText(value != null ? value : rest));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, Ui.dp(this.ctx, dashboard ? 140 : 180));
            cp.topMargin = Ui.dp(this.ctx, 8);
            body.addView(chart, cp);
            LinearLayout ends = new LinearLayout(this.ctx);
            ends.addView(text("0%", R.attr.textAppearanceLabelSmall, R.attr.colorOnSurfaceVariant),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            ends.addView(text("reset", R.attr.textAppearanceLabelSmall, R.attr.colorOnSurfaceVariant));
            body.addView(ends, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = Ui.dp(this.ctx, 6);
            body.addView(readout, rp);
            if (data.legend) {
                line(body, "Solid: this window · faint: earlier windows · dashed: even pace"
                        + (data.projection != null ? " and projection" : ""),
                        R.attr.textAppearanceBodySmall, R.attr.colorOnSurfaceVariant, 8);
            }
            if (!data.windows.isEmpty()) {
                List<View> checks = new java.util.ArrayList<>();
                int[] picked = {-1};
                for (Object[] w : data.windows) {
                    int index = (Integer) w[2];
                    View row = twoLineRow((String) w[0], (String) w[1]);
                    row.setBackgroundColor(index == picked[0] ? color(R.attr.colorSecondaryContainer) : cardColor());
                    row.setPadding(Ui.dp(this.ctx, 12), row.getPaddingTop(), Ui.dp(this.ctx, 12), row.getPaddingBottom());
                    row.setTag(index);
                    checks.add(row);
                    row.setOnClickListener(v -> {
                        picked[0] = picked[0] == index ? -1 : index;
                        chart.setWindow(picked[0]);
                        for (View other : checks) {
                            other.setBackgroundColor((Integer) other.getTag() == picked[0] && picked[0] != -1
                                    ? color(R.attr.colorSecondaryContainer) : cardColor());
                        }
                    });
                    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
                    wp.topMargin = Ui.dp(this.ctx, 2);
                    body.addView(row, wp);
                }
            }
        }
        line(body, c.summary, R.attr.textAppearanceBodyMedium, R.attr.colorOnSurfaceVariant, 10);
        if (dashboard && c.onPress != null) {
            button(body, "View history", c.onPress, true, false);
        }
    }

    /**
     * A usage window: its name and reset time on one line, what's left in large type, the wavy
     * bar, and the pace as a tonal pill (error colors when it runs out early).
     */
    private void meter(LinearLayout body, GlassModel.Card c) {
        LinearLayout top = new LinearLayout(this.ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(c.title, R.attr.textAppearanceTitleMediumEmphasized, R.attr.colorOnSurface);
        weight(name, 560);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (c.detail != null && !c.detail.isEmpty()) {
            top.addView(text(c.detail, R.attr.textAppearanceLabelLarge, R.attr.colorOnSurfaceVariant),
                    new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout amount = new LinearLayout(this.ctx);
        amount.setGravity(Gravity.BOTTOM);
        boolean empty = c.remaining <= 0.001;
        TextView value = text(c.value, R.attr.textAppearanceDisplayMediumEmphasized,
                empty ? R.attr.colorError : R.attr.colorOnSurface);
        weight(value, 600);
        value.setIncludeFontPadding(false);
        amount.addView(value, new LinearLayout.LayoutParams(-2, -2));
        TextView left = text(" left", R.attr.textAppearanceTitleMedium, R.attr.colorOnSurfaceVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = Ui.dp(this.ctx, 6);
        amount.addView(left, lp);
        amount.setContentDescription(c.title + ", " + c.value + " left");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this.ctx, 10);
        body.addView(amount, ap);

        ExpressiveWave bar = new ExpressiveWave(this.ctx,
                color(c.fast || empty ? R.attr.colorError : R.attr.colorPrimary),
                color(c.fast || empty ? R.attr.colorErrorContainer : R.attr.colorSecondaryContainer));
        bar.setFraction(c.remaining);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(this.ctx, 12);
        body.addView(bar, bp);

        if (c.paceLabel != null && c.pace != null) {
            TextView pill = text(c.paceLabel + " · " + c.pace, R.attr.textAppearanceLabelLarge,
                    c.fast ? R.attr.colorOnErrorContainer : R.attr.colorOnSecondaryContainer);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Ui.dp(this.ctx, 16));
            bg.setColor(color(c.fast ? R.attr.colorErrorContainer : R.attr.colorSecondaryContainer));
            pill.setBackground(bg);
            int px = Ui.dp(this.ctx, 12);
            int py = Ui.dp(this.ctx, 6);
            pill.setPadding(px, py, px, py);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
            pp.topMargin = Ui.dp(this.ctx, 14);
            body.addView(pill, pp);
        }
        line(body, c.summary, R.attr.textAppearanceBodySmall,
                R.attr.colorOnSurfaceVariant, 8);
    }

    private void header(LinearLayout body, String name, String value) {
        LinearLayout row = new LinearLayout(this.ctx);
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
        LinearLayout row = new LinearLayout(this.ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this.ctx, 10), 0, 0);
        row.addView(text(label, R.attr.textAppearanceBodyLarge,
                R.attr.colorOnSurface),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(value, R.attr.textAppearanceLabelLarge,
                R.attr.colorOnSurfaceVariant),
                new LinearLayout.LayoutParams(-2, -2));
        return row;
    }
    /** A dismissible row: its title over its fact, on the card's color so a swipe moves it whole. */
    private View twoLineRow(String title, String fact) {
        LinearLayout row = new LinearLayout(this.ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this.ctx, 10), 0, Ui.dp(this.ctx, 10));
        row.setMinimumHeight(Ui.dp(this.ctx, 56));
        row.setBackgroundColor(cardColor());
        row.addView(text(title, R.attr.textAppearanceBodyLarge, R.attr.colorOnSurface));
        if (fact != null && !fact.isEmpty()) {
            row.addView(text(fact, R.attr.textAppearanceBodyMedium, R.attr.colorOnSurfaceVariant));
        }
        return row;
    }

    private void button(LinearLayout body, String label, Runnable onPress, boolean tonal,
            boolean disabled) {
        MaterialButton button = (MaterialButton) LayoutInflater.from(this.ctx).inflate(
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
        params.topMargin = Ui.dp(this.ctx, top);
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
    View heading(String text) {
        TextView view = text(text, R.attr.textAppearanceTitleSmallEmphasized, R.attr.colorPrimary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Ui.dp(this.ctx, 16), Ui.dp(this.ctx, 8), Ui.dp(this.ctx, 16),
                Ui.dp(this.ctx, 8));
        view.setLayoutParams(params);
        return view;
    }

    TextView text(String text, int appearance, int colorAttr) {
        TextView view = new TextView(this.ctx);
        android.util.TypedValue value = new android.util.TypedValue();
        if (this.ctx.getTheme().resolveAttribute(appearance, value, true)) {
            TextViewCompat.setTextAppearance(view, value.resourceId);
        }
        view.setTextColor(color(colorAttr));
        view.setText(text == null ? "" : text);
        return view;
    }

    int color(int attr) {
        return MaterialColors.getColor(this.ctx, attr, 0xFF808080);
    }
}
