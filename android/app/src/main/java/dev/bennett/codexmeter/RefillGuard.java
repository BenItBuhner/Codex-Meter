package dev.bennett.codexmeter;

/**
 * Guards against one-off false refills: a provider that answers once with a full (or no longer
 * used-up) limit well before its reset time, then goes back to the real numbers on the next
 * refresh. Antigravity does this now and then. Such a reading is held, not saved, and the provider
 * is asked again after {@link #CONFIRM_DELAY_MS}; only a reading that still shows the refill at
 * least {@link #MIN_CONFIRM_GAP_MS} after the first one is saved and can be announced.
 *
 * <p>The same rule runs for every provider. A refill at or after its reset time (within
 * {@link CelebrationDetector#ON_TIME_TOLERANCE_MS}) is the normal reset and is never held. Kept free
 * of Android so it can be tested; {@code RefillHold} keeps the state and schedules the re-check.
 */
final class RefillGuard {
    private RefillGuard() {}

    /** How long after a suspect reading the provider is asked again. */
    static final long CONFIRM_DELAY_MS = 45_000L;
    /** A second reading this long after the first that still shows the refill confirms it. */
    static final long MIN_CONFIRM_GAP_MS = 30_000L;
    /** A hold this old no longer confirms anything: the next suspect reading starts a new one. */
    static final long HOLD_EXPIRES_MS = 30L * 60L * 1000L;

    /**
     * True when {@code current} shows a limit refilled well before the reset time {@code previous}
     * gave: back to nothing used, or a used-up limit with some left again. A used-up limit with no
     * known reset time also counts, since "usage is back" would announce it.
     */
    static boolean suspect(UsageSnapshot previous, UsageSnapshot current) {
        if (previous == null || current == null) return false;
        long observedAt = current.fetchedAtMillis > 0L ? current.fetchedAtMillis : System.currentTimeMillis();
        if (suspect(previous, previous.fiveHour, current.fiveHour, observedAt)) return true;
        if (suspect(previous, previous.weekly, current.weekly, observedAt)) return true;
        if (suspect(previous, previous.monthly, current.monthly, observedAt)) return true;
        for (UsageLimit now : current.additionalLimits) {
            if (now == null || now.id.isEmpty() || now.primary == null) continue;
            for (UsageLimit before : previous.additionalLimits) {
                if (before == null || !now.id.equals(before.id)) continue;
                if (suspect(previous, before.primary, now.primary, observedAt)) return true;
            }
        }
        return false;
    }

    static boolean suspect(UsageSnapshot previous, UsageWindow before, UsageWindow after, long observedAt) {
        if (before == null || after == null) return false;
        boolean refilled = before.usedPercent > 0 && after.usedPercent == 0;
        boolean back = before.windowSeconds > 0L && before.remainingPercent() <= 0 && after.remainingPercent() > 0;
        if (!refilled && !back) return false;
        long expected = CelebrationDetector.expectedResetMillis(previous, before);
        if (expected <= 0L) return back;
        return CelebrationDetector.wellBeforeReset(expected, observedAt);
    }

    /**
     * Whether to hold {@code current} instead of saving it. {@code heldSince} is when the first
     * suspect reading of this run was held, or 0.
     */
    static boolean hold(UsageSnapshot previous, UsageSnapshot current, long heldSince, long now) {
        if (!suspect(previous, current)) return false;
        return !confirms(heldSince, now);
    }

    /** True when a hold that started at {@code heldSince} is old enough, and not too old, to confirm. */
    static boolean confirms(long heldSince, long now) {
        if (heldSince <= 0L || heldSince > now) return false;
        long age = now - heldSince;
        return age >= MIN_CONFIRM_GAP_MS && age <= HOLD_EXPIRES_MS;
    }
}
