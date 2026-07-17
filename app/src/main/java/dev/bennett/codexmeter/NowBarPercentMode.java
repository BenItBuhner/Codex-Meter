package dev.bennett.codexmeter;

/**
 * Chooses which remaining-percentage value drives the Now Bar / Live Update
 * progress pill. Explicit modes lock to one window; automatic mode locks to the
 * window that triggered auto-start (lower remaining when both triggered), or to
 * the lower remaining value for a manual start.
 */
public final class NowBarPercentMode {
    public static final String AUTO = "auto";
    public static final String FIVE_HOUR = "five_hour";
    public static final String WEEKLY = "weekly";

    private NowBarPercentMode() {
    }

    public static String normalize(String mode) {
        if (FIVE_HOUR.equals(mode) || WEEKLY.equals(mode)) {
            return mode;
        }
        return AUTO;
    }

    public static String normalizeFocusMetric(String focus) {
        if (FIVE_HOUR.equals(focus) || WEEKLY.equals(focus)) {
            return focus;
        }
        return null;
    }

    /**
     * Among watched windows that are at or below the auto-start threshold, returns
     * the focus metric with the lower remaining percentage. Ties prefer five-hour.
     */
    public static String triggeredFocus(String autoStartMetric, int threshold,
            UsageWindow fiveHour, UsageWindow weekly) {
        String watched = NowBarAutoStart.normalizeMetric(autoStartMetric);
        boolean fiveTriggered = fiveHour != null
                && !NowBarAutoStart.METRIC_WEEKLY.equals(watched)
                && fiveHour.remainingPercent() <= threshold;
        boolean weeklyTriggered = weekly != null
                && !NowBarAutoStart.METRIC_FIVE_HOUR.equals(watched)
                && weekly.remainingPercent() <= threshold;
        if (fiveTriggered && weeklyTriggered) {
            return fiveHour.remainingPercent() <= weekly.remainingPercent()
                    ? FIVE_HOUR : WEEKLY;
        }
        if (fiveTriggered) return FIVE_HOUR;
        if (weeklyTriggered) return WEEKLY;
        return null;
    }

    /** Picks the window with the lower remaining percentage among those present. */
    public static String lowerRemainingFocus(UsageWindow fiveHour, UsageWindow weekly) {
        if (fiveHour != null && weekly != null) {
            return fiveHour.remainingPercent() <= weekly.remainingPercent()
                    ? FIVE_HOUR : WEEKLY;
        }
        if (fiveHour != null) return FIVE_HOUR;
        if (weekly != null) return WEEKLY;
        return null;
    }

    /**
     * Resolves which metric's remaining % should drive the Now Bar progress/pill.
     *
     * @param lockedFocusMetric focus captured when the monitor started (AUTO only);
     *        ignored for explicit five-hour / weekly modes
     */
    public static String resolveFocus(String mode, UsageWindow fiveHour, UsageWindow weekly,
            String lockedFocusMetric) {
        String normalized = normalize(mode);
        if (FIVE_HOUR.equals(normalized)) {
            if (fiveHour != null) return FIVE_HOUR;
            if (weekly != null) return WEEKLY;
            return null;
        }
        if (WEEKLY.equals(normalized)) {
            if (weekly != null) return WEEKLY;
            if (fiveHour != null) return FIVE_HOUR;
            return null;
        }
        String locked = normalizeFocusMetric(lockedFocusMetric);
        if (FIVE_HOUR.equals(locked) && fiveHour != null) return FIVE_HOUR;
        if (WEEKLY.equals(locked) && weekly != null) return WEEKLY;
        return lowerRemainingFocus(fiveHour, weekly);
    }

    public static UsageWindow selectWindow(String focus, UsageWindow fiveHour,
            UsageWindow weekly) {
        if (WEEKLY.equals(focus)) {
            return weekly != null ? weekly : fiveHour;
        }
        if (FIVE_HOUR.equals(focus)) {
            return fiveHour != null ? fiveHour : weekly;
        }
        return fiveHour != null ? fiveHour : weekly;
    }

    public static boolean isWeeklyFocus(String focus) {
        return WEEKLY.equals(normalizeFocusMetric(focus));
    }

    /**
     * Resolves focus when the user changes the percentage preference mid-session.
     * Explicit modes ignore the session auto-start trigger; AUTO restores it so
     * cycling AUTO → weekly → AUTO keeps the original trigger lock.
     */
    public static String focusForSettingsChange(String mode, UsageWindow fiveHour,
            UsageWindow weekly, String sessionAutoTriggerFocus) {
        String normalized = normalize(mode);
        if (!AUTO.equals(normalized)) {
            return resolveFocus(normalized, fiveHour, weekly, null);
        }
        return resolveFocus(AUTO, fiveHour, weekly, sessionAutoTriggerFocus);
    }
}
