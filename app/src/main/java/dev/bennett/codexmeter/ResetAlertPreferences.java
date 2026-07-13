package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;

/* JADX INFO: loaded from: classes.dex */
public final class ResetAlertPreferences {
    private static final String KEY_METRIC = "metric";
    private static final String KEY_RESET_CREDIT_INCREASES = "reset_credit_increases";
    private static final String KEY_STYLE = "style";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_UNEXPECTED_REFILLS = "unexpected_refills";
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";
    private static final String PREFS = "codex_meter_reset_alerts_v1";
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

    public static boolean unexpectedRefillsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_UNEXPECTED_REFILLS, true);
    }

    public static void setUnexpectedRefillsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_UNEXPECTED_REFILLS, enabled).apply();
    }

    public static boolean resetCreditIncreasesEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RESET_CREDIT_INCREASES, true);
    }

    public static void setResetCreditIncreasesEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RESET_CREDIT_INCREASES, enabled).apply();
    }

    private static boolean isValidThreshold(int i) {
        return i == 10 || i == 25 || i == 50 || i == 75 || i == 100;
    }
}
