package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/**
 * Glanceable Now Bar / Live Update copy. When a usage window is fully exhausted
 * (0% remaining), percentage text is replaced with how long until that window's
 * natural reset, using days and/or hours (and minutes under an hour).
 */
public final class NowBarCopy {
    private NowBarCopy() {
    }

    /**
     * Compact critical / chip percentage for the focused window. Exhausted windows
     * show a compact reset countdown instead of {@code 0%}.
     */
    public static String focusCriticalText(boolean weeklyFocus, UsageWindow window,
            long observedAtMillis, long nowMillis) {
        String prefix = weeklyFocus ? "W " : "";
        if (window == null) {
            return prefix + "—";
        }
        int remaining = window.remainingPercent();
        if (remaining > 0) {
            return prefix + remaining + "%";
        }
        String duration = resetDurationText(window, observedAtMillis, nowMillis);
        return duration == null ? prefix + "0%" : prefix + duration;
    }

    /**
     * Samsung expanded-chip label: {@code Codex · 5-hour 12%} or, when exhausted,
     * {@code Codex · Weekly 2d 4h}.
     */
    public static String chipExpandedText(boolean weeklyFocus, UsageWindow window,
            long observedAtMillis, long nowMillis) {
        String focusLabel = weeklyFocus ? "Weekly " : "5-hour ";
        if (window == null) {
            return "Codex · " + focusLabel + "unavailable";
        }
        int remaining = window.remainingPercent();
        if (remaining > 0) {
            return "Codex · " + focusLabel + remaining + "%";
        }
        String duration = resetDurationText(window, observedAtMillis, nowMillis);
        return duration == null
                ? "Codex · " + focusLabel + "0%"
                : "Codex · " + focusLabel + duration;
    }

    /**
     * Notification body line for one window: {@code 5-hour: 12% left} or, when
     * exhausted, {@code Weekly: resets in 2d 4h}.
     */
    public static String limitText(String label, UsageWindow window, long observedAtMillis,
            long nowMillis) {
        if (window == null) {
            return label + ": unavailable";
        }
        int remaining = window.remainingPercent();
        if (remaining > 0) {
            return label + ": " + remaining + "% left";
        }
        String duration = resetDurationText(window, observedAtMillis, nowMillis);
        return duration == null
                ? label + ": 0% left"
                : label + ": resets in " + duration;
    }

    /**
     * Compact Wear body fragment: {@code 5h 12%} or {@code Week resets 2d 4h}.
     */
    public static String wearLimitText(String label, UsageWindow window, long observedAtMillis,
            long nowMillis) {
        if (window == null) {
            return label + " --";
        }
        int remaining = window.remainingPercent();
        if (remaining > 0) {
            return label + " " + remaining + "%";
        }
        String duration = resetDurationText(window, observedAtMillis, nowMillis);
        return duration == null
                ? label + " 0%"
                : label + " resets " + duration;
    }

    /**
     * Days and/or hours until {@code window}'s natural reset. Returns null when the
     * reset time is unknown or not in the future.
     */
    public static String resetDurationText(UsageWindow window, long observedAtMillis,
            long nowMillis) {
        if (window == null) return null;
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= nowMillis) return null;
        return compactDuration(resetAt - nowMillis);
    }

    /**
     * Compact remaining duration: {@code 2d 4h}, {@code 2d}, {@code 4h 20m}, {@code 4h},
     * or {@code 12m}. Prefer days and/or hours when those units are needed.
     */
    public static String compactDuration(long durationMillis) {
        long minutes = Math.max(1L, TimeUnit.MILLISECONDS.toMinutes(Math.max(0L, durationMillis)));
        long days = minutes / TimeUnit.DAYS.toMinutes(1);
        long hours = (minutes % TimeUnit.DAYS.toMinutes(1)) / TimeUnit.HOURS.toMinutes(1);
        long remainingMinutes = minutes % TimeUnit.HOURS.toMinutes(1);
        if (days > 0L) {
            return hours > 0L ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0L) {
            return remainingMinutes > 0L ? hours + "h " + remainingMinutes + "m" : hours + "h";
        }
        return minutes + "m";
    }
}
