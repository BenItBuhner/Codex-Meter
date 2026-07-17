package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User settings for automatically starting the live usage monitor when allowance
 * remaining drops to a configured threshold — same metric/threshold pattern as
 * {@link ResetAlertPreferences} low-usage alerts.
 */
public final class NowBarPreferences {
    private static final String KEY_AUTO_ENABLED = "auto_enabled";
    private static final String KEY_DISPLAY_MODE = "display_mode";
    private static final String KEY_METRIC = "metric";
    private static final String KEY_PERCENT_MODE = "percent_mode";
    private static final String KEY_SUPPRESS_UNTIL = "suppress_until";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String PREFS = "codex_meter_now_bar_prefs_v1";

    private NowBarPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAutoStartEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static String getDisplayMode(Context context) {
        return NowBarDisplayMode.normalize(prefs(context).getString(KEY_DISPLAY_MODE,
                NowBarDisplayMode.AUTO));
    }

    public static void setDisplayMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_DISPLAY_MODE,
                NowBarDisplayMode.normalize(mode)).apply();
    }

    public static String getPercentMode(Context context) {
        return NowBarPercentMode.normalize(prefs(context).getString(KEY_PERCENT_MODE,
                NowBarPercentMode.AUTO));
    }

    public static void setPercentMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_PERCENT_MODE,
                NowBarPercentMode.normalize(mode)).apply();
    }

    public static String getMetric(Context context) {
        return NowBarAutoStart.normalizeMetric(prefs(context).getString(KEY_METRIC,
                NowBarAutoStart.METRIC_BOTH));
    }

    public static int getThreshold(Context context) {
        return NowBarAutoStart.normalizeThreshold(prefs(context).getInt(KEY_THRESHOLD, 25));
    }

    public static void save(Context context, boolean autoEnabled, String metric, int threshold) {
        prefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, autoEnabled)
                .putString(KEY_METRIC, NowBarAutoStart.normalizeMetric(metric))
                .putInt(KEY_THRESHOLD, NowBarAutoStart.normalizeThreshold(threshold))
                .apply();
    }

    public static void setAutoStartEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply();
    }

    public static boolean meetsThreshold(Context context, UsageSnapshot snapshot) {
        if (snapshot == null) return false;
        long now = System.currentTimeMillis();
        return NowBarAutoStart.shouldStart(isAutoStartEnabled(context), getMetric(context),
                getThreshold(context),
                UsageSnapshot.currentWindow(snapshot.fiveHour, now),
                UsageSnapshot.currentWindow(snapshot.weekly, now));
    }

    public static boolean isSuppressed(Context context) {
        return prefs(context).getLong(KEY_SUPPRESS_UNTIL, 0L) > System.currentTimeMillis();
    }

    public static void markSuppressedUntil(Context context, long untilMillis) {
        if (untilMillis <= System.currentTimeMillis()) {
            clearSuppression(context);
            return;
        }
        prefs(context).edit().putLong(KEY_SUPPRESS_UNTIL, untilMillis).apply();
    }

    public static void clearSuppression(Context context) {
        prefs(context).edit().remove(KEY_SUPPRESS_UNTIL).apply();
    }
}
