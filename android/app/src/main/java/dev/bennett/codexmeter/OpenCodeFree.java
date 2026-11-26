package dev.bennett.codexmeter;

/**
 * OpenCode's free tier: what Codex Meter can know about it without an account.
 *
 * <p>OpenCode's gateway counts free-model requests per network (IP address) per UTC day and
 * refuses with {@code FreeUsageLimitError} once a network reaches its daily number, with a
 * {@code retry-after} of the seconds left until 00:00 UTC. It has no route that reports how
 * many requests are used or left, and it only serves free models to OpenCode itself, so the
 * daily reset is the one thing that can be shown honestly. The Apple and desktop apps use the
 * same words.
 */
public final class OpenCodeFree {
    static final long DAY_MILLIS = 86_400_000L;

    /** Dashboard card title. */
    public static final String CARD_TITLE = "Daily free limit";

    /** What the card says under the reset time. */
    public static final String EXPLANATION = "OpenCode counts free-model requests from your "
            + "network each day and resets them at midnight UTC. It doesn't share how many "
            + "are left.";

    /** Sign-in screen copy: there is nothing to sign in to. */
    public static final String HOW_IT_WORKS = "No sign-in needed. OpenCode's free models have "
            + "a daily limit for each network, and Codex Meter shows when it resets. OpenCode "
            + "doesn't share how much of it is used, so there's no percentage.";

    /** Sign-in screen footer: nothing is stored or sent. */
    public static final String PRIVACY = "Codex Meter works out the reset time on this device. "
            + "Nothing is sent to OpenCode or anyone else.";

    private OpenCodeFree() {
    }

    /** The next 00:00 UTC strictly after {@code nowMillis}, in epoch milliseconds. */
    public static long nextResetMillis(long nowMillis) {
        return (Math.floorDiv(nowMillis, DAY_MILLIS) + 1L) * DAY_MILLIS;
    }

    /** The snapshot the client saves: no meters, only the plan and when it was read. */
    public static UsageSnapshot snapshot(long nowMillis) {
        return new UsageSnapshot("Free", true, false, null, null, nowMillis);
    }
}
