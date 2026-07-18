package dev.bennett.codexmeter;

import android.content.Context;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes.dex */
public final class UsageFormat {
    private UsageFormat() {
    }

    public static String planLabel(String str) {
        if (str == null || str.trim().isEmpty()) {
            return "";
        }
        String normalized = str.trim().toLowerCase(Locale.US).replace("_", "").replace("-", "");
        switch (normalized) {
            case "free": return "Free";
            case "go": return "Go";
            case "plus": return "Plus";
            case "prolite":
            case "pro5x": return "Pro 5x";
            case "pro":
            case "pro20x": return "Pro 20x";
            default: return "";
        }
    }

    public static String percent(UsageWindow usageWindow, String str, boolean z) {
        if (usageWindow == null) {
            return z ? "—" : "Unavailable";
        }
        boolean zEquals = WidgetOptions.DISPLAY_USED.equals(str);
        int iRemainingPercent = zEquals ? usageWindow.usedPercent : usageWindow.remainingPercent();
        if (z) {
            return iRemainingPercent + "%";
        }
        return iRemainingPercent + "% " + (zEquals ? WidgetOptions.DISPLAY_USED : "left");
    }

    public static String reset(Context context, UsageWindow usageWindow, String str, long j) {
        return reset(context, usageWindow, str, j, j);
    }

    public static String reset(Context context, UsageWindow usageWindow, String str,
            long observedAtMillis, long nowMillis) {
        if (usageWindow == null || WidgetOptions.RESET_HIDDEN.equals(str)
                || !usageWindow.showsResetCountdown()) {
            return "";
        }
        long jResetAtMillis = usageWindow.effectiveResetAtMillis(observedAtMillis);
        if (jResetAtMillis <= 0) {
            return "Reset time unavailable";
        }
        String strAbsolute = absolute(context, jResetAtMillis, nowMillis);
        String strRelative = relative(jResetAtMillis, nowMillis);
        if (WidgetOptions.RESET_RELATIVE.equals(str)) {
            return "Resets " + strRelative;
        }
        return "both".equals(str) ? "Resets " + strAbsolute + " (" + strRelative + ")" : "Resets " + strAbsolute;
    }

    public static String estimatedRemaining(UsagePace.Assessment assessment) {
        if (assessment == null || !assessment.available) {
            return "";
        }
        if (assessment.estimatedRemainingMillis <= 0L) {
            return "Est. depleted";
        }
        return "Est. " + compactDuration(assessment.estimatedRemainingMillis);
    }

    static String compactDuration(long millis) {
        long minutes = Math.max(1L, TimeUnit.MILLISECONDS.toMinutes(Math.max(0L, millis)));
        long days = minutes / 1440L;
        long hours = (minutes % 1440L) / 60L;
        long remainingMinutes = minutes % 60L;
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + remainingMinutes + "m";
        }
        return minutes + "m";
    }

    public static String absolute(Context context, long j, long j2) {
        String str;
        boolean zIs24HourFormat = DateFormat.is24HourFormat(context);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(j);
        Calendar calendar2 = Calendar.getInstance();
        calendar2.setTimeInMillis(j2);
        Calendar calendar3 = (Calendar) calendar2.clone();
        calendar3.add(6, 1);
        if (sameDay(calendar, calendar2)) {
            str = zIs24HourFormat ? "'today at' HH:mm" : "'today at' h:mm a";
        } else if (sameDay(calendar, calendar3)) {
            str = zIs24HourFormat ? "'tomorrow at' HH:mm" : "'tomorrow at' h:mm a";
        } else {
            str = zIs24HourFormat ? "EEE, MMM d 'at' HH:mm" : "EEE, MMM d 'at' h:mm a";
        }
        return new SimpleDateFormat(str, Locale.getDefault()).format(new Date(j));
    }

    private static boolean sameDay(Calendar calendar, Calendar calendar2) {
        return calendar.get(0) == calendar2.get(0) && calendar.get(1) == calendar2.get(1) && calendar.get(6) == calendar2.get(6);
    }

    public static String relative(long j, long j2) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(Math.max(0L, j - j2));
        long j3 = minutes / 1440;
        long j4 = (minutes % 1440) / 60;
        long j5 = minutes % 60;
        if (j3 > 0) {
            return "in " + j3 + "d " + j4 + "h";
        }
        if (j4 > 0) {
            return "in " + j4 + "h " + j5 + "m";
        }
        return minutes > 0 ? "in " + minutes + "m" : "now";
    }

    public static String updated(long j, long j2) {
        if (j <= 0) {
            return "Not updated yet";
        }
        long jMax = Math.max(0L, TimeUnit.MILLISECONDS.toMinutes(j2 - j));
        if (jMax < 1) {
            return "Updated just now";
        }
        if (jMax < 60) {
            return "Updated " + jMax + "m ago";
        }
        long j3 = jMax / 60;
        return j3 < 24 ? "Updated " + j3 + "h ago" : "Updated " + (j3 / 24) + "d ago";
    }
}
