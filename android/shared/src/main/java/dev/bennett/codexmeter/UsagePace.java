package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/**
 * Estimates quota exhaustion from the average consumption over the complete current window.
 *
 * <p>The elapsed sample begins at {@code resetAt - windowDuration}, so pauses and idle periods
 * remain part of the average instead of making a later burst look permanently representative.
 */
public final class UsagePace {
    public static final String OFF = "off";
    public static final String SENSITIVE = "sensitive";
    public static final String BALANCED = "balanced";
    public static final String RELAXED = "relaxed";

    public static final int WINDOW_NONE = 0;
    public static final int WINDOW_FIVE_HOUR = 1;
    public static final int WINDOW_WEEKLY = 2;

    private UsagePace() {
    }

    public static Assessment assess(UsageWindow window, long observedAtMillis, long nowMillis,
            String sensitivity) {
        if (window == null || window.windowSeconds <= 0L || observedAtMillis <= 0L) {
            return Assessment.unavailable();
        }
        long windowMillis = safeMultiply(window.windowSeconds, 1000L);
        long resetAtMillis = window.effectiveResetAtMillis(observedAtMillis);
        if (windowMillis <= 0L || resetAtMillis <= observedAtMillis
                || resetAtMillis <= nowMillis) {
            return Assessment.unavailable();
        }
        long windowStartMillis = resetAtMillis - windowMillis;
        long elapsedMillis = observedAtMillis - windowStartMillis;
        if (elapsedMillis <= 0L || elapsedMillis > windowMillis || window.usedPercent <= 0) {
            return Assessment.unavailable();
        }

        String normalized = normalizeSensitivity(sensitivity);
        Policy policy = policy(normalized, windowMillis);
        if (elapsedMillis < policy.minimumElapsedMillis
                || window.usedPercent < policy.minimumUsedPercent) {
            return Assessment.unavailable();
        }

        long estimatedRemainingAtObservation = safeMultiply(elapsedMillis,
                100L - window.usedPercent) / window.usedPercent;
        long estimatedTotalMillis = safeMultiply(elapsedMillis, 100L) / window.usedPercent;
        long estimatedExhaustionAtMillis = safeAdd(observedAtMillis,
                estimatedRemainingAtObservation);
        long actualRemainingAtObservation = resetAtMillis - observedAtMillis;
        boolean accelerated = warningsEnabled(normalized)
                && safeMultiply(estimatedRemainingAtObservation, 100L)
                <= safeMultiply(actualRemainingAtObservation, policy.maximumCoveragePercent);
        long estimatedRemainingNow = Math.max(0L, estimatedExhaustionAtMillis - nowMillis);
        long actualRemainingNow = Math.max(0L, resetAtMillis - nowMillis);
        return new Assessment(true, accelerated, estimatedRemainingNow, estimatedTotalMillis,
                actualRemainingNow, estimatedExhaustionAtMillis, resetAtMillis,
                elapsedMillis, window.usedPercent);
    }

    /**
     * Refines the full-window average with actual same-window samples when enough history exists.
     * The blend keeps estimates stable while reacting to a sustained recent change in burn.
     */
    public static Assessment assess(UsageWindow window, UsageHistory history,
            long observedAtMillis, long nowMillis, String sensitivity) {
        Assessment baseline = assess(window, observedAtMillis, nowMillis, sensitivity);
        if (!baseline.available || history == null) return baseline;
        double observedRate = history.observedBurnRate();
        if (observedRate <= 0d) return baseline;
        double averageRate = window.usedPercent / (double) baseline.observedDurationMillis;
        if (averageRate <= 0d) return baseline;
        // Bound malformed or unusually bursty samples before blending.
        observedRate = Math.max(averageRate * 0.25d, Math.min(averageRate * 4d, observedRate));
        double blendedRate = averageRate * 0.4d + observedRate * 0.6d;
        long remainingAtObservation = Math.max(0L,
                Math.round((100d - window.usedPercent) / blendedRate));
        long exhaustionAt = safeAdd(observedAtMillis, remainingAtObservation);
        long estimatedRemainingNow = Math.max(0L, exhaustionAt - nowMillis);
        long estimatedTotal = safeAdd(baseline.observedDurationMillis, remainingAtObservation);
        Policy policy = policy(normalizeSensitivity(sensitivity),
                safeMultiply(window.windowSeconds, 1000L));
        boolean accelerated = warningsEnabled(sensitivity)
                && safeMultiply(remainingAtObservation, 100L)
                <= safeMultiply(baseline.resetAtMillis - observedAtMillis,
                        policy.maximumCoveragePercent);
        return new Assessment(true, accelerated, estimatedRemainingNow, estimatedTotal,
                baseline.actualRemainingMillis, exhaustionAt, baseline.resetAtMillis,
                baseline.observedDurationMillis, window.usedPercent);
    }

    public static int mostAcceleratedWindow(UsageSnapshot snapshot, long nowMillis,
            String sensitivity) {
        if (snapshot == null || !warningsEnabled(sensitivity)) return WINDOW_NONE;
        Assessment fiveHour = assess(snapshot.fiveHour, snapshot.fetchedAtMillis, nowMillis,
                sensitivity);
        Assessment weekly = assess(snapshot.weekly, snapshot.fetchedAtMillis, nowMillis,
                sensitivity);
        if (!fiveHour.accelerated && !weekly.accelerated) return WINDOW_NONE;
        if (!fiveHour.accelerated) return WINDOW_WEEKLY;
        if (!weekly.accelerated) return WINDOW_FIVE_HOUR;
        return fiveHour.coverageRatio() <= weekly.coverageRatio()
                ? WINDOW_FIVE_HOUR : WINDOW_WEEKLY;
    }

    public static String normalizeSensitivity(String sensitivity) {
        if (OFF.equals(sensitivity) || SENSITIVE.equals(sensitivity)
                || RELAXED.equals(sensitivity)) {
            return sensitivity;
        }
        return BALANCED;
    }

    /** Whether accelerated-usage warnings (and their Now Bar triggers) may fire. */
    public static boolean warningsEnabled(String sensitivity) {
        return !OFF.equals(normalizeSensitivity(sensitivity));
    }

    private static Policy policy(String sensitivity, long windowMillis) {
        switch (normalizeSensitivity(sensitivity)) {
            case SENSITIVE:
                return new Policy(2, Math.max(TimeUnit.MINUTES.toMillis(2),
                        windowMillis / 400L), 100);
            case RELAXED:
                return new Policy(10, Math.max(TimeUnit.MINUTES.toMillis(10),
                        windowMillis / 100L), 50);
            case OFF:
                // Keep balanced sample gates so estimates still appear without warnings.
            default:
                return new Policy(5, Math.max(TimeUnit.MINUTES.toMillis(5),
                        windowMillis / 200L), 75);
        }
    }

    private static long safeMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class Policy {
        final int minimumUsedPercent;
        final long minimumElapsedMillis;
        final int maximumCoveragePercent;

        Policy(int minimumUsedPercent, long minimumElapsedMillis,
                int maximumCoveragePercent) {
            this.minimumUsedPercent = minimumUsedPercent;
            this.minimumElapsedMillis = minimumElapsedMillis;
            this.maximumCoveragePercent = maximumCoveragePercent;
        }
    }

    public static final class Assessment {
        public final boolean available;
        public final boolean accelerated;
        public final long estimatedRemainingMillis;
        public final long estimatedTotalMillis;
        public final long actualRemainingMillis;
        public final long estimatedExhaustionAtMillis;
        public final long resetAtMillis;
        public final long observedDurationMillis;
        public final int usedPercent;

        private Assessment(boolean available, boolean accelerated,
                long estimatedRemainingMillis, long estimatedTotalMillis,
                long actualRemainingMillis, long estimatedExhaustionAtMillis,
                long resetAtMillis, long observedDurationMillis, int usedPercent) {
            this.available = available;
            this.accelerated = accelerated;
            this.estimatedRemainingMillis = estimatedRemainingMillis;
            this.estimatedTotalMillis = estimatedTotalMillis;
            this.actualRemainingMillis = actualRemainingMillis;
            this.estimatedExhaustionAtMillis = estimatedExhaustionAtMillis;
            this.resetAtMillis = resetAtMillis;
            this.observedDurationMillis = observedDurationMillis;
            this.usedPercent = usedPercent;
        }

        private static Assessment unavailable() {
            return new Assessment(false, false, 0L, 0L, 0L, 0L, 0L, 0L, 0);
        }

        double coverageRatio() {
            if (!available || actualRemainingMillis <= 0L) return Double.POSITIVE_INFINITY;
            return (double) estimatedRemainingMillis / (double) actualRemainingMillis;
        }
    }
}
