package dev.bennett.codexmeter;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
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
     * show a compact reset countdown instead of {@code 0%}. {@code prefix} is a short
     * window marker such as {@code "W "} (weekly), {@code "M "} (monthly), or {@code ""}.
     */
    public static String focusCriticalText(String prefix, UsageWindow window,
            long observedAtMillis, long nowMillis) {
        if (prefix == null) {
            prefix = "";
        }
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
     * {@code Codex · Weekly 2d 4h}. {@code windowLabel} names the focused window
     * ({@code "5-hour"}, {@code "Weekly"}, or {@code "Monthly"}).
     */
    public static String chipExpandedText(String windowLabel, UsageWindow window,
            long observedAtMillis, long nowMillis) {
        String focusLabel = (windowLabel == null || windowLabel.isEmpty()
                ? "5-hour" : windowLabel) + " ";
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
     * Live notification title for the focused window: {@code Weekly · 48% left}, or
     * {@code Weekly · used up} once nothing is left.
     */
    public static String headline(String label, UsageWindow window) {
        if (window == null) {
            return label + " · unavailable";
        }
        int remaining = window.remainingPercent();
        return remaining > 0 ? label + " · " + remaining + "% left" : label + " · used up";
    }

    /**
     * When the window resets, as a wall-clock time that stays true between refreshes:
     * {@code Resets 4:14 PM}, {@code Resets tomorrow 9:00 AM}, {@code Resets Fri 2:37 PM}, or
     * {@code Resets Nov 3} when it is a week or more away. Null when the reset is unknown.
     */
    public static String resetClockText(UsageWindow window, long observedAtMillis,
            long nowMillis, boolean is24Hour, TimeZone zone, Locale locale) {
        if (window == null) return null;
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= nowMillis) return null;
        TimeZone tz = zone == null ? TimeZone.getDefault() : zone;
        Locale loc = locale == null ? Locale.getDefault() : locale;
        Calendar reset = Calendar.getInstance(tz, loc);
        reset.setTimeInMillis(resetAt);
        Calendar today = Calendar.getInstance(tz, loc);
        today.setTimeInMillis(nowMillis);
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        Calendar resetDay = (Calendar) reset.clone();
        resetDay.set(Calendar.HOUR_OF_DAY, 0);
        resetDay.set(Calendar.MINUTE, 0);
        resetDay.set(Calendar.SECOND, 0);
        resetDay.set(Calendar.MILLISECOND, 0);
        long days = Math.round((resetDay.getTimeInMillis() - today.getTimeInMillis())
                / (double) TimeUnit.DAYS.toMillis(1));
        String clock = is24Hour ? "HH:mm" : "h:mm a";
        String pattern;
        if (days <= 0L) {
            pattern = clock;
        } else if (days == 1L) {
            pattern = "'tomorrow' " + clock;
        } else if (days < 7L) {
            pattern = "EEE " + clock;
        } else {
            pattern = "MMM d";
        }
        SimpleDateFormat format = new SimpleDateFormat(pattern, loc);
        format.setTimeZone(tz);
        return "Resets " + format.format(new Date(resetAt));
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
