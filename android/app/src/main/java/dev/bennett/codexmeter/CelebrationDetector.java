package dev.bennett.codexmeter;

/** Pure decision logic for unexpected allowance refills and reset-credit increases. */
public final class CelebrationDetector {
    public static final int FIVE_HOUR = 1;
    public static final int WEEKLY = 1 << 1;
    public static final int MONTHLY = 1 << 2;
    /**
     * A limit that refills this close to its reset time, or later, is its normal reset arriving a
     * little early, not a surprise. Providers often reset a minute or two ahead of the time they
     * gave.
     */
    public static final long ON_TIME_TOLERANCE_MS = 5L * 60L * 1000L;

    private CelebrationDetector() {
    }

    /**
     * Returns a bit mask for windows that became completely available before their previously
     * advertised reset deadline, by more than {@link #ON_TIME_TOLERANCE_MS}. A missing deadline is deliberately ignored because a natural
     * reset cannot be distinguished from an external refill in that case.
     */
    public static int detectUnexpectedRefills(UsageSnapshot previous, UsageSnapshot current) {
        if (previous == null || current == null || current.fetchedAtMillis <= 0L) return 0;
        int refills = 0;
        if (isUnexpectedRefill(previous, previous.fiveHour, current.fiveHour,
                current.fetchedAtMillis)) {
            refills |= FIVE_HOUR;
        }
        if (isUnexpectedRefill(previous, previous.weekly, current.weekly,
                current.fetchedAtMillis)) {
            refills |= WEEKLY;
        }
        if (isUnexpectedRefill(previous, previous.monthly, current.monthly,
                current.fetchedAtMillis)) {
            refills |= MONTHLY;
        }
        return refills;
    }

    public static int resetCreditsAdded(int previous, int current) {
        if (previous < 0 || current <= previous) return 0;
        return current - previous;
    }

    public static int withoutUserResetRefills(int refills, long observedAtMillis,
            long fiveHourSuppressUntil, long weeklySuppressUntil) {
        return withoutUserResetRefills(refills, observedAtMillis, fiveHourSuppressUntil,
                weeklySuppressUntil, 0L);
    }

    public static int withoutUserResetRefills(int refills, long observedAtMillis,
            long fiveHourSuppressUntil, long weeklySuppressUntil, long monthlySuppressUntil) {
        if (fiveHourSuppressUntil > observedAtMillis) {
            refills &= ~FIVE_HOUR;
        }
        if (weeklySuppressUntil > observedAtMillis) {
            refills &= ~WEEKLY;
        }
        if (monthlySuppressUntil > observedAtMillis) {
            refills &= ~MONTHLY;
        }
        return refills;
    }

    static long expectedResetMillis(UsageSnapshot snapshot, UsageWindow window) {
        if (snapshot == null || window == null) return 0L;
        long resetAt = window.resetAtMillis();
        if (resetAt > 0L) return resetAt;
        if (snapshot.fetchedAtMillis <= 0L || window.resetAfterSeconds <= 0L) return 0L;
        if (window.resetAfterSeconds > (Long.MAX_VALUE - snapshot.fetchedAtMillis) / 1000L) {
            return Long.MAX_VALUE;
        }
        return snapshot.fetchedAtMillis + window.resetAfterSeconds * 1000L;
    }

    private static boolean isUnexpectedRefill(UsageSnapshot previous, UsageWindow oldWindow,
            UsageWindow newWindow, long observedAtMillis) {
        if (oldWindow == null || newWindow == null
                || oldWindow.usedPercent <= 0 || newWindow.usedPercent != 0) {
            return false;
        }
        return wellBeforeReset(expectedResetMillis(previous, oldWindow), observedAtMillis);
    }

    /**
     * True when {@code observedAtMillis} is more than {@link #ON_TIME_TOLERANCE_MS} before a known
     * reset time ({@code expectedReset} above 0).
     */
    static boolean wellBeforeReset(long expectedReset, long observedAtMillis) {
        return expectedReset > 0L && expectedReset - ON_TIME_TOLERANCE_MS > observedAtMillis;
    }
}
