package dev.bennett.codexmeter;

import java.util.Collections;
import java.util.List;

/**
 * Every provider: a refill a few minutes ahead of its reset time is the normal reset, and a reading
 * that shows an early refill is held until a second reading confirms it.
 */
public final class RefillGuardSelfTest {
    private static final long NOW = 1_794_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final List<UsageLimit> NONE = Collections.<UsageLimit>emptyList();

    public static void main(String[] args) {
        resetAMinuteOrTwoEarlyIsNotASurprise();
        resetWellBeforeItsTimeIsStillASurprise();
        modelLimitsUseTheSameTolerance();
        earlyRefillIsHeldUntilConfirmed();
        flukeThatRevertsIsDropped();
        usedUpLimitComingBackEarlyIsHeld();
        onTimeAndOrdinaryReadingsAreNeverHeld();
        System.out.println("Refill guard: on-time tolerance and the confirm-before-saving hold verified.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static UsageWindow fiveHour(int used, long resetAt) {
        return new UsageWindow(used, 5 * 3600L, 0L, resetAt / 1000L);
    }

    private static UsageWindow weekly(int used, long resetAt) {
        return new UsageWindow(used, 7 * 86400L, 0L, resetAt / 1000L);
    }

    private static UsageSnapshot snapshot(UsageWindow fiveHour, UsageWindow weekly, List<UsageLimit> extra, long at) {
        return new UsageSnapshot("", true, false, fiveHour, weekly, extra, null, -1, at);
    }

    private static void resetAMinuteOrTwoEarlyIsNotASurprise() {
        long due = NOW + 2 * MINUTE;
        UsageSnapshot before = snapshot(fiveHour(70, due), weekly(40, NOW + 3 * 24 * HOUR), NONE, NOW - 10 * MINUTE);
        UsageSnapshot after = snapshot(fiveHour(0, NOW + 5 * HOUR), weekly(40, NOW + 3 * 24 * HOUR), NONE, NOW);
        check(CelebrationDetector.detectUnexpectedRefills(before, after) == 0,
                "a 5-hour reset 2 minutes early is the scheduled one");
        check(ProviderEvents.refilledEarly(before, after, "5-hour").isEmpty(), "and no Priority event");
        check(!RefillGuard.suspect(before, after), "and it is not held");

        UsageSnapshot weeklyBefore = snapshot(null, weekly(90, NOW + 4 * MINUTE), NONE, NOW - HOUR);
        UsageSnapshot weeklyAfter = snapshot(null, weekly(0, NOW + 7 * 24 * HOUR), NONE, NOW);
        check(CelebrationDetector.detectUnexpectedRefills(weeklyBefore, weeklyAfter) == 0,
                "a weekly reset 4 minutes early is the scheduled one");
    }

    private static void resetWellBeforeItsTimeIsStillASurprise() {
        UsageSnapshot before = snapshot(fiveHour(70, NOW + 2 * HOUR), null, NONE, NOW - 10 * MINUTE);
        UsageSnapshot after = snapshot(fiveHour(0, NOW + 5 * HOUR), null, NONE, NOW);
        check(CelebrationDetector.detectUnexpectedRefills(before, after) == CelebrationDetector.FIVE_HOUR,
                "two hours early is a surprise");
        UsageSnapshot edge = snapshot(fiveHour(70, NOW + 6 * MINUTE), null, NONE, NOW - 10 * MINUTE);
        check(CelebrationDetector.detectUnexpectedRefills(edge, after) == CelebrationDetector.FIVE_HOUR,
                "six minutes early is past the tolerance");
    }

    private static void modelLimitsUseTheSameTolerance() {
        UsageLimit soon = new UsageLimit("m", "Fable only", "", true, false, weekly(60, NOW + MINUTE), null);
        UsageLimit later = new UsageLimit("m", "Fable only", "", true, false, weekly(60, NOW + 2 * 24 * HOUR), null);
        UsageLimit refilled = new UsageLimit("m", "Fable only", "", true, false, weekly(0, NOW + 7 * 24 * HOUR), null);
        UsageSnapshot after = snapshot(null, null, Collections.singletonList(refilled), NOW);
        check(ProviderEvents.refilledEarly(snapshot(null, null, Collections.singletonList(soon), NOW - HOUR),
                after, "").isEmpty(), "a model limit a minute early is on time");
        check(ProviderEvents.refilledEarly(snapshot(null, null, Collections.singletonList(later), NOW - HOUR),
                after, "").equals(Collections.singletonList("Fable only")), "two days early is named");
        check(RefillGuard.suspect(snapshot(null, null, Collections.singletonList(later), NOW - HOUR), after),
                "and held first");
    }

    private static void earlyRefillIsHeldUntilConfirmed() {
        // Antigravity: 60% used, reset in 3 hours, then one answer says nothing is used.
        UsageSnapshot before = snapshot(fiveHour(60, NOW + 3 * HOUR), null, NONE, NOW - 5 * MINUTE);
        UsageSnapshot full = snapshot(fiveHour(0, NOW + 5 * HOUR), null, NONE, NOW);
        check(RefillGuard.suspect(before, full), "a full limit 3 hours early is suspect");
        check(RefillGuard.hold(before, full, 0L, NOW), "the first such reading is held");
        long since = NOW;
        check(RefillGuard.hold(before, full, since, NOW + 10_000L), "a refresh 10 s later still holds");
        check(!RefillGuard.hold(before, full, since, NOW + RefillGuard.CONFIRM_DELAY_MS),
                "the re-check after the delay confirms it");
        check(RefillGuard.CONFIRM_DELAY_MS >= RefillGuard.MIN_CONFIRM_GAP_MS,
                "the scheduled re-check is late enough to confirm");
        check(RefillGuard.hold(before, full, since, NOW + RefillGuard.HOLD_EXPIRES_MS + MINUTE),
                "a forgotten hold does not confirm a reading much later");
    }

    private static void flukeThatRevertsIsDropped() {
        UsageSnapshot before = snapshot(fiveHour(60, NOW + 3 * HOUR), null, NONE, NOW - 5 * MINUTE);
        UsageSnapshot real = snapshot(fiveHour(61, NOW + 3 * HOUR), null, NONE, NOW + 45_000L);
        check(!RefillGuard.suspect(before, real), "the reverted reading is ordinary");
        check(!RefillGuard.hold(before, real, NOW, NOW + 45_000L), "and is saved, with nothing announced");
        check(CelebrationDetector.detectUnexpectedRefills(before, real) == 0, "no surprise refill");
    }

    private static void usedUpLimitComingBackEarlyIsHeld() {
        UsageSnapshot usedUp = snapshot(fiveHour(100, NOW + 2 * HOUR), null, NONE, NOW - 5 * MINUTE);
        UsageSnapshot some = snapshot(fiveHour(30, NOW + 2 * HOUR), null, NONE, NOW);
        check(RefillGuard.suspect(usedUp, some), "a used-up limit with some left 2 hours early is held");
        UsageSnapshot onTime = snapshot(fiveHour(100, NOW + MINUTE), null, NONE, NOW - 5 * MINUTE);
        check(!RefillGuard.suspect(onTime, some), "a minute early is its reset");
        UsageWindow noReset = new UsageWindow(100, 30 * 86400L, 0L, 0L);
        UsageWindow back = new UsageWindow(20, 30 * 86400L, 0L, 0L);
        check(RefillGuard.suspect(snapshot(noReset, null, NONE, NOW - HOUR), snapshot(back, null, NONE, NOW)),
                "a used-up limit with no reset time coming back is held");
    }

    private static void onTimeAndOrdinaryReadingsAreNeverHeld() {
        UsageSnapshot before = snapshot(fiveHour(60, NOW - MINUTE), null, NONE, NOW - 10 * MINUTE);
        UsageSnapshot after = snapshot(fiveHour(0, NOW + 5 * HOUR), null, NONE, NOW);
        check(!RefillGuard.hold(before, after, 0L, NOW), "a reset after its time shows at once");
        UsageSnapshot more = snapshot(fiveHour(65, NOW + 3 * HOUR), null, NONE, NOW);
        check(!RefillGuard.hold(snapshot(fiveHour(60, NOW + 3 * HOUR), null, NONE, NOW - MINUTE), more, 0L, NOW),
                "more usage is never held");
        UsageWindow noReset = new UsageWindow(40, 30 * 86400L, 0L, 0L);
        UsageWindow zero = new UsageWindow(0, 30 * 86400L, 0L, 0L);
        check(!RefillGuard.suspect(snapshot(noReset, null, NONE, NOW - HOUR), snapshot(zero, null, NONE, NOW)),
                "a refill with no reset time, nothing used up, announces nothing and is not held");
        check(!RefillGuard.suspect(null, after), "the first reading is never held");
    }
}
