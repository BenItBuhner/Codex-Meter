package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Deterministic sample usage behind "Explore demo". The values mirror the iOS
 * {@code DemoCodexService} so screenshots match across platforms; nothing here reaches the
 * network or the credential store.
 */
public final class DemoData {
    public static final String PLAN_TYPE = "plus";
    public static final String USAGE_CREDIT_BALANCE = "2500";
    static final int INITIAL_FIVE_HOUR_USED = 38;
    static final int INITIAL_WEEKLY_USED = 64;
    static final int INITIAL_RESET_CREDITS = 2;
    static final long FIVE_HOUR_RESET_OFFSET_MS =
            TimeUnit.HOURS.toMillis(2) + TimeUnit.MINUTES.toMillis(17);
    static final long WEEKLY_RESET_OFFSET_MS =
            TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(8);
    private static final long FIVE_HOUR_WINDOW_SECONDS = TimeUnit.HOURS.toSeconds(5);
    private static final long WEEKLY_WINDOW_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final long SPARK_PRIMARY_RESET_OFFSET_MS = TimeUnit.HOURS.toMillis(3);
    private static final long SPARK_SECONDARY_RESET_OFFSET_MS = TimeUnit.DAYS.toMillis(5);

    // Completed windows cover quiet, steady, heavy, and bursty burns for the history overlays.
    private static final int[][] FIVE_HOUR_SHAPES = {
            {3, 7, 12, 18, 22},
            {6, 14, 25, 33, 41},
            {12, 30, 52, 74, 96},
            {10, 26, 38, 61, 83},
    };
    private static final int[][] WEEKLY_SHAPES = {
            {5, 9, 14, 22, 30, 38},
            {8, 19, 33, 47, 58, 71},
            {15, 34, 52, 78, 95, 100},
            {11, 24, 39, 52, 66, 84},
    };
    // Current-window climbs end at the initial used percentages so the first recorded sample
    // coincides with the seeded history instead of duplicating it.
    private static final int[] FIVE_HOUR_CLIMB = {4, 10, 17, 24, 31, INITIAL_FIVE_HOUR_USED};
    private static final int[] WEEKLY_CLIMB = {6, 14, 22, 32, 41, 49, 57, INITIAL_WEEKLY_USED};

    private DemoData() {
    }

    /** Persisted demo progression; every derived surface is a pure function of this state. */
    public static final class State {
        public final long referenceMillis;
        public final int refreshCount;
        public final int fiveHourUsed;
        public final int weeklyUsed;
        public final int availableCredits;

        State(long referenceMillis, int refreshCount, int fiveHourUsed, int weeklyUsed,
                int availableCredits) {
            this.referenceMillis = Math.max(0L, referenceMillis);
            this.refreshCount = Math.max(0, refreshCount);
            this.fiveHourUsed = clampPercent(fiveHourUsed);
            this.weeklyUsed = clampPercent(weeklyUsed);
            this.availableCredits = Math.max(0, Math.min(INITIAL_RESET_CREDITS, availableCredits));
        }

        public static State initial(long referenceMillis) {
            return new State(referenceMillis, 0, INITIAL_FIVE_HOUR_USED, INITIAL_WEEKLY_USED,
                    INITIAL_RESET_CREDITS);
        }

        public long fiveHourResetMillis() {
            return referenceMillis + FIVE_HOUR_RESET_OFFSET_MS;
        }

        public long weeklyResetMillis() {
            return referenceMillis + WEEKLY_RESET_OFFSET_MS;
        }

        /** Each refresh nudges usage upward a little; the first one only reports the seed. */
        public State refreshed(long nowMillis) {
            State base = anchored(nowMillis);
            int count = base.refreshCount + 1;
            if (count == 1) {
                return new State(base.referenceMillis, count, base.fiveHourUsed,
                        base.weeklyUsed, base.availableCredits);
            }
            return new State(base.referenceMillis, count,
                    Math.min(100, base.fiveHourUsed + 1),
                    count % 2 == 0 ? Math.min(100, base.weeklyUsed + 1) : base.weeklyUsed,
                    base.availableCredits);
        }

        /** Spends one credit and clears both windows, like a real Codex reset. */
        public State resetConsumed(long nowMillis) {
            State base = anchored(nowMillis);
            if (base.availableCredits <= 0) {
                return base;
            }
            return new State(base.referenceMillis, base.refreshCount + 1, 0, 0,
                    base.availableCredits - 1);
        }

        /** Re-anchors the timeline once the 5-hour window would have reset while the demo sat idle. */
        State anchored(long nowMillis) {
            if (nowMillis < fiveHourResetMillis()) {
                return this;
            }
            return new State(nowMillis, 0, INITIAL_FIVE_HOUR_USED,
                    nowMillis >= weeklyResetMillis() ? INITIAL_WEEKLY_USED : weeklyUsed,
                    availableCredits);
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("reference_millis", referenceMillis)
                    .put("refresh_count", refreshCount)
                    .put("five_hour_used", fiveHourUsed)
                    .put("weekly_used", weeklyUsed)
                    .put("available_credits", availableCredits);
        }

        public static State fromJson(JSONObject json) {
            if (json == null || !json.has("reference_millis")) {
                return null;
            }
            return new State(json.optLong("reference_millis", 0L),
                    json.optInt("refresh_count", 0),
                    json.optInt("five_hour_used", INITIAL_FIVE_HOUR_USED),
                    json.optInt("weekly_used", INITIAL_WEEKLY_USED),
                    json.optInt("available_credits", INITIAL_RESET_CREDITS));
        }

        private static int clampPercent(int value) {
            return Math.max(0, Math.min(100, value));
        }
    }

    public static UsageSnapshot snapshot(State state, long fetchedAtMillis) {
        long reference = state.referenceMillis;
        UsageWindow fiveHour = new UsageWindow(state.fiveHourUsed, FIVE_HOUR_WINDOW_SECONDS, 0L,
                seconds(state.fiveHourResetMillis()));
        UsageWindow weekly = new UsageWindow(state.weeklyUsed, WEEKLY_WINDOW_SECONDS, 0L,
                seconds(state.weeklyResetMillis()));
        UsageLimit spark = new UsageLimit("codex-spark", "GPT-5.3-Codex-Spark", "codex_bengalfox",
                true, false,
                new UsageWindow(24, FIVE_HOUR_WINDOW_SECONDS, 0L,
                        seconds(reference + SPARK_PRIMARY_RESET_OFFSET_MS)),
                new UsageWindow(42, WEEKLY_WINDOW_SECONDS, 0L,
                        seconds(reference + SPARK_SECONDARY_RESET_OFFSET_MS)));
        return new UsageSnapshot(PLAN_TYPE, true, false, fiveHour, weekly,
                Collections.singletonList(spark),
                new UsageCredits(true, false, USAGE_CREDIT_BALANCE),
                state.availableCredits, fetchedAtMillis);
    }

    /** Credits expire soonest-first, so spending one removes the earliest expiry. */
    public static ResetCreditsSnapshot resetCredits(State state, long fetchedAtMillis) {
        List<RateLimitResetCredit> credits = new ArrayList<>();
        for (int index = INITIAL_RESET_CREDITS - state.availableCredits;
                index < INITIAL_RESET_CREDITS; index++) {
            int ordinal = index + 1;
            credits.add(new RateLimitResetCredit("demo-credit-" + ordinal, "rate_limit",
                    RateLimitResetCredit.STATUS_AVAILABLE,
                    state.referenceMillis - TimeUnit.DAYS.toMillis(ordinal),
                    state.referenceMillis + TimeUnit.DAYS.toMillis(3L * (index + 2)),
                    "Codex reset", "Demo reset credit"));
        }
        return new ResetCreditsSnapshot(state.availableCredits, credits, fetchedAtMillis);
    }

    /** Four completed windows plus the climb of the current one, ending at the reference time. */
    public static UsageHistory seededHistory(State state, String kind) {
        boolean weekly = UsageHistory.WEEKLY.equals(kind);
        if (!weekly && !UsageHistory.FIVE_HOUR.equals(kind)) {
            return UsageHistory.empty(kind);
        }
        long windowMillis = weekly ? TimeUnit.DAYS.toMillis(7) : TimeUnit.HOURS.toMillis(5);
        long windowSeconds = weekly ? WEEKLY_WINDOW_SECONDS : FIVE_HOUR_WINDOW_SECONDS;
        long currentReset = weekly ? state.weeklyResetMillis() : state.fiveHourResetMillis();
        long sampleSpacing = weekly ? TimeUnit.HOURS.toMillis(24) : TimeUnit.MINUTES.toMillis(45);
        int[][] shapes = weekly ? WEEKLY_SHAPES : FIVE_HOUR_SHAPES;
        UsageHistory history = UsageHistory.empty(kind);
        for (int back = shapes.length; back >= 1; back--) {
            long reset = currentReset - windowMillis * back;
            int[] shape = shapes[shapes.length - back];
            for (int point = 0; point < shape.length; point++) {
                history = history.append(
                        new UsageWindow(shape[point], windowSeconds, 0L, seconds(reset)),
                        reset - windowMillis + sampleSpacing * (point + 1));
            }
        }
        int[] climb = weekly ? WEEKLY_CLIMB : FIVE_HOUR_CLIMB;
        long climbSpacing = weekly ? TimeUnit.HOURS.toMillis(12) : TimeUnit.MINUTES.toMillis(30);
        for (int point = 0; point < climb.length; point++) {
            history = history.append(
                    new UsageWindow(climb[point], windowSeconds, 0L, seconds(currentReset)),
                    state.referenceMillis - climbSpacing * (climb.length - 1 - point));
        }
        return history;
    }

    private static long seconds(long millis) {
        return millis / 1000L;
    }
}
