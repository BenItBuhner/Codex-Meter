package dev.bennett.codexmeter;

/** The always-on "usage is back" alert: arm on 0%, announce once on reset or refill. */
public final class UsageRefillSelfTest {
    private static final long NOW = 1_794_000_000_000L;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) {
        usedUpLimitArmsWithItsResetTime();
        sameResetDoesNotReArm();
        refillAnnouncesOnceAndForgets();
        unknownResetStillArms();
        staleReadingDoesNotArm();
        alarmMatchesOnlyTheArmedReset();
        copyNamesProviderAndLimit();
        priorityOrdersUsedUpBySoonestReset();
        System.out.println("Usage refill alert: arm, announce once, alarm matching and copy verified.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static UsageWindow fiveHour(int used, long resetAtMillis) {
        return new UsageWindow(used, 5 * 3600L, 0L, resetAtMillis / 1000L);
    }

    private static void usedUpLimitArmsWithItsResetTime() {
        UsageRefill.Decision d = UsageRefill.decide(0L, fiveHour(100, NOW + 2 * HOUR), NOW, NOW);
        check(d.action == UsageRefill.Action.ARM, "a used-up limit arms");
        check(d.resetAt == NOW + 2 * HOUR, "armed with its reset time");
        check(UsageRefill.decide(0L, fiveHour(99, NOW + 2 * HOUR), NOW, NOW).action
                == UsageRefill.Action.NONE, "a limit with some left does not arm");
    }

    private static void sameResetDoesNotReArm() {
        long armed = NOW + 2 * HOUR;
        check(UsageRefill.decide(armed, fiveHour(100, armed + 20_000L), NOW, NOW).action
                == UsageRefill.Action.NONE, "a few seconds of drift is the same window");
        UsageRefill.Decision moved = UsageRefill.decide(armed, fiveHour(100, armed + HOUR), NOW, NOW);
        check(moved.action == UsageRefill.Action.ARM && moved.resetAt == armed + HOUR,
                "a moved reset re-arms at the new time");
    }

    private static void refillAnnouncesOnceAndForgets() {
        UsageRefill.Decision d = UsageRefill.decide(NOW + HOUR, fiveHour(0, NOW + 6 * HOUR), NOW, NOW);
        check(d.action == UsageRefill.Action.BACK && d.left == 100, "a refill is announced with what is left");
        check(UsageRefill.decide(d.resetAt, fiveHour(0, NOW + 6 * HOUR), NOW, NOW).action
                == UsageRefill.Action.NONE, "and only once");
        check(UsageRefill.decide(NOW + HOUR, null, NOW, NOW).action == UsageRefill.Action.NONE,
                "a missing window keeps waiting");
    }

    private static void unknownResetStillArms() {
        UsageWindow noReset = new UsageWindow(100, 30 * 86400L, 0L, 0L);
        UsageRefill.Decision d = UsageRefill.decide(0L, noReset, NOW, NOW);
        check(d.action == UsageRefill.Action.ARM && d.resetAt == UsageRefill.UNKNOWN_RESET,
                "a used-up limit with no reset time still waits for a refill");
        check(UsageRefill.decide(UsageRefill.UNKNOWN_RESET, new UsageWindow(40, 30 * 86400L, 0L, 0L),
                NOW, NOW).action == UsageRefill.Action.BACK, "and is announced when a refresh shows some left");
    }

    private static void staleReadingDoesNotArm() {
        check(UsageRefill.decide(0L, fiveHour(100, NOW - HOUR), NOW - 2 * HOUR, NOW).action
                == UsageRefill.Action.NONE, "a reading older than its own reset does not arm again");
    }

    private static void alarmMatchesOnlyTheArmedReset() {
        long armed = NOW + HOUR;
        check(UsageRefill.alarmStillDue(armed, armed), "the alarm for the armed reset fires");
        check(!UsageRefill.alarmStillDue(armed, armed - HOUR), "an old alarm does not");
        check(!UsageRefill.alarmStillDue(0L, armed), "nothing armed, nothing fires");
        check(!UsageRefill.alarmStillDue(UsageRefill.UNKNOWN_RESET, armed), "unknown resets have no alarm");
    }

    private static void copyNamesProviderAndLimit() {
        check(UsageRefill.title("Claude Code").equals("Claude Code usage is back"), "title");
        check(UsageRefill.text("Claude Code", "5-hour", -1)
                .equals("Your 5-hour limit has reset. You can use Claude Code again."), "reset copy");
        check(UsageRefill.text("Cursor", "Monthly", 100)
                .equals("Your monthly limit refilled to 100% left. You can use Cursor again."), "refill copy");
    }

    private static void priorityOrdersUsedUpBySoonestReset() {
        check(PriorityView.resetOrder(NOW) < PriorityView.resetOrder(NOW + HOUR), "soonest first");
        check(PriorityView.resetOrder(0L) > PriorityView.resetOrder(NOW + 999 * HOUR), "unknown last");
    }
}
