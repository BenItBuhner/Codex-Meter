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
        return (str == null || str.trim().isEmpty()) ? "" : str.replace('_', ' ').toUpperCase(Locale.US);
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
        if (usageWindow == null || WidgetOptions.RESET_HIDDEN.equals(str)) {
            return "";
        }
        long jResetAtMillis = usageWindow.resetAtMillis();
        if (jResetAtMillis <= 0 && usageWindow.resetAfterSeconds > 0) {
            jResetAtMillis = (usageWindow.resetAfterSeconds * 1000) + j;
        }
        if (jResetAtMillis <= 0) {
            return "Reset time unavailable";
        }
        String strAbsolute = absolute(context, jResetAtMillis, j);
        String strRelative = relative(jResetAtMillis, j);
        if (WidgetOptions.RESET_RELATIVE.equals(str)) {
            return "Resets " + strRelative;
        }
        return "both".equals(str) ? "Resets " + strAbsolute + " (" + strRelative + ")" : "Resets " + strAbsolute;
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
