package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pure aggregation over locally recorded usage samples: per-window statistics,
 * cross-window typical pace, and time interpolation for chart scrubbing.
 */
public final class UsageStats {
    /** Consecutive samples closer than this are too jittery for a burn-rate reading. */
    private static final long MINIMUM_RATE_SPACING_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private UsageStats() {
    }

    /** Statistics for one reset window's recorded samples. */
    public static final class WindowStats {
        public final long windowStartMillis;
        public final long resetAtMillis;
        public final long firstSampleMillis;
        public final long lastSampleMillis;
        public final int firstPercent;
        public final int finalPercent;
        public final int sampleCount;
        /** Average burn in percent per hour across the observed sample span. */
        public final double averageBurnPercentPerHour;
        /** Steepest observed burn in percent per hour between adjacent samples. */
        public final double peakBurnPercentPerHour;
        public final boolean exhausted;
        public final boolean complete;

        private WindowStats(long windowStartMillis, long resetAtMillis, long firstSampleMillis,
                long lastSampleMillis, int firstPercent, int finalPercent, int sampleCount,
                double averageBurnPercentPerHour, double peakBurnPercentPerHour,
                boolean exhausted, boolean complete) {
            this.windowStartMillis = windowStartMillis;
            this.resetAtMillis = resetAtMillis;
            this.firstSampleMillis = firstSampleMillis;
            this.lastSampleMillis = lastSampleMillis;
            this.firstPercent = firstPercent;
            this.finalPercent = finalPercent;
            this.sampleCount = sampleCount;
            this.averageBurnPercentPerHour = averageBurnPercentPerHour;
            this.peakBurnPercentPerHour = peakBurnPercentPerHour;
            this.exhausted = exhausted;
            this.complete = complete;
        }
    }

    /** Builds statistics for one window's samples, or null when samples are unusable. */
    public static WindowStats windowStats(List<UsageSample> samples, boolean complete) {
        if (samples == null || samples.isEmpty()) return null;
        UsageSample first = samples.get(0);
        UsageSample last = samples.get(samples.size() - 1);
        long windowStart = last.resetAtMillis - last.windowSeconds * 1000L;
        double averageRate = 0d;
        long span = last.observedAtMillis - first.observedAtMillis;
        int burned = last.usedPercent - first.usedPercent;
        if (span > 0L && burned > 0) {
            averageRate = burned / hours(span);
        }
        double peakRate = 0d;
        for (int i = 1; i < samples.size(); i++) {
            UsageSample previous = samples.get(i - 1);
            UsageSample current = samples.get(i);
            long gap = current.observedAtMillis - previous.observedAtMillis;
            int delta = current.usedPercent - previous.usedPercent;
            if (gap < MINIMUM_RATE_SPACING_MILLIS || delta <= 0) continue;
            peakRate = Math.max(peakRate, delta / hours(gap));
        }
        return new WindowStats(windowStart, last.resetAtMillis, first.observedAtMillis,
                last.observedAtMillis, first.usedPercent, last.usedPercent, samples.size(),
                averageRate, peakRate, last.usedPercent >= 100, complete);
    }

    /** Per-window statistics, oldest to newest; the final entry is the current window. */
    public static List<WindowStats> windowBreakdown(UsageHistory history, int maximumWindows) {
        if (history == null) return Collections.emptyList();
        List<List<UsageSample>> windows = history.recentWindows(maximumWindows);
        ArrayList<WindowStats> breakdown = new ArrayList<>();
        for (int index = 0; index < windows.size(); index++) {
            WindowStats stats = windowStats(windows.get(index), index < windows.size() - 1);
            if (stats != null) breakdown.add(stats);
        }
        return Collections.unmodifiableList(breakdown);
    }

    /**
     * Linearly interpolated used percent at a moment in time, or -1 when the moment
     * falls outside the observed sample span.
     */
    public static double usedPercentAt(List<UsageSample> samples, long timeMillis) {
        if (samples == null || samples.isEmpty()) return -1d;
        UsageSample first = samples.get(0);
        UsageSample last = samples.get(samples.size() - 1);
        if (timeMillis < first.observedAtMillis || timeMillis > last.observedAtMillis) return -1d;
        for (int i = 1; i < samples.size(); i++) {
            UsageSample left = samples.get(i - 1);
            UsageSample right = samples.get(i);
            if (timeMillis > right.observedAtMillis) continue;
            long span = right.observedAtMillis - left.observedAtMillis;
            if (span <= 0L) return right.usedPercent;
            double ratio = (timeMillis - left.observedAtMillis) / (double) span;
            return left.usedPercent + (right.usedPercent - left.usedPercent) * ratio;
        }
        return last.usedPercent;
    }

    /**
     * Typical used percent at an elapsed fraction of the window (0..1), averaged across
     * completed windows. Returns -1 when no completed window covers that fraction.
     */
    public static double typicalUsedPercentAt(UsageHistory history, double elapsedFraction) {
        if (history == null) return -1d;
        double fraction = Math.max(0d, Math.min(1d, elapsedFraction));
        List<List<UsageSample>> windows = history.recentWindows(Integer.MAX_VALUE);
        double total = 0d;
        int counted = 0;
        for (int index = 0; index < windows.size() - 1; index++) {
            List<UsageSample> window = windows.get(index);
            if (window.size() < 2) continue;
            UsageSample reference = window.get(window.size() - 1);
            long windowStart = reference.resetAtMillis - reference.windowSeconds * 1000L;
            long moment = windowStart
                    + Math.round(fraction * reference.windowSeconds * 1000d);
            double value = usedPercentAt(window, moment);
            if (value < 0d) {
                // Before the first sample usage is effectively the first reading; after the
                // last sample the window ended at its final reading.
                UsageSample first = window.get(0);
                value = moment < first.observedAtMillis ? first.usedPercent
                        : reference.usedPercent;
            }
            total += value;
            counted++;
        }
        return counted == 0 ? -1d : total / counted;
    }

    /** Average final used percent across completed windows, or -1 when none exist. */
    public static double averageFinalPercent(UsageHistory history) {
        if (history == null) return -1d;
        List<List<UsageSample>> windows = history.recentWindows(Integer.MAX_VALUE);
        double total = 0d;
        int counted = 0;
        for (int index = 0; index < windows.size() - 1; index++) {
            List<UsageSample> window = windows.get(index);
            if (window.isEmpty()) continue;
            total += window.get(window.size() - 1).usedPercent;
            counted++;
        }
        return counted == 0 ? -1d : total / counted;
    }

    /** Steepest burn observed across every recorded window, or 0 when unavailable. */
    public static double peakBurnPercentPerHour(UsageHistory history) {
        double peak = 0d;
        for (WindowStats stats : windowBreakdown(history, Integer.MAX_VALUE)) {
            peak = Math.max(peak, stats.peakBurnPercentPerHour);
        }
        return peak;
    }

    private static double hours(long millis) {
        return millis / (double) TimeUnit.HOURS.toMillis(1);
    }
}
