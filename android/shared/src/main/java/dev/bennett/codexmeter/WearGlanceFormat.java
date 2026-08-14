package dev.bennett.codexmeter;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class WearGlanceFormat {
    public static final int ONE_UI_PRIMARY = 0xFF5CA9FF;
    public static final int ONE_UI_ON_PRIMARY = 0xFFFFFFFF;
    public static final int ONE_UI_ON_SURFACE = 0xFFF8F8FA;
    public static final int ONE_UI_SECONDARY_TEXT = 0xFFB7BAC2;
    public static final int ONE_UI_SURFACE = 0xFF16181C;
    public static final int ONE_UI_TRACK = 0xFF2A2C32;

    private static final long HOUR_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final long MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private WearGlanceFormat() {
    }

    /*
     * Phone Samsung Now Bar extras do NOT exist on Wear. Glanceables on Wear are the
     * Tiles carousel, Complications, and Ongoing Activity.
     *
     * Samsung publishes One UI Watch design principles, but there is no public One UI
     * Design SDK for Wear like phone oneui-design. Wear surfaces align visually with
     * OLED-dark backgrounds, high-contrast text, One UI dark #5CA9FF accents,
     * large round-friendly tap targets, glanceable single-job layouts, and subtle
     * dark cards (#16181C), and filled control surfaces (#2A2C32).
     */

    public static UsageWindow currentFiveHour(UsageSnapshot snapshot) {
        return snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.fiveHour, snapshot.fetchedAtMillis,
                        System.currentTimeMillis());
    }

    public static UsageWindow currentWeekly(UsageSnapshot snapshot) {
        return snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.weekly, snapshot.fetchedAtMillis,
                        System.currentTimeMillis());
    }

    public static UsageWindow currentMonthly(UsageSnapshot snapshot) {
        return snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.monthly, snapshot.fetchedAtMillis,
                        System.currentTimeMillis());
    }

    /** Weekly window when reported, otherwise the free-tier monthly window. */
    public static UsageWindow currentLongWindow(UsageSnapshot snapshot) {
        return snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.longWindow(), snapshot.fetchedAtMillis,
                        System.currentTimeMillis());
    }

    /** Short label ("Week"/"Month") for the long-cadence window of {@code snapshot}. */
    public static String longWindowShortLabel(UsageSnapshot snapshot) {
        return snapshot != null && snapshot.longWindowIsMonthly() ? "Month" : "Week";
    }

    /** Full label ("Weekly"/"Monthly") for the long-cadence window of {@code snapshot}. */
    public static String longWindowLabel(UsageSnapshot snapshot) {
        return snapshot != null && snapshot.longWindowIsMonthly() ? "Monthly" : "Weekly";
    }

    public static String remainingPercentText(UsageWindow window) {
        return window == null ? "--" : window.remainingPercent() + "%";
    }

    public static String remainingNumberText(UsageWindow window) {
        return window == null ? "--" : Integer.toString(window.remainingPercent());
    }

    public static int remainingPercentOrZero(UsageWindow window) {
        return window == null ? 0 : window.remainingPercent();
    }

    public static float remainingProgress(UsageWindow window) {
        return remainingPercentOrZero(window) / 100f;
    }

    public static String compactWindowLabel(UsageWindow window, String fallback) {
        if (window == null || window.windowSeconds <= 0L) return fallback;
        if (window.windowSeconds >= TimeUnit.DAYS.toSeconds(10)) return "Month";
        return window.windowSeconds >= TimeUnit.DAYS.toSeconds(1) ? "Week" : "5h";
    }

    public static String dualShortText(UsageSnapshot snapshot) {
        return remainingNumberText(currentFiveHour(snapshot)) + "\u00b7"
                + remainingNumberText(currentLongWindow(snapshot));
    }

    public static String dualLongText(UsageSnapshot snapshot) {
        return "5h " + remainingPercentText(currentFiveHour(snapshot)) + " \u00b7 "
                + longWindowShortLabel(snapshot) + " "
                + remainingPercentText(currentLongWindow(snapshot));
    }

    public static String nextResetWindowLabel(UsageSnapshot snapshot, long nowMillis) {
        if (snapshot == null) return "--";
        long next = snapshot.nextResetMillis(nowMillis);
        if (next <= nowMillis) return "--";
        UsageWindow fiveHour = UsageSnapshot.currentWindow(
                snapshot.fiveHour, snapshot.fetchedAtMillis, nowMillis);
        UsageWindow weekly = UsageSnapshot.currentWindow(
                snapshot.weekly, snapshot.fetchedAtMillis, nowMillis);
        UsageWindow monthly = UsageSnapshot.currentWindow(
                snapshot.monthly, snapshot.fetchedAtMillis, nowMillis);
        long fiveReset = resetAt(fiveHour, snapshot.fetchedAtMillis);
        long weekReset = resetAt(weekly, snapshot.fetchedAtMillis);
        long monthReset = resetAt(monthly, snapshot.fetchedAtMillis);
        if (fiveReset == next) return "5h reset";
        if (weekReset == next) return "Week reset";
        if (monthReset == next) return "Month reset";
        return "Next reset";
    }

    public static String nextResetRelativeText(UsageSnapshot snapshot, long nowMillis) {
        if (snapshot == null) return "--";
        long next = snapshot.nextResetMillis(nowMillis);
        if (next <= nowMillis) return "--";
        return durationText(next - nowMillis);
    }

    public static String nextResetLongText(UsageSnapshot snapshot, long nowMillis) {
        String relative = nextResetRelativeText(snapshot, nowMillis);
        return "--".equals(relative) ? "No reset yet" : "Resets in " + relative;
    }

    public static String monitorStatusText(boolean active) {
        return active ? "Live monitor active" : "Live monitor off";
    }

    public static boolean isStale(long updatedAtMillis, int refreshMinutes, long nowMillis) {
        if (updatedAtMillis <= 0L || updatedAtMillis > nowMillis) return true;
        long staleAfter = Math.max(TimeUnit.MINUTES.toMillis(30),
                TimeUnit.MINUTES.toMillis(Math.max(1, refreshMinutes)) * 2L);
        return nowMillis - updatedAtMillis > staleAfter;
    }

    public static String accountStatus(UsageSnapshot snapshot) {
        if (snapshot == null) return "Waiting for usage";
        if (!snapshot.allowed) return "Usage access unavailable";
        if (snapshot.limitReached) return "Limit reached";
        String plan = snapshot.planType == null ? "" : snapshot.planType.trim();
        if (plan.isEmpty()) return "Codex usage";
        return Character.toUpperCase(plan.charAt(0)) + plan.substring(1) + " plan";
    }

    public static String resetCreditsText(UsageSnapshot snapshot) {
        if (snapshot == null || snapshot.resetCreditsAvailable < 0) return "";
        int count = snapshot.resetCreditsAvailable;
        return count + (count == 1 ? " reset credit" : " reset credits");
    }

    public static String paceWarning(UsageSnapshot snapshot, boolean enabled, String sensitivity,
            long nowMillis) {
        if (!enabled) return "";
        int window = UsagePace.mostAcceleratedWindow(snapshot, nowMillis, sensitivity);
        if (window == UsagePace.WINDOW_FIVE_HOUR) return "Fast 5-hour usage";
        if (window == UsagePace.WINDOW_WEEKLY) return "Fast weekly usage";
        if (window == UsagePace.WINDOW_MONTHLY) return "Fast monthly usage";
        return "";
    }

    public static String focusSummary(UsageSnapshot snapshot) {
        UsageWindow fiveHour = currentFiveHour(snapshot);
        UsageWindow longWindow = currentLongWindow(snapshot);
        if (fiveHour == null && longWindow == null) return "No usage yet";
        return "5h " + remainingPercentText(fiveHour) + " \u00b7 "
                + longWindowShortLabel(snapshot) + " "
                + remainingPercentText(longWindow);
    }

    private static long resetAt(UsageWindow window, long observedAtMillis) {
        return window == null ? 0L : window.effectiveResetAtMillis(observedAtMillis);
    }

    private static String durationText(long durationMillis) {
        long minutes = Math.max(1L, (durationMillis + MINUTE_MILLIS - 1L) / MINUTE_MILLIS);
        long days = minutes / TimeUnit.DAYS.toMinutes(1);
        if (days > 0L) {
            long hours = (minutes % TimeUnit.DAYS.toMinutes(1)) / TimeUnit.HOURS.toMinutes(1);
            return hours > 0L ? String.format(Locale.US, "%dd %dh", days, hours)
                    : String.format(Locale.US, "%dd", days);
        }
        long hours = durationMillis / HOUR_MILLIS;
        long remainderMinutes = (durationMillis % HOUR_MILLIS + MINUTE_MILLIS - 1L)
                / MINUTE_MILLIS;
        if (hours > 0L) {
            return remainderMinutes > 0L ? String.format(Locale.US, "%dh %dm", hours,
                    remainderMinutes) : String.format(Locale.US, "%dh", hours);
        }
        return String.format(Locale.US, "%dm", minutes);
    }
}
