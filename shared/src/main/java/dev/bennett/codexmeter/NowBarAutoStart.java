package dev.bennett.codexmeter;

/**
 * Pure helpers for deciding when the live usage monitor should auto-start.
 * Mirrors the low-usage notification metric and remaining-percentage threshold rules.
 */
public final class NowBarAutoStart {
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";

    private NowBarAutoStart() {
    }

    public static String normalizeMetric(String metric) {
        if (METRIC_FIVE_HOUR.equals(metric) || METRIC_WEEKLY.equals(metric)) {
            return metric;
        }
        return METRIC_BOTH;
    }

    public static boolean isValidThreshold(int threshold) {
        return threshold == 10 || threshold == 25 || threshold == 50
                || threshold == 75 || threshold == 100;
    }

    public static int normalizeThreshold(int threshold) {
        return isValidThreshold(threshold) ? threshold : 25;
    }

    /**
     * Returns true when auto-start is enabled and any watched usage window's remaining
     * percentage is at or below the configured threshold.
     */
    public static boolean shouldStart(boolean enabled, String metric, int threshold,
            UsageWindow fiveHour, UsageWindow weekly) {
        if (!enabled || !isValidThreshold(threshold)) return false;
        String normalized = normalizeMetric(metric);
        if (!METRIC_WEEKLY.equals(normalized)
                && fiveHour != null
                && fiveHour.remainingPercent() <= threshold) {
            return true;
        }
        if (!METRIC_FIVE_HOUR.equals(normalized)
                && weekly != null
                && weekly.remainingPercent() <= threshold) {
            return true;
        }
        return false;
    }
}
