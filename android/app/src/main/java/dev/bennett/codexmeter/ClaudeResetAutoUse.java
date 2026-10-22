package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/**
 * Pure decision: should a saved Claude Code reset be spent automatically right now? Same rules,
 * lead times and thresholds as the Codex reset credits:
 * <ul>
 *   <li><b>Before it expires</b> (on by default): use a reset once it is within the chosen lead
 *       time of expiring.</li>
 *   <li><b>When running low</b> (opt-in): use one when a 5-hour or weekly window's remaining
 *       allowance reaches the chosen threshold, once per window, skipping a window that refills
 *       on its own within 15 minutes ({@link ResetCreditLowUsageAutoUse}).</li>
 * </ul>
 * The server decides whether a grant can be used now ({@code usable_now}); some, like a full
 * reset, work any time, others only at a limit. Nothing fires on a grant the server holds back.
 */
public final class ClaudeResetAutoUse {
    /** Only a status this fresh may spend a reset. */
    public static final long MAX_STATUS_AGE_MS = TimeUnit.MINUTES.toMillis(2);

    public static final String FIRE_EXPIRING = "fire_expiring";
    public static final String FIRE_LOW_USAGE = "fire_low_usage";
    public static final String WAIT = "wait";
    public static final String SKIP_ALREADY_TRIED = "skip_already_tried";
    public static final String SKIP_NO_MATCHING_RESET = "skip_no_matching_reset";

    public final String outcome;
    public final ClaudeSavedResets.Grant grant;
    /** Remembered after an attempt so the same grant or window is tried once. */
    public final String attemptKey;
    /** For the running-low rule: "5-hour" / "weekly" and the remaining percent. */
    public final String windowLabel;
    public final int remainingPercent;

    private ClaudeResetAutoUse(String outcome, ClaudeSavedResets.Grant grant, String attemptKey,
            String windowLabel, int remainingPercent) {
        this.outcome = outcome;
        this.grant = grant;
        this.attemptKey = attemptKey == null ? "" : attemptKey;
        this.windowLabel = windowLabel == null ? "" : windowLabel;
        this.remainingPercent = remainingPercent;
    }

    public boolean fires() {
        return FIRE_EXPIRING.equals(outcome) || FIRE_LOW_USAGE.equals(outcome);
    }

    /**
     * @param lastExpiryKey the {@link #attemptKey} of the last expiry attempt
     * @param lastLowUsageToken the {@link #attemptKey} of the last running-low attempt
     */
    public static ClaudeResetAutoUse evaluate(ClaudeSavedResets status, UsageSnapshot usage,
            boolean expiryEnabled, long leadMillis, boolean lowUsageEnabled, int threshold,
            String metric, String lastExpiryKey, String lastLowUsageToken, long nowMillis) {
        if ((!expiryEnabled && !lowUsageEnabled) || status == null
                || status.fetchedAtMillis <= 0L
                || nowMillis - status.fetchedAtMillis > MAX_STATUS_AGE_MS) {
            return waiting();
        }
        ClaudeResetAutoUse skipped = null;
        if (expiryEnabled) {
            long lead = ResetCreditExpiryApply.normalizeLead(leadMillis);
            ClaudeSavedResets.Grant expiring = null;
            for (ClaudeSavedResets.Grant grant : status.grants) {
                if (!grant.hasResets(nowMillis) || !grant.usableNow
                        || grant.endsAtMillis <= 0L
                        || grant.endsAtMillis - nowMillis > lead) {
                    continue;
                }
                if (expiring == null || grant.endsAtMillis < expiring.endsAtMillis) {
                    expiring = grant;
                }
            }
            if (expiring != null) {
                String key = "expiry:" + expiring.id + ":" + expiring.resetsLeft;
                if (!key.equals(lastExpiryKey)) {
                    return new ClaudeResetAutoUse(FIRE_EXPIRING, expiring, key, "", -1);
                }
                skipped = new ClaudeResetAutoUse(SKIP_ALREADY_TRIED, expiring, key, "", -1);
            }
        }
        if (lowUsageEnabled && usage != null) {
            ResetCreditLowUsageAutoUse low = ResetCreditLowUsageAutoUse.evaluate(usage, metric,
                    threshold, status.availableCount(nowMillis), lastLowUsageToken, nowMillis);
            if (low.fires()) {
                ClaudeSavedResets.Grant grant = status.usableGrantFor(low.windowLabel, nowMillis);
                if (grant == null) {
                    return new ClaudeResetAutoUse(SKIP_NO_MATCHING_RESET, null, low.windowToken,
                            low.windowLabel, low.remainingPercent);
                }
                return new ClaudeResetAutoUse(FIRE_LOW_USAGE, grant, low.windowToken,
                        low.windowLabel, low.remainingPercent);
            }
            if (ResetCreditLowUsageAutoUse.SKIP_ALREADY_USED.equals(low.outcome)) {
                return new ClaudeResetAutoUse(SKIP_ALREADY_TRIED, null, low.windowToken,
                        low.windowLabel, low.remainingPercent);
            }
        }
        return skipped == null ? waiting() : skipped;
    }

    private static ClaudeResetAutoUse waiting() {
        return new ClaudeResetAutoUse(WAIT, null, "", "", -1);
    }
}
