package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;

/* JADX INFO: loaded from: classes.dex */
public final class ResetAlertPreferences {
    private static final String KEY_EXTERNAL_RESET_ENABLED = "external_reset_enabled";
    private static final String KEY_EXTERNAL_RESET_STYLE = "external_reset_style";
    private static final String KEY_METRIC = "metric";
    private static final String KEY_STYLE = "style";
    private static final String KEY_THRESHOLD = "threshold";
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";
    private static final String PREFS = "codex_meter_reset_alerts_v1";
    public static final String EXTERNAL_STYLE_CELEBRATION = "celebration";
    public static final String EXTERNAL_STYLE_NOTIFICATION = "notification";
    public static final String EXTERNAL_STYLE_SILENT = "silent";
    public static final String STYLE_ALARM = "alarm";
    public static final String STYLE_NOTIFICATION = "notification";
    public static final String STYLE_OFF = "off";
    public static final String STYLE_SILENT = "silent";

    private ResetAlertPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    public static String getStyle(Context context) {
        String string = prefs(context).getString(KEY_STYLE, STYLE_OFF);
        return (STYLE_SILENT.equals(string) || STYLE_NOTIFICATION.equals(string) || STYLE_ALARM.equals(string)) ? string : STYLE_OFF;
    }

    public static String getMetric(Context context) {
        String string = prefs(context).getString(KEY_METRIC, "both");
        return ("five_hour".equals(string) || "weekly".equals(string)) ? string : "both";
    }

    public static int getThreshold(Context context) {
        int i = prefs(context).getInt(KEY_THRESHOLD, 25);
        if (isValidThreshold(i)) {
            return i;
        }
        return 25;
    }

    public static boolean externalResetEnabled(Context context) {
        return prefs(context).getBoolean(KEY_EXTERNAL_RESET_ENABLED, true);
    }

    public static String getExternalResetStyle(Context context) {
        String style = prefs(context).getString(KEY_EXTERNAL_RESET_STYLE,
                EXTERNAL_STYLE_CELEBRATION);
        return isValidExternalStyle(style) ? style : EXTERNAL_STYLE_CELEBRATION;
    }

    public static void setExternalResetEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_RESET_ENABLED, enabled).apply();
    }

    public static void setExternalResetStyle(Context context, String style) {
        prefs(context).edit().putString(KEY_EXTERNAL_RESET_STYLE,
                isValidExternalStyle(style) ? style : EXTERNAL_STYLE_CELEBRATION).apply();
    }

    public static void save(Context context, String str, String str2, int i) {
        if (!STYLE_SILENT.equals(str) && !STYLE_NOTIFICATION.equals(str) && !STYLE_ALARM.equals(str)) {
            str = STYLE_OFF;
        }
        if (!"five_hour".equals(str2) && !"weekly".equals(str2)) {
            str2 = "both";
        }
        if (!isValidThreshold(i)) {
            i = 25;
        }
        prefs(context).edit().putString(KEY_STYLE, str).putString(KEY_METRIC, str2).putInt(KEY_THRESHOLD, i).apply();
    }

    public static boolean enabled(Context context) {
        return !STYLE_OFF.equals(getStyle(context));
    }

    private static boolean isValidThreshold(int i) {
        return i == 10 || i == 25 || i == 50 || i == 75 || i == 100;
    }

    private static boolean isValidExternalStyle(String style) {
        return EXTERNAL_STYLE_CELEBRATION.equals(style)
                || EXTERNAL_STYLE_NOTIFICATION.equals(style)
                || EXTERNAL_STYLE_SILENT.equals(style);
    }
}
