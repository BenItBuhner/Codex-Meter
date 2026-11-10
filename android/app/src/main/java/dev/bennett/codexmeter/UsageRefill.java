package dev.bennett.codexmeter;

/**
 * The rules behind the always-on "usage is back" alert, kept free of Android so they can be tested:
 * a limit that is used up (0% left) is remembered with the time it resets, and once it resets (the
 * reset time passes, or a refresh shows some left again) it is announced once.
 *
 * <p>The remembered value is the reset time in milliseconds, {@link #UNKNOWN_RESET} for a used-up
 * limit whose reset time is not known, or 0 when nothing is waiting.
 */
final class UsageRefill {
    private UsageRefill() {}

    /** A used-up limit whose provider did not say when it resets. */
    static final long UNKNOWN_RESET = -1L;
    /** How far an alarm's reset time may drift from the remembered one and still count. */
    static final long ALARM_TOLERANCE_MS = 60_000L;

    enum Action {
        /** Nothing changes. */
        NONE,
        /** Remember {@link Decision#resetAt} (a new used-up limit, or its reset time moved). */
        ARM,
        /** The used-up limit has some left again: announce it and forget it. */
        BACK
    }

    static final class Decision {
        final Action action;
        final long resetAt;
        /** BACK: percent left now. */
        final int left;

        Decision(Action action, long resetAt, int left) {
            this.action = action;
            this.resetAt = resetAt;
            this.left = left;
        }
    }

    static boolean usedUp(UsageWindow w) {
        return w != null && w.windowSeconds > 0L && w.remainingPercent() <= 0;
    }

    /**
     * What a fresh reading of one limit means for the alert.
     *
     * @param armed the remembered value for this limit (see the class comment)
     */
    static Decision decide(long armed, UsageWindow w, long observedAt, long now) {
        if (w == null || w.windowSeconds <= 0L) {
            return new Decision(Action.NONE, armed, -1);
        }
        if (usedUp(w)) {
            long resetAt = w.effectiveResetAtMillis(observedAt);
            long target;
            if (resetAt > now) {
                target = resetAt;
            } else if (resetAt <= 0L) {
                target = UNKNOWN_RESET;
            } else {
                // The reading is older than its own reset; the next refresh says what is true.
                return new Decision(Action.NONE, armed, -1);
            }
            if (target == armed || (target > 0L && armed > 0L
                    && Math.abs(target - armed) < ALARM_TOLERANCE_MS)) {
                return new Decision(Action.NONE, armed, -1);
            }
            return new Decision(Action.ARM, target, 0);
        }
        if (armed != 0L) {
            return new Decision(Action.BACK, 0L, w.remainingPercent());
        }
        return new Decision(Action.NONE, 0L, -1);
    }

    /** True when an alarm set for {@code alarmResetAt} is still the one the limit waits for. */
    static boolean alarmStillDue(long armed, long alarmResetAt) {
        return armed > 0L && alarmResetAt > 0L && Math.abs(armed - alarmResetAt) < ALARM_TOLERANCE_MS;
    }

    /** "Claude Code usage is back". */
    static String title(String provider) {
        return provider + " usage is back";
    }

    /**
     * The notification text. {@code left} is the percent left when a refresh saw it, or negative
     * when the reset time passed and the refresh has not come back yet.
     */
    static String text(String provider, String label, int left) {
        String limit = "Your " + lower(label) + " limit";
        if (left >= 0) {
            return limit + " refilled to " + left + "% left. You can use " + provider + " again.";
        }
        return limit + " has reset. You can use " + provider + " again.";
    }

    private static String lower(String label) {
        return label == null || label.isEmpty() ? "usage" : label.toLowerCase(java.util.Locale.ROOT);
    }
}
