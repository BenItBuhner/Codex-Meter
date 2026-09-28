package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One armed "Scheduled reset": a one-shot rule that spends a single reset credit either at a
 * chosen date and time or once a window's remaining usage drops to a chosen threshold. The
 * trigger and the fire-time guards are evaluated here, as pure logic, so Android and iOS make
 * the same decision from the same inputs (see {@code android/tests/fixtures/scheduled-reset-cases.json}).
 */
public final class ScheduledReset {
    public static final String TRIGGER_DATE_TIME = "date_time";
    public static final String TRIGGER_FIVE_HOUR = "five_hour";
    public static final String TRIGGER_WEEKLY = "weekly";
    public static final int CONDITION_OFF = -1;
    public static final int[] THRESHOLD_PRESETS = {0, 5, 10};
    public static final long DEFAULT_IMMINENT_RESET_MS = TimeUnit.MINUTES.toMillis(15);
    /** A snapshot older than this is never trusted to spend a credit; the caller refreshes first. */
    public static final long MAX_SNAPSHOT_AGE_MS = TimeUnit.MINUTES.toMillis(2);

    public final String trigger;
    /** Threshold triggers fire once the targeted window's remaining percent is at or below this. */
    public final int thresholdPercent;
    /** Date/time triggers fire once the clock reaches this instant. */
    public final long fireAtMillis;
    /** Date/time only: fire only if the lowest remaining percent is at or below this; {@link #CONDITION_OFF} otherwise. */
    public final int conditionPercent;
    public final long createdAtMillis;

    private ScheduledReset(String trigger, int thresholdPercent, long fireAtMillis,
            int conditionPercent, long createdAtMillis) {
        this.trigger = TRIGGER_DATE_TIME.equals(trigger) || TRIGGER_WEEKLY.equals(trigger)
                ? trigger : TRIGGER_FIVE_HOUR;
        this.thresholdPercent = clampPercent(thresholdPercent);
        this.fireAtMillis = Math.max(0L, fireAtMillis);
        this.conditionPercent = conditionPercent < 0 ? CONDITION_OFF : clampPercent(conditionPercent);
        this.createdAtMillis = Math.max(0L, createdAtMillis);
    }

    public static ScheduledReset atThreshold(String windowTrigger, int thresholdPercent,
            long createdAtMillis) {
        String trigger = TRIGGER_WEEKLY.equals(windowTrigger) ? TRIGGER_WEEKLY : TRIGGER_FIVE_HOUR;
        return new ScheduledReset(trigger, thresholdPercent, 0L, CONDITION_OFF, createdAtMillis);
    }

    public static ScheduledReset atDateTime(long fireAtMillis, int conditionPercent,
            long createdAtMillis) {
        return new ScheduledReset(TRIGGER_DATE_TIME, 0, fireAtMillis, conditionPercent,
                createdAtMillis);
    }

    public boolean isDateTime() {
        return TRIGGER_DATE_TIME.equals(trigger);
    }

    public boolean isThreshold() {
        return !isDateTime();
    }

    public boolean hasCondition() {
        return isDateTime() && conditionPercent != CONDITION_OFF;
    }

    /**
     * The window a threshold trigger watches. The weekly trigger follows the long-cadence window,
     * so a Free-tier account that only reports a monthly window is still covered.
     */
    public UsageWindow targetWindow(UsageSnapshot snapshot) {
        if (snapshot == null || isDateTime()) return null;
        return TRIGGER_WEEKLY.equals(trigger) ? snapshot.longWindow() : snapshot.fiveHour;
    }

    /** "5-hour", "weekly", or "monthly" when the weekly trigger rides on a monthly window. */
    public String windowLabel(UsageSnapshot snapshot) {
        if (TRIGGER_WEEKLY.equals(trigger)) {
            return snapshot != null && snapshot.longWindowIsMonthly() ? "monthly" : "weekly";
        }
        return isDateTime() ? "" : "5-hour";
    }

    /** Dashboard/reset-screen state, e.g. "Scheduled · when 5-hour reaches 5%". */
    public String armedLabel(UsageSnapshot snapshot, String dateTimeText) {
        if (isDateTime()) return "Scheduled · " + dateTimeText;
        return "Scheduled · when " + windowLabel(snapshot) + " reaches " + thresholdPercent + "%";
    }

    /** The irreversible-action confirmation copy; identical on both platforms. */
    public String confirmationText(UsageSnapshot snapshot, String dateTimeText) {
        if (!isDateTime()) {
            return "Codex Meter will use 1 reset credit when your " + windowLabel(snapshot)
                    + " limit reaches " + thresholdPercent + "% remaining.";
        }
        String text = "Codex Meter will use 1 reset credit " + dateTimeText + ".";
        if (hasCondition()) {
            text += " It only runs if your remaining usage is at or below " + conditionPercent
                    + "% at that time.";
        }
        return text;
    }

    /** Whether every available credit expires before a date/time trigger, so it would find none. */
    public boolean creditsExpireBefore(long[] creditExpiryMillis) {
        if (!isDateTime() || creditExpiryMillis == null || creditExpiryMillis.length == 0) {
            return false;
        }
        for (long expiry : creditExpiryMillis) {
            if (expiry <= 0L || expiry > fireAtMillis) return false;
        }
        return true;
    }

    /**
     * Percentage points between the targeted window's remaining usage and the threshold, so the
     * refresh policy can tighten its cadence as the trigger nears. {@link Integer#MAX_VALUE} when
     * the schedule is a date/time trigger or the window is unavailable.
     */
    public int thresholdGap(UsageSnapshot snapshot, long nowMillis) {
        UsageWindow window = snapshot == null ? null
                : UsageSnapshot.currentWindow(targetWindow(snapshot), snapshot.fetchedAtMillis,
                        nowMillis);
        if (window == null) return Integer.MAX_VALUE;
        return Math.max(0, window.remainingPercent() - thresholdPercent);
    }

    public Decision evaluate(UsageSnapshot snapshot, int availableCredits, long nowMillis) {
        return evaluate(snapshot, availableCredits, nowMillis, DEFAULT_IMMINENT_RESET_MS);
    }

    /**
     * Decides what a due schedule should do from fresh data. {@code availableCredits} below zero
     * means unknown, in which case the consume path reports the shortage instead.
     */
    public Decision evaluate(UsageSnapshot snapshot, int availableCredits, long nowMillis,
            long imminentResetMillis) {
        if (isDateTime() && nowMillis < fireAtMillis) return Decision.waiting();
        if (snapshot == null || snapshot.fetchedAtMillis <= 0L
                || nowMillis - snapshot.fetchedAtMillis > MAX_SNAPSHOT_AGE_MS) {
            return Decision.waiting();
        }
        long observedAt = snapshot.fetchedAtMillis;
        if (isThreshold()) {
            UsageWindow window = UsageSnapshot.currentWindow(targetWindow(snapshot), observedAt,
                    nowMillis);
            if (window == null || window.remainingPercent() > thresholdPercent) {
                return Decision.waiting();
            }
            String label = windowLabel(snapshot);
            if (availableCredits == 0) {
                return new Decision(Decision.SKIP_NO_CREDIT, label, window.remainingPercent(), 0L);
            }
            long reset = window.effectiveResetAtMillis(observedAt);
            if (reset > nowMillis && reset - nowMillis <= imminentResetMillis) {
                return new Decision(Decision.SKIP_RESET_IMMINENT, label,
                        window.remainingPercent(), reset);
            }
            return new Decision(Decision.FIRE, label, window.remainingPercent(), 0L);
        }

        UsageWindow fiveHour = UsageSnapshot.currentWindow(snapshot.fiveHour, observedAt, nowMillis);
        UsageWindow longWindow = UsageSnapshot.currentWindow(snapshot.longWindow(), observedAt,
                nowMillis);
        String longLabel = snapshot.longWindowIsMonthly() ? "monthly" : "weekly";
        int lowestRemaining = 101;
        if (fiveHour != null) lowestRemaining = Math.min(lowestRemaining, fiveHour.remainingPercent());
        if (longWindow != null) lowestRemaining = Math.min(lowestRemaining, longWindow.remainingPercent());
        int remaining = lowestRemaining > 100 ? -1 : lowestRemaining;
        if (availableCredits == 0) {
            return new Decision(Decision.SKIP_NO_CREDIT, "", remaining, 0L);
        }
        // A reset clears every window, so a credit is only wasted when each used window is
        // about to reset on its own anyway.
        boolean anyUsed = false;
        boolean allImminent = true;
        String imminentLabel = "";
        long imminentReset = 0L;
        UsageWindow[] windows = {fiveHour, longWindow};
        String[] labels = {"5-hour", longLabel};
        for (int index = 0; index < windows.length; index++) {
            UsageWindow window = windows[index];
            if (window == null || window.usedPercent <= 0) continue;
            anyUsed = true;
            long reset = window.effectiveResetAtMillis(observedAt);
            boolean imminent = reset > nowMillis && reset - nowMillis <= imminentResetMillis;
            if (imminent && imminentReset == 0L) {
                imminentLabel = labels[index];
                imminentReset = reset;
            }
            allImminent &= imminent;
        }
        if (anyUsed && allImminent) {
            return new Decision(Decision.SKIP_RESET_IMMINENT, imminentLabel, remaining,
                    imminentReset);
        }
        if (!anyUsed) {
            return new Decision(Decision.SKIP_NOTHING_TO_RESET, "", remaining, 0L);
        }
        if (hasCondition() && remaining > conditionPercent) {
            return new Decision(Decision.SKIP_CONDITION_NOT_MET, "", remaining, 0L);
        }
        return new Decision(Decision.FIRE, "", remaining, 0L);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("trigger", trigger)
                .put("threshold_percent", thresholdPercent)
                .put("fire_at_millis", fireAtMillis)
                .put("condition_percent", conditionPercent)
                .put("created_at_millis", createdAtMillis);
    }

    public static ScheduledReset fromJson(JSONObject json) {
        if (json == null || !json.has("trigger")) return null;
        return new ScheduledReset(json.optString("trigger", TRIGGER_FIVE_HOUR),
                json.optInt("threshold_percent", 0),
                json.optLong("fire_at_millis", 0L),
                json.optInt("condition_percent", CONDITION_OFF),
                json.optLong("created_at_millis", 0L));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** The outcome of one evaluation plus the details its user-facing copy needs. */
    public static final class Decision {
        public static final String WAIT = "wait";
        public static final String FIRE = "fire";
        public static final String SKIP_NO_CREDIT = "skip_no_credit";
        public static final String SKIP_RESET_IMMINENT = "skip_reset_imminent";
        public static final String SKIP_NOTHING_TO_RESET = "skip_nothing_to_reset";
        public static final String SKIP_CONDITION_NOT_MET = "skip_condition_not_met";

        public final String outcome;
        /** The window the decision hinged on ("5-hour", "weekly", "monthly"), or empty. */
        public final String windowLabel;
        /** Remaining percent that was evaluated, or -1 when no window was available. */
        public final int remainingPercent;
        /** The natural reset that made a credit pointless; 0 unless the outcome is imminent. */
        public final long naturalResetMillis;

        Decision(String outcome, String windowLabel, int remainingPercent, long naturalResetMillis) {
            this.outcome = outcome;
            this.windowLabel = windowLabel == null ? "" : windowLabel;
            this.remainingPercent = remainingPercent;
            this.naturalResetMillis = Math.max(0L, naturalResetMillis);
        }

        static Decision waiting() {
            return new Decision(WAIT, "", -1, 0L);
        }

        public boolean fires() {
            return FIRE.equals(outcome);
        }

        public boolean waits() {
            return WAIT.equals(outcome);
        }

        public boolean skipped() {
            return !fires() && !waits();
        }

        /** Why the credit was kept, phrased for the skipped notification. */
        public String skipReason(ScheduledReset schedule, long nowMillis) {
            switch (outcome) {
                case SKIP_NO_CREDIT:
                    return "No reset credit was available, so nothing was used.";
                case SKIP_RESET_IMMINENT: {
                    long minutes = TimeUnit.MILLISECONDS.toMinutes(
                            Math.max(0L, naturalResetMillis - nowMillis));
                    String when = minutes <= 0 ? "in under a minute"
                            : minutes == 1 ? "in 1 minute" : "in " + minutes + " minutes";
                    return "Your " + windowLabel + " limit resets on its own " + when
                            + ", so the credit was kept.";
                }
                case SKIP_NOTHING_TO_RESET:
                    return "Nothing had been used, so the credit was kept.";
                case SKIP_CONDITION_NOT_MET:
                    return "Remaining usage was " + remainingPercent + "%, above your "
                            + schedule.conditionPercent + "% condition, so the credit was kept.";
                default:
                    return "";
            }
        }

        /** What happened, phrased for the fired notification. */
        public String firedReason(ScheduledReset schedule) {
            if (schedule.isDateTime()) {
                return "1 reset credit was used at the scheduled time.";
            }
            return "Your " + windowLabel + " limit reached " + remainingPercent
                    + "% remaining, so 1 reset credit was used.";
        }
    }
}
