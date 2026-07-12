package dev.bennett.codexmeter;

/** Classifies full-allowance jumps that are not explained by a local reset or countdown. */
public final class ExternalResetDetector {
    public static final int NONE = 0;
    public static final int FIVE_HOUR = 1;
    public static final int WEEKLY = 1 << 1;
    static final long NATURAL_RESET_TOLERANCE_MS = 60_000L;

    private ExternalResetDetector() {
    }

    public static boolean isUnexpectedReset(int previousUsedPercent, long expectedResetAtMillis,
            UsageWindow current, long observedAtMillis, boolean manualResetPending) {
        if (manualResetPending || current == null || previousUsedPercent <= 0
                || current.usedPercent != 0) {
            return false;
        }
        return expectedResetAtMillis <= 0L
                || observedAtMillis < expectedResetAtMillis - NATURAL_RESET_TOLERANCE_MS;
    }

    public static long expectedResetAt(UsageWindow window, long fetchedAtMillis) {
        if (window == null) return 0L;
        long resetAt = window.resetAtMillis();
        if (resetAt > 0L) return resetAt;
        return window.resetAfterSeconds > 0L
                ? fetchedAtMillis + window.resetAfterSeconds * 1000L
                : 0L;
    }
}
