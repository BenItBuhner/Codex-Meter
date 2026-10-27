package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/**
 * Pure decision: use one banked reset credit when a usage window's remaining allowance drops to
 * the chosen threshold. Fires at most once per usage window, never from a stale snapshot, and
 * never when that window is about to refill on its own anyway.
 */
public final class ResetCreditLowUsageAutoUse {
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";
    public static final int[] THRESHOLD_OPTIONS = {0, 1, 2, 5, 10};
    public static final int DEFAULT_THRESHOLD = 1;
    /** A window that refills on its own within this time is not worth a credit. */
    public static final long IMMINENT_RESET_MS = TimeUnit.MINUTES.toMillis(15);
    /** Only a snapshot this fresh may spend a credit. */
    public static final long MAX_SNAPSHOT_AGE_MS = TimeUnit.MINUTES.toMillis(2);

    public static final String FIRE = "fire";
    public static final String WAIT = "wait";
    public static final String SKIP_NO_CREDIT = "skip_no_credit";
    public static final String SKIP_RESET_IMMINENT = "skip_reset_imminent";
    public static final String SKIP_ALREADY_USED = "skip_already_used";

    public final String outcome;
    /** "5-hour", "weekly", or "monthly"; empty while waiting. */
    public final String windowLabel;
    public final int remainingPercent;
    /** Identifies the usage window this decision is about, so it fires only once per window. */
    public final String windowToken;

    private ResetCreditLowUsageAutoUse(String outcome, String windowLabel, int remainingPercent,
            String windowToken) {
        this.outcome = outcome;
        this.windowLabel = windowLabel == null ? "" : windowLabel;
        this.remainingPercent = remainingPercent;
        this.windowToken = windowToken == null ? "" : windowToken;
    }

    public boolean fires() {
        return FIRE.equals(outcome);
    }

    public static String normalizeMetric(String metric) {
        if (METRIC_FIVE_HOUR.equals(metric) || METRIC_WEEKLY.equals(metric)) return metric;
        return METRIC_BOTH;
    }

    public static int normalizeThreshold(int threshold) {
        for (int option : THRESHOLD_OPTIONS) {
            if (option == threshold) return threshold;
        }
        return DEFAULT_THRESHOLD;
    }

    /**
     * @param availableCredits banked credits available now; below zero means unknown and the
     *     consume call reports the shortage itself
     * @param lastFiredToken the {@link #windowToken} of the last window this rule already used a
     *     credit on (empty when none)
     */
    public static ResetCreditLowUsageAutoUse evaluate(UsageSnapshot snapshot, String metric,
            int threshold, int availableCredits, String lastFiredToken, long nowMillis) {
        if (snapshot == null || snapshot.fetchedAtMillis <= 0L
                || nowMillis - snapshot.fetchedAtMillis > MAX_SNAPSHOT_AGE_MS) {
            return waiting();
        }
        String watched = normalizeMetric(metric);
        int limit = normalizeThreshold(threshold);
        long observedAt = snapshot.fetchedAtMillis;
        UsageWindow[] windows = new UsageWindow[2];
        String[] labels = new String[2];
        if (!METRIC_WEEKLY.equals(watched)) {
            windows[0] = UsageSnapshot.currentWindow(snapshot.fiveHour, observedAt, nowMillis);
            labels[0] = "5-hour";
        }
        if (!METRIC_FIVE_HOUR.equals(watched)) {
            windows[1] = UsageSnapshot.currentWindow(snapshot.longWindow(), observedAt, nowMillis);
            labels[1] = snapshot.longWindowIsMonthly() ? "monthly" : "weekly";
        }
        // Pick the lowest window at or under the threshold.
        int pick = -1;
        for (int index = 0; index < windows.length; index++) {
            UsageWindow window = windows[index];
            if (window == null || window.remainingPercent() > limit) continue;
            if (pick < 0 || window.remainingPercent() < windows[pick].remainingPercent()) {
                pick = index;
            }
        }
        if (pick < 0) return waiting();
        UsageWindow window = windows[pick];
        long resetAt = window.effectiveResetAtMillis(observedAt);
        String token = labels[pick] + ":" + resetAt;
        if (sameWindow(lastFiredToken, labels[pick], resetAt, window.windowSeconds)) {
            return new ResetCreditLowUsageAutoUse(SKIP_ALREADY_USED, labels[pick],
                    window.remainingPercent(), token);
        }
        if (availableCredits == 0) {
            return new ResetCreditLowUsageAutoUse(SKIP_NO_CREDIT, labels[pick],
                    window.remainingPercent(), token);
        }
        if (resetAt > nowMillis && resetAt - nowMillis <= IMMINENT_RESET_MS) {
            return new ResetCreditLowUsageAutoUse(SKIP_RESET_IMMINENT, labels[pick],
                    window.remainingPercent(), token);
        }
        return new ResetCreditLowUsageAutoUse(FIRE, labels[pick], window.remainingPercent(),
                token);
    }

    private static boolean sameWindow(String lastFiredToken, String label, long resetAt,
            long windowSeconds) {
        if (lastFiredToken == null || lastFiredToken.isEmpty()) return false;
        int split = lastFiredToken.lastIndexOf(':');
        if (split <= 0) return false;
        if (!label.equals(lastFiredToken.substring(0, split))) return false;
        long lastReset;
        try {
            lastReset = Long.parseLong(lastFiredToken.substring(split + 1));
        } catch (NumberFormatException exception) {
            return false;
        }
        if (resetAt <= 0L || lastReset <= 0L) return resetAt == lastReset;
        return UsageWindow.sameResetWindow(lastReset, windowSeconds, resetAt, windowSeconds);
    }

    private static ResetCreditLowUsageAutoUse waiting() {
        return new ResetCreditLowUsageAutoUse(WAIT, "", -1, "");
    }
}
