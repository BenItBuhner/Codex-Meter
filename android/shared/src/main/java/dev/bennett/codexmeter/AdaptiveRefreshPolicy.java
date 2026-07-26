package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/** Pure, low-cost policy for selecting the next automatic usage refresh. */
public final class AdaptiveRefreshPolicy {
    private static final int[] INTERVALS = {5, 10, 15, 30, 60, 120};

    private AdaptiveRefreshPolicy() {
    }

    public static int chooseMinutes(UsageSnapshot snapshot, double attentionScore,
            int localHour, int consecutiveFailures, long nowMillis) {
        UsageWindow fiveHour = snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.fiveHour, snapshot.fetchedAtMillis,
                        nowMillis);
        UsageWindow weekly = snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.weekly, snapshot.fetchedAtMillis,
                        nowMillis);
        int remaining = minimumRemaining(fiveHour, weekly);
        boolean limited = snapshot != null && (!snapshot.allowed || snapshot.limitReached);

        int minutes;
        if (limited || remaining <= 10) {
            minutes = 5;
        } else if (remaining <= 25) {
            minutes = 10;
        } else if (remaining <= 50) {
            minutes = 15;
        } else if (remaining <= 75) {
            minutes = 30;
        } else if (remaining <= 100) {
            minutes = 60;
        } else {
            minutes = 30;
        }

        long nextUsedReset = nextUsedReset(fiveHour, weekly,
                snapshot == null ? 0L : snapshot.fetchedAtMillis, nowMillis);
        long untilReset = nextUsedReset <= nowMillis ? Long.MAX_VALUE : nextUsedReset - nowMillis;
        if (untilReset <= TimeUnit.MINUTES.toMillis(15)) {
            minutes = Math.min(minutes, 5);
        } else if (untilReset <= TimeUnit.HOURS.toMillis(1)) {
            minutes = Math.min(minutes, 10);
        }

        if (snapshot != null
                && UsagePace.mostAcceleratedWindow(snapshot, nowMillis, UsagePace.BALANCED)
                != UsagePace.WINDOW_NONE) {
            minutes = Math.min(minutes, 10);
        }

        double attention = Math.max(0.0d, attentionScore);
        if (attention >= 6.0d) {
            minutes = Math.min(minutes, 5);
        } else if (attention >= 3.0d) {
            minutes = Math.min(minutes, 10);
        } else if (attention >= 1.5d) {
            minutes = Math.min(minutes, 15);
        } else if (attention >= 0.5d) {
            minutes = Math.min(minutes, 30);
        }

        boolean urgent = limited || remaining <= 25
                || untilReset <= TimeUnit.HOURS.toMillis(1);
        int hour = Math.max(0, Math.min(23, localHour));
        if (!urgent && attention < 0.5d && hour >= 1 && hour < 6) {
            minutes = Math.max(minutes, 120);
        }

        int failures = Math.max(0, Math.min(3, consecutiveFailures));
        for (int i = 0; i < failures; i++) {
            minutes = nextSlower(minutes);
        }
        return urgent ? Math.min(minutes, 30) : Math.min(minutes, 120);
    }

    private static int minimumRemaining(UsageWindow first, UsageWindow second) {
        int remaining = 101;
        if (first != null) remaining = Math.min(remaining, first.remainingPercent());
        if (second != null) remaining = Math.min(remaining, second.remainingPercent());
        return remaining;
    }

    private static long nextUsedReset(UsageWindow first, UsageWindow second,
            long observedAtMillis, long nowMillis) {
        long next = Long.MAX_VALUE;
        if (first != null && first.usedPercent > 0) {
            long reset = first.effectiveResetAtMillis(observedAtMillis);
            if (reset > nowMillis) next = Math.min(next, reset);
        }
        if (second != null && second.usedPercent > 0) {
            long reset = second.effectiveResetAtMillis(observedAtMillis);
            if (reset > nowMillis) next = Math.min(next, reset);
        }
        return next == Long.MAX_VALUE ? 0L : next;
    }

    private static int nextSlower(int minutes) {
        for (int interval : INTERVALS) {
            if (interval > minutes) return interval;
        }
        return INTERVALS[INTERVALS.length - 1];
    }
}
