package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/**
 * Pure decision: use one banked reset credit when a usage window's remaining allowance drops to
 * the chosen threshold. Fires at most once per usage window, never from a stale snapshot, and
 * never when that window refills on its own within the wait the user chose for it (15 minutes
 * unless changed; the 5-hour and weekly limits each have their own).
 */
public final class ResetCreditLowUsageAutoUse {
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";
    public static final int[] THRESHOLD_OPTIONS = {0, 1, 2, 5, 10};
    public static final int DEFAULT_THRESHOLD = 1;
    /** The default wait: a window that refills on its own this soon is not worth a credit. */
    public static final int DEFAULT_WAIT_MINUTES = 15;
    /** Waits offered for the 5-hour limit, in minutes. 0 means never wait. */
    public static final int[] FIVE_HOUR_WAIT_OPTIONS = {0, 5, 15, 30, 60, 120, 180, 240};
    /** Waits offered for the weekly (or monthly) limit, in minutes, up to 3 days. */
    public static final int[] WEEKLY_WAIT_OPTIONS =
            {0, 15, 30, 60, 180, 360, 720, 1440, 2880, 4320};
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
    /** When the picked window refills on its own; 0 while waiting or when unknown. */
    public final long resetAtMillis;

    private ResetCreditLowUsageAutoUse(String outcome, String windowLabel, int remainingPercent,
            String windowToken, long resetAtMillis) {
        this.outcome = outcome;
        this.windowLabel = windowLabel == null ? "" : windowLabel;
        this.remainingPercent = remainingPercent;
        this.windowToken = windowToken == null ? "" : windowToken;
        this.resetAtMillis = Math.max(0L, resetAtMillis);
    }

    public boolean fires() {
        return FIRE.equals(outcome);
    }

    public static String normalizeMetric(String metric) {
        if (METRIC_FIVE_HOUR.equals(metric) || METRIC_WEEKLY.equals(metric)) return metric;
        return METRIC_BOTH;
    }

    /** A stored 5-hour wait, or the default when it isn't one of the offered values. */
    public static int normalizeFiveHourWait(int minutes) {
        return normalizeWait(minutes, FIVE_HOUR_WAIT_OPTIONS);
    }

    /** A stored weekly wait, or the default when it isn't one of the offered values. */
    public static int normalizeWeeklyWait(int minutes) {
        return normalizeWait(minutes, WEEKLY_WAIT_OPTIONS);
    }

    private static int normalizeWait(int minutes, int[] options) {
        for (int option : options) {
            if (option == minutes) return minutes;
        }
        return DEFAULT_WAIT_MINUTES;
    }

    /** "Don't wait", "5 minutes", "1 hour", "12 hours", "1 day", "3 days". */
    public static String waitLabel(int minutes) {
        if (minutes <= 0) return "Don't wait";
        if (minutes < 60) return minutes == 1 ? "1 minute" : minutes + " minutes";
        if (minutes < 1440 || minutes % 1440 != 0) {
            int hours = minutes / 60;
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        int days = minutes / 1440;
        return days == 1 ? "1 day" : days + " days";
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
        return evaluate(snapshot, metric, threshold, availableCredits, lastFiredToken,
                DEFAULT_WAIT_MINUTES, DEFAULT_WAIT_MINUTES, nowMillis);
    }

    /**
     * @param fiveHourWaitMinutes skip the 5-hour limit when it refills on its own within this
     *     many minutes (inclusive); 0 never skips
     * @param weeklyWaitMinutes the same for the weekly or monthly limit
     */
    public static ResetCreditLowUsageAutoUse evaluate(UsageSnapshot snapshot, String metric,
            int threshold, int availableCredits, String lastFiredToken, int fiveHourWaitMinutes,
            int weeklyWaitMinutes, long nowMillis) {
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
                    window.remainingPercent(), token, resetAt);
        }
        if (availableCredits == 0) {
            return new ResetCreditLowUsageAutoUse(SKIP_NO_CREDIT, labels[pick],
                    window.remainingPercent(), token, resetAt);
        }
        int waitMinutes = pick == 0 ? normalizeFiveHourWait(fiveHourWaitMinutes)
                : normalizeWeeklyWait(weeklyWaitMinutes);
        if (refillsWithin(resetAt, waitMinutes, nowMillis)) {
            return new ResetCreditLowUsageAutoUse(SKIP_RESET_IMMINENT, labels[pick],
                    window.remainingPercent(), token, resetAt);
        }
        return new ResetCreditLowUsageAutoUse(FIRE, labels[pick], window.remainingPercent(),
                token, resetAt);
    }

    /** True when {@code resetAt} is still ahead and no more than the wait away. */
    public static boolean refillsWithin(long resetAtMillis, int waitMinutes, long nowMillis) {
        return waitMinutes > 0 && resetAtMillis > nowMillis
                && resetAtMillis - nowMillis <= TimeUnit.MINUTES.toMillis(waitMinutes);
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
        return new ResetCreditLowUsageAutoUse(WAIT, "", -1, "", 0L);
    }
}
