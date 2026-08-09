package dev.bennett.codexmeter;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full local-history view: scrubbable charts always, with every extra highlight —
 * chart guide, previous-window list, insight rows, and value estimates — individually
 * customizable so the page can stay as minimal as the user likes.
 */
public final class UsageHistoryActivity extends AppCompatActivity {
    private static final int MAX_BREAKDOWN_WINDOWS = 5;
    private static final int MENU_CUSTOMIZE = 8201;

    private LinearLayout content;
    private boolean dark;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        dark = Ui.isDark(this);
        content = Ui.installPage(this, "Usage history", true).content;
        render();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_CUSTOMIZE, 0, "Customize")
                .setIcon(R.drawable.ic_oui_edit_outline)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CUSTOMIZE) {
            showCustomizeDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Checklist of every optional highlight; changes persist and apply immediately. */
    private void showCustomizeDialog() {
        List<String> keys = HistorySections.all();
        String[] labels = new String[keys.size()];
        boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            labels[i] = HistorySections.label(keys.get(i));
            checked[i] = AppPreferences.isHistorySectionVisible(this, keys.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Highlights to show")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        AppPreferences.setHistorySectionVisible(this, keys.get(which), isChecked))
                .setPositiveButton("Done", null)
                .setOnDismissListener(dialog -> render())
                .show();
    }

    private boolean visible(String key) {
        return AppPreferences.isHistorySectionVisible(this, key);
    }

    private void render() {
        content.removeAllViews();
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        UsageHistory five = AppPreferences.loadUsageHistory(this, UsageHistory.FIVE_HOUR);
        UsageHistory weekly = AppPreferences.loadUsageHistory(this, UsageHistory.WEEKLY);
        // Dollar figures ride on the value-estimates highlight; hiding it hides them all.
        PlanPricing pricing = snapshot == null || !visible(HistorySections.VALUE_ESTIMATES)
                ? null : PlanPricing.forPlan(snapshot.planType);

        if (visible(HistorySections.GUIDE)) {
            LinearLayout guide = Ui.card(this, dark);
            guide.addView(Ui.text(this,
                    "The solid line is this window's usage, faint lines are previous windows, "
                            + "the dotted diagonal is a sustainable pace, and the dashed line is "
                            + "the projection. Drag a chart to inspect any moment. Samples are "
                            + "recorded after each successful refresh and stay on this device.",
                    13, Ui.secondaryText(dark)));
            content.addView(guide);
            Ui.addSpacer(content, 20);
        }

        // Windows still waiting for usage data are skipped instead of rendering blank charts.
        boolean hasCharts = false;
        UsageWindow fiveWindow = snapshot == null ? null : snapshot.fiveHour;
        if (fiveWindow != null && snapshot.fetchedAtMillis > 0L) {
            addWindowSection("5-hour", fiveWindow, snapshot, five, pricing);
            hasCharts = true;
        }
        UsageWindow weeklyWindow = snapshot == null ? null : snapshot.weekly;
        if (weeklyWindow != null && snapshot.fetchedAtMillis > 0L) {
            addWindowSection("Weekly", weeklyWindow, snapshot, weekly, pricing);
            hasCharts = true;
        }
        if (!hasCharts) {
            LinearLayout waiting = Ui.card(this, dark);
            waiting.addView(Ui.text(this,
                    "Charts appear once OpenAI reports your 5-hour or weekly usage windows. "
                            + "Refresh usage from the dashboard to check again.",
                    13, Ui.secondaryText(dark)));
            content.addView(waiting);
            Ui.addSpacer(content, 20);
        }

        if (pricing != null && hasCharts) {
            content.addView(Ui.separator(this, "Estimated value"));
            content.addView(buildValueCard(snapshot, pricing));
            Ui.addSpacer(content, 20);
        }

        Button clear = Ui.button(this, "Clear local history", false, dark);
        clear.setEnabled(!five.samples.isEmpty() || !weekly.samples.isEmpty());
        clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Clear usage history?")
                .setMessage("This removes every locally stored usage sample. Your latest "
                        + "allowance and account sign-in stay intact.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    AppPreferences.clearUsageHistory(this);
                    render();
                })
                .show());
        content.addView(clear, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));
    }

    private void addWindowSection(String label, UsageWindow window, UsageSnapshot snapshot,
            UsageHistory history, PlanPricing pricing) {
        content.addView(Ui.separator(this, label + " window"));
        content.addView(buildChartCard(label, window, snapshot, history, pricing));
        Ui.addSpacer(content, 12);
        LinearLayout insights = buildInsightsCard(window, snapshot, history, pricing);
        if (insights != null) {
            content.addView(insights);
            Ui.addSpacer(content, 12);
        }
        Ui.addSpacer(content, 8);
    }

    private LinearLayout buildChartCard(String label, UsageWindow window, UsageSnapshot snapshot,
            UsageHistory history, PlanPricing pricing) {
        LinearLayout card = Ui.card(this, dark);
        card.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        long now = System.currentTimeMillis();
        UsagePace.Assessment pace = snapshot == null
                ? UsagePace.assess(null, 0L, now, UsagePace.BALANCED)
                : UsagePacePreferences.assess(this, snapshot, window, now);
        UsageBurnChartView chart = new UsageBurnChartView(this);
        chart.setScrubEnabled(true);
        chart.setData(label, window, history,
                snapshot == null ? now : snapshot.fetchedAtMillis, pace);
        card.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 200)));

        List<UsageStats.WindowStats> breakdown =
                UsageStats.windowBreakdown(history, MAX_BREAKDOWN_WINDOWS);
        boolean showWindowRows = visible(HistorySections.WINDOW_LIST) && breakdown.size() > 1;

        String defaultDetail = showWindowRows
                ? "Drag to inspect · tap a window to compare" : "Drag to inspect";
        TextView scrubDetail = Ui.text(this, defaultDetail, 12, Ui.secondaryText(dark));
        LinearLayout.LayoutParams scrubParams = new LinearLayout.LayoutParams(-1, -2);
        scrubParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 6));
        card.addView(scrubDetail, scrubParams);
        chart.setOnScrubListener(new UsageBurnChartView.OnScrubListener() {
            @Override
            public void onScrub(long timeMillis, double usedPercent, boolean historicalWindow) {
                String moment = UsageFormat.absolute(UsageHistoryActivity.this, timeMillis,
                        System.currentTimeMillis());
                String text = moment + " — " + Math.round(usedPercent) + "% used";
                if (pricing != null) {
                    text += " · ≈ " + PlanPricing.formatUsd(
                            pricing.estimatedValueUsd(history.kind, usedPercent));
                }
                scrubDetail.setTextColor(Ui.mainText(dark));
                scrubDetail.setText(text);
            }

            @Override
            public void onScrubEnd() {
                scrubDetail.setTextColor(Ui.secondaryText(dark));
                scrubDetail.setText(defaultDetail);
            }
        });

        if (showWindowRows) {
            View divider = new View(this);
            divider.setBackgroundColor(Ui.divider(dark));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, 1);
            dividerParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12),
                    Ui.dp(this, 4));
            card.addView(divider, dividerParams);
            addWindowRows(card, chart, history, breakdown, pricing);
        }
        return card;
    }

    /** Tappable per-window rows that select a window on the chart for scrubbing. */
    private void addWindowRows(LinearLayout card, UsageBurnChartView chart, UsageHistory history,
            List<UsageStats.WindowStats> breakdown, PlanPricing pricing) {
        boolean weekly = UsageHistory.WEEKLY.equals(history.kind);
        TextView[] titles = new TextView[breakdown.size()];
        Runnable[] selections = new Runnable[breakdown.size()];
        for (int index = breakdown.size() - 1; index >= 0; index--) {
            UsageStats.WindowStats stats = breakdown.get(index);
            boolean current = !stats.complete;
            String rowTitle = current ? "Current window" : windowRangeLabel(stats, weekly);
            StringBuilder subtitle = new StringBuilder();
            subtitle.append(stats.finalPercent).append("% used");
            if (stats.averageBurnPercentPerHour > 0d) {
                subtitle.append(" · avg ").append(formatRate(stats.averageBurnPercentPerHour));
            }
            if (pricing != null) {
                subtitle.append(" · ≈ ").append(PlanPricing.formatUsd(
                        pricing.estimatedValueUsd(history.kind, stats.finalPercent)));
            }
            if (stats.exhausted) subtitle.append(" · hit limit");

            LinearLayout row = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = Ui.text(this, rowTitle, 14,
                    current ? Ui.accent(this, dark) : Ui.mainText(dark));
            titleView.setTypeface(Ui.mediumTypeface(this));
            texts.addView(titleView);
            texts.addView(Ui.text(this, subtitle.toString(), 12, Ui.secondaryText(dark)));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));

            titles[index] = titleView;
            int chartWindowIndex = chart.windowCount() - breakdown.size() + index;
            boolean selectsCurrent = current;
            int rowIndex = index;
            selections[index] = () -> {
                chart.setSelectedWindow(selectsCurrent ? -1 : chartWindowIndex);
                for (int i = 0; i < titles.length; i++) {
                    boolean selected = i == rowIndex;
                    titles[i].setTextColor(selected ? Ui.accent(this, dark)
                            : Ui.mainText(dark));
                }
            };
            row.setOnClickListener(view -> selections[rowIndex].run());
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription("Inspect " + rowTitle + ". " + subtitle);
        }
    }

    private LinearLayout buildInsightsCard(UsageWindow window, UsageSnapshot snapshot,
            UsageHistory history, PlanPricing pricing) {
        long now = System.currentTimeMillis();
        long observedAt = snapshot == null ? now : snapshot.fetchedAtMillis;
        LinearLayout card = Ui.card(this, dark);
        TextView title = Ui.text(this, "Insights", 16, Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        int rows = 0;

        // Current position against the typical pace of completed windows.
        long resetAt = window.effectiveResetAtMillis(observedAt);
        long durationMillis = window.windowSeconds * 1000L;
        if (visible(HistorySections.INSIGHT_PACE) && resetAt > 0L && durationMillis > 0L) {
            double elapsedFraction = 1d - Math.max(0d, Math.min(1d,
                    (resetAt - now) / (double) durationMillis));
            double typical = UsageStats.typicalUsedPercentAt(history, elapsedFraction);
            if (typical >= 0d) {
                long delta = Math.round(window.usedPercent - typical);
                String value;
                if (delta >= 2L) {
                    value = delta + " pts ahead of typical";
                } else if (delta <= -2L) {
                    value = (-delta) + " pts behind typical";
                } else {
                    value = "On par with typical";
                }
                addStatRow(card, "Pace vs. previous windows", value);
                rows++;
            }
        }

        if (visible(HistorySections.INSIGHT_EXHAUSTION)) {
            UsagePace.Assessment pace = snapshot == null ? null
                    : UsagePacePreferences.assess(this, snapshot, window, now);
            if (pace != null && pace.available) {
                addStatRow(card, "Projected exhaustion",
                        UsageFormat.relative(pace.estimatedExhaustionAtMillis, now));
                rows++;
            }
        }

        if (visible(HistorySections.INSIGHT_AVERAGE)) {
            double averageFinal = UsageStats.averageFinalPercent(history);
            if (averageFinal >= 0d) {
                addStatRow(card, "Avg. completed window", Math.round(averageFinal) + "% used");
                rows++;
            }
        }

        if (visible(HistorySections.INSIGHT_PEAK)) {
            double peakBurn = UsageStats.peakBurnPercentPerHour(history);
            if (peakBurn > 0d) {
                String value = formatRate(peakBurn);
                if (pricing != null) {
                    value += " · ≈ " + PlanPricing.formatUsd(
                            pricing.windowValueUsd(history.kind) * peakBurn / 100d) + "/h";
                }
                addStatRow(card, "Peak burn observed", value);
                rows++;
            }
        }

        if (pricing != null) {
            addStatRow(card, "Est. value used this window",
                    "≈ " + PlanPricing.formatUsd(pricing.estimatedValueUsd(history.kind,
                            window.usedPercent))
                            + " of " + PlanPricing.formatUsd(
                                    pricing.windowValueUsd(history.kind)));
            rows++;
        }
        return rows == 0 ? null : card;
    }

    private LinearLayout buildValueCard(UsageSnapshot snapshot, PlanPricing pricing) {
        LinearLayout card = Ui.card(this, dark);
        TextView title = Ui.text(this, pricing.planLabel + " · "
                + PlanPricing.formatUsd(pricing.monthlyPriceUsd) + "/month", 16,
                Ui.mainText(dark));
        title.setTypeface(Ui.mediumTypeface(this));
        card.addView(title);
        addStatRow(card, "Est. included usage",
                "≈ " + PlanPricing.formatUsd(pricing.monthlyValueUsd) + "/month");
        addStatRow(card, "Weekly allowance",
                "≈ " + PlanPricing.formatUsd(pricing.weeklyValueUsd()));
        addStatRow(card, "5-hour allowance",
                "≈ " + PlanPricing.formatUsd(pricing.fiveHourValueUsd()));
        addStatRow(card, "Vs. subscription price",
                "≈ " + Math.round(pricing.valueMultiplier()) + "x the monthly cost");
        if (snapshot.weekly != null) {
            addStatRow(card, "Weekly value remaining",
                    "≈ " + PlanPricing.formatUsd(pricing.estimatedValueUsd(UsageHistory.WEEKLY,
                            snapshot.weekly.remainingPercent())));
        }
        TextView disclaimer = Ui.text(this,
                "Rough community estimates comparing plan allowances with API pricing; "
                        + "not a billing statement.",
                12, Ui.secondaryText(dark));
        LinearLayout.LayoutParams disclaimerParams = new LinearLayout.LayoutParams(-1, -2);
        disclaimerParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        card.addView(disclaimer, disclaimerParams);
        return card;
    }

    private void addStatRow(LinearLayout card, String label, String value) {
        LinearLayout row = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, Ui.dp(this, 8), 0, 0);
        TextView labelView = Ui.text(this, label, 13, Ui.secondaryText(dark));
        row.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView valueView = Ui.text(this, value, 13, Ui.mainText(dark));
        valueView.setTypeface(Typeface.create("sec", Typeface.NORMAL));
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row, rowParams);
    }

    private String windowRangeLabel(UsageStats.WindowStats stats, boolean weekly) {
        boolean is24Hour = DateFormat.is24HourFormat(this);
        if (weekly) {
            SimpleDateFormat day = new SimpleDateFormat("MMM d", Locale.getDefault());
            return day.format(new Date(stats.windowStartMillis)) + " – "
                    + day.format(new Date(stats.resetAtMillis));
        }
        SimpleDateFormat day = new SimpleDateFormat("MMM d", Locale.getDefault());
        SimpleDateFormat time = new SimpleDateFormat(is24Hour ? "HH:mm" : "h:mm a",
                Locale.getDefault());
        return day.format(new Date(stats.windowStartMillis)) + " · "
                + time.format(new Date(stats.windowStartMillis)) + " – "
                + time.format(new Date(stats.resetAtMillis));
    }

    private static String formatRate(double percentPerHour) {
        if (percentPerHour >= 10d) {
            return Math.round(percentPerHour) + "%/h";
        }
        return String.format(Locale.US, "%.1f%%/h", percentPerHour);
    }
}
