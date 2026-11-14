package dev.bennett.codexmeter;

import android.content.Context;
import android.text.format.DateFormat;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Card;
import dev.bennett.codexmeter.glass.GlassModel.Section;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Usage history in Liquid Glass (UsageHistoryActivity): a scrubbable burn chart per window with
 * its earlier windows to compare, insights, value estimates and the highlights picker, from the
 * same history the One UI page reads.
 */
final class GlassHistoryScreen extends GlassScreens.Screen {
    private static final int MAX_BREAKDOWN_WINDOWS = 5;
    private final UsageProvider provider;
    /** The window the page shows (its segmented control). */
    private int windowIndex;

    GlassHistoryScreen(GlassScreens screens, UsageProvider provider) {
        super(screens);
        this.provider = provider;
    }

    private boolean visible(String key) {
        return AppPreferences.isHistorySectionVisible(activity(), key);
    }

    @Override
    GlassModel.Page page() {
        Context c = activity();
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "history:" + provider.id,
                provider == UsageProvider.CODEX ? "Usage history" : provider.displayName + " history");
        page.actions.add(new GlassModel.Action("Customize", "sliders", false, this::customize));
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(c, provider);
        UsageHistory five = ProviderPreferences.loadUsageHistory(c, provider, UsageHistory.FIVE_HOUR);
        UsageHistory weekly = ProviderPreferences.loadUsageHistory(c, provider, UsageHistory.WEEKLY);
        UsageHistory monthly = ProviderPreferences.loadUsageHistory(c, provider, UsageHistory.MONTHLY);
        PlanPricing pricing = snapshot == null || !visible(HistorySections.VALUE_ESTIMATES)
                ? null : PlanPricing.forPlan(snapshot.planType);

        // One window at a time, picked with a segmented control at the top (iOS's way of
        // switching between views of the same data), instead of every chart stacked.
        List<String> labels = new ArrayList<>();
        List<UsageWindow> shown = new ArrayList<>();
        List<UsageHistory> histories = new ArrayList<>();
        if (snapshot != null && snapshot.fetchedAtMillis > 0L) {
            if (snapshot.weekly != null) {
                labels.add("Weekly");
                shown.add(snapshot.weekly);
                histories.add(weekly);
            }
            if (snapshot.fiveHour != null) {
                labels.add("5-hour");
                shown.add(snapshot.fiveHour);
                histories.add(five);
            }
            if (snapshot.monthly != null) {
                labels.add("Monthly");
                shown.add(snapshot.monthly);
                histories.add(monthly);
            }
        }
        boolean hasCharts = !shown.isEmpty();
        if (hasCharts) {
            int index = Math.max(0, Math.min(windowIndex, shown.size() - 1));
            if (shown.size() > 1) {
                Section pick = GlassScreens.section(null);
                GlassModel.Row segmented = GlassScreens.row(GlassModel.Row.Kind.SEGMENTED, "window", "", null);
                segmented.entries = labels.toArray(new String[0]);
                segmented.selected = index;
                segmented.onChoose = which -> {
                    windowIndex = which;
                    changed();
                };
                pick.rows.add(segmented);
                page.sections.add(pick);
            }
            addWindow(page, labels.get(index), shown.get(index), snapshot, histories.get(index), pricing);
        }
        if (!hasCharts) {
            page.sections.add(GlassScreens.cards(GlassScreens.note(null,
                    "Charts appear once " + provider.vendorName + " reports your 5-hour, weekly, or "
                            + "monthly usage windows. Refresh usage from the dashboard to check again.")));
        }
        if (pricing != null && hasCharts) {
            Card value = new Card(Card.Kind.STATS);
            value.detail = "Estimated value";
            value.title = pricing.planLabel + " · " + PlanPricing.formatUsd(pricing.monthlyPriceUsd) + "/month";
            value.stats.add(new String[] {"Est. included usage",
                    "≈ " + PlanPricing.formatUsd(pricing.monthlyValueUsd) + "/month"});
            value.stats.add(new String[] {"Weekly allowance", "≈ " + PlanPricing.formatUsd(pricing.weeklyValueUsd())});
            value.stats.add(new String[] {"5-hour allowance",
                    "≈ " + PlanPricing.formatUsd(pricing.fiveHourValueUsd())});
            value.stats.add(new String[] {"Vs. subscription price",
                    "≈ " + Math.round(pricing.valueMultiplier()) + "x the monthly cost"});
            if (snapshot.weekly != null) {
                value.stats.add(new String[] {"Weekly value remaining", "≈ " + PlanPricing.formatUsd(
                        pricing.estimatedValueUsd(UsageHistory.WEEKLY, snapshot.weekly.remainingPercent()))});
            }
            value.summary = "Rough community estimates comparing plan allowances with API pricing; "
                    + "not a billing statement.";
            page.sections.add(GlassScreens.cards(value));
        }
        Section clear = GlassScreens.section(null);
        GlassModel.Row button = GlassScreens.row(GlassModel.Row.Kind.ACTION, "clear", "Clear local history", null);
        button.destructive = true;
        button.enabled = !five.samples.isEmpty() || !weekly.samples.isEmpty() || !monthly.samples.isEmpty();
        button.onPress = this::confirmClear;
        clear.rows.add(button);
        page.sections.add(clear);
        return page;
    }

    private void customize() {
        List<String> keys = HistorySections.all();
        String[] labels = new String[keys.size()];
        boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            labels[i] = HistorySections.label(keys.get(i));
            checked[i] = visible(keys.get(i));
        }
        new AppDialog(activity())
                .setTitle("Highlights to show")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        AppPreferences.setHistorySectionVisible(activity(), keys.get(which), isChecked))
                .setPositiveButton("Done", null)
                .setOnDismissListener(dialog -> changed())
                .show();
    }

    private void confirmClear() {
        new AppDialog(activity())
                .setTitle("Clear usage history?")
                .setMessage("This removes every locally stored usage sample. Your latest "
                        + "allowance and account sign-in stay intact.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    ProviderPreferences.clearUsageHistory(activity(), provider);
                    changed();
                })
                .show();
    }

    private void addWindow(GlassModel.Page page, String label, UsageWindow window, UsageSnapshot snapshot,
            UsageHistory recorded, PlanPricing pricing) {
        // Current-window samples are only those of the window reported now (see withLiveWindow).
        UsageHistory history = recorded.withLiveWindow(window, snapshot.fetchedAtMillis);
        Section section = GlassScreens.section(null);
        section.cards.add(chart(label, window, snapshot, history, pricing));
        Card insights = insights(window, snapshot, history, pricing);
        if (insights != null) {
            section.cards.add(insights);
        }
        page.sections.add(section);
    }

    private Card chart(String label, UsageWindow window, UsageSnapshot snapshot, UsageHistory history,
            PlanPricing pricing) {
        Context c = activity();
        long now = System.currentTimeMillis();
        UsagePace.Assessment pace = UsagePacePreferences.assess(c, provider, snapshot, window, now);
        long observedAt = snapshot.fetchedAtMillis;
        List<UsageSample> samples = history.currentWindowSamples();
        List<List<UsageSample>> windows = history.recentWindows(5);

        Card card = new Card(Card.Kind.CHART);
        card.title = label + " window";
        GlassModel.Chart chart = new GlassModel.Chart();
        card.chart = chart;
        chart.label = Math.round(window.usedPercent) + "% used so far";
        chart.legend = visible(HistorySections.GUIDE);
        chart.sampleLabel = samples.size() < 2 ? "Building history" : samples.size() + " samples";
        long resetAt = window.effectiveResetAtMillis(observedAt);
        long startAt = resetAt - window.windowSeconds * 1000L;
        boolean liveAxis = window.windowSeconds > 0L && resetAt > observedAt;
        if (!liveAxis) {
            // Claude's idle 5-hour session (or a window whose reset has passed) has no axis;
            // earlier windows still show faintly, each on its own axis.
            chart.emptyText = window.resetAtMillis() <= 0L && window.resetAfterSeconds <= 0L
                    ? "No window running. It starts with your next request."
                    : "Window reset. Waiting for the next refresh.";
            chart.sampleLabel = "";
        }
        chart.current = liveAxis ? points(samples, startAt, resetAt) : new double[0];
        for (int i = 0; i < windows.size() - 1; i++) {
            List<UsageSample> previous = windows.get(i);
            if (previous.size() < 2) {
                chart.previous.add(null);
                continue;
            }
            UsageSample reference = previous.get(previous.size() - 1);
            chart.previous.add(points(previous, reference.resetAtMillis - reference.windowSeconds * 1000L,
                    reference.resetAtMillis));
        }
        if (!liveAxis) {
            return card;
        }
        if (pace != null && pace.available && !samples.isEmpty()) {
            UsageSample latest = samples.get(samples.size() - 1);
            long endAt = Math.min(resetAt, pace.estimatedExhaustionAtMillis);
            if (latest.usedPercent < 100 && endAt > latest.observedAtMillis) {
                double endPercent = UsagePace.projectedUsedPercent(pace, latest.observedAtMillis,
                        latest.usedPercent, endAt);
                chart.projection = new double[] {ratio(latest.observedAtMillis, startAt, resetAt),
                        latest.usedPercent, ratio(endAt, startAt, resetAt), endPercent};
                chart.projectionWarning = pace.accelerated;
            }
        }
        chart.scrub = new GlassModel.Scrub() {
            @Override
            public String at(int index, double r) {
                long[] axis = axis(index);
                List<UsageSample> active = active(index);
                if (axis == null || active.isEmpty()) {
                    return null;
                }
                long time = clamp(active, axis[0] + Math.round(r * (axis[1] - axis[0])));
                double percent = percentAt(active, time);
                String text = UsageFormat.absolute(c, time, System.currentTimeMillis()) + " — "
                        + Math.round(percent) + "% used";
                if (pricing != null) {
                    text += " · ≈ " + PlanPricing.formatUsd(pricing.estimatedValueUsd(history.kind, percent));
                }
                return text;
            }

            @Override
            public double percent(int index, double r) {
                long[] axis = axis(index);
                List<UsageSample> active = active(index);
                if (axis == null || active.isEmpty()) {
                    return -1;
                }
                long time = clamp(active, axis[0] + Math.round(r * (axis[1] - axis[0])));
                return percentAt(active, time);
            }

            private List<UsageSample> active(int index) {
                return index >= 0 && index < windows.size() - 1 ? windows.get(index) : samples;
            }

            private long[] axis(int index) {
                if (index >= 0 && index < windows.size() - 1) {
                    List<UsageSample> selected = windows.get(index);
                    UsageSample reference = selected.get(selected.size() - 1);
                    return new long[] {reference.resetAtMillis - reference.windowSeconds * 1000L,
                            reference.resetAtMillis};
                }
                return new long[] {startAt, resetAt};
            }
        };

        List<UsageStats.WindowStats> breakdown = UsageStats.windowBreakdown(history, MAX_BREAKDOWN_WINDOWS);
        boolean rows = visible(HistorySections.WINDOW_LIST) && breakdown.size() > 1;
        chart.hint = rows ? "Drag to inspect · tap a window to compare" : "Drag to inspect";
        if (rows) {
            boolean days = UsageHistory.WEEKLY.equals(history.kind) || UsageHistory.MONTHLY.equals(history.kind);
            for (int index = breakdown.size() - 1; index >= 0; index--) {
                UsageStats.WindowStats stats = breakdown.get(index);
                boolean current = !stats.complete;
                String title = current ? "Current window" : rangeLabel(stats, days);
                StringBuilder subtitle = new StringBuilder();
                subtitle.append(stats.finalPercent).append("% used");
                if (stats.averageBurnPercentPerHour > 0d) {
                    subtitle.append(" · avg ").append(formatRate(stats.averageBurnPercentPerHour));
                }
                if (pricing != null) {
                    subtitle.append(" · ≈ ").append(PlanPricing.formatUsd(
                            pricing.estimatedValueUsd(history.kind, stats.finalPercent)));
                }
                if (stats.exhausted) {
                    subtitle.append(" · hit limit");
                }
                // Breakdown and chart read the same recent windows; only an empty current
                // window is missing from the breakdown, and it is always last.
                int chartIndex = current ? -1 : index;
                chart.windows.add(new Object[] {title, subtitle.toString(), chartIndex});
            }
        }
        return card;
    }

    private static long clamp(List<UsageSample> active, long time) {
        long first = active.get(0).observedAtMillis;
        long last = active.get(active.size() - 1).observedAtMillis;
        return Math.max(first, Math.min(last, time));
    }

    private static double percentAt(List<UsageSample> active, long time) {
        double percent = UsageStats.usedPercentAt(active, time);
        return percent < 0d ? active.get(active.size() - 1).usedPercent : percent;
    }

    private static double[] points(List<UsageSample> samples, long start, long end) {
        if (samples == null || samples.isEmpty()) {
            return new double[0];
        }
        double[] out = new double[samples.size() * 2];
        for (int i = 0; i < samples.size(); i++) {
            out[2 * i] = ratio(samples.get(i).observedAtMillis, start, end);
            out[2 * i + 1] = samples.get(i).usedPercent;
        }
        return out;
    }

    private static double ratio(long time, long start, long end) {
        return end <= start ? 0 : Math.max(0d, Math.min(1d, (time - start) / (double) (end - start)));
    }

    private Card insights(UsageWindow window, UsageSnapshot snapshot, UsageHistory history, PlanPricing pricing) {
        long now = System.currentTimeMillis();
        long observedAt = snapshot.fetchedAtMillis;
        Card card = new Card(Card.Kind.STATS);
        card.title = "Insights";
        long resetAt = window.effectiveResetAtMillis(observedAt);
        long duration = window.windowSeconds * 1000L;
        if (visible(HistorySections.INSIGHT_PACE) && resetAt > 0L && duration > 0L) {
            double elapsed = 1d - Math.max(0d, Math.min(1d, (resetAt - now) / (double) duration));
            double typical = UsageStats.typicalUsedPercentAt(history, elapsed);
            if (typical >= 0d) {
                long delta = Math.round(window.usedPercent - typical);
                card.stats.add(new String[] {"Vs. earlier windows",
                        delta >= 2L ? delta + " pts ahead"
                                : delta <= -2L ? (-delta) + " pts behind" : "On par"});
            }
        }
        if (visible(HistorySections.INSIGHT_EXHAUSTION)) {
            UsagePace.Assessment pace = UsagePacePreferences.assess(activity(), provider, snapshot, window, now);
            if (pace != null && pace.available) {
                card.stats.add(new String[] {"Runs out",
                        UsageFormat.relative(pace.estimatedExhaustionAtMillis, now)});
            }
        }
        if (visible(HistorySections.INSIGHT_AVERAGE)) {
            double average = UsageStats.averageFinalPercent(history);
            if (average >= 0d) {
                card.stats.add(new String[] {"Average window", Math.round(average) + "% used"});
            }
        }
        if (visible(HistorySections.INSIGHT_PEAK)) {
            double peak = UsageStats.peakBurnPercentPerHour(history);
            if (peak > 0d) {
                String value = formatRate(peak);
                if (pricing != null) {
                    value += " · ≈ " + PlanPricing.formatUsd(pricing.windowValueUsd(history.kind) * peak / 100d) + "/h";
                }
                card.stats.add(new String[] {"Fastest burn", value});
            }
        }
        if (pricing != null) {
            card.stats.add(new String[] {"Value used",
                    "≈ " + PlanPricing.formatUsd(pricing.estimatedValueUsd(history.kind, window.usedPercent))
                            + " of " + PlanPricing.formatUsd(pricing.windowValueUsd(history.kind))});
        }
        return card.stats.isEmpty() ? null : card;
    }

    private String rangeLabel(UsageStats.WindowStats stats, boolean days) {
        SimpleDateFormat day = new SimpleDateFormat("MMM d", Locale.getDefault());
        if (days) {
            return day.format(new Date(stats.windowStartMillis)) + " – " + day.format(new Date(stats.resetAtMillis));
        }
        SimpleDateFormat time = new SimpleDateFormat(DateFormat.is24HourFormat(activity()) ? "HH:mm" : "h:mm a",
                Locale.getDefault());
        return day.format(new Date(stats.windowStartMillis)) + " · " + time.format(new Date(stats.windowStartMillis))
                + " – " + time.format(new Date(stats.resetAtMillis));
    }

    private static String formatRate(double perHour) {
        return perHour >= 10d ? Math.round(perHour) + "%/h" : String.format(Locale.US, "%.1f%%/h", perHour);
    }

    @SuppressWarnings("unused")
    private static List<UsageSample> none() {
        return Collections.emptyList();
    }
}
