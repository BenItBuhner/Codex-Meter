package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class AppPreferences {
    private static final String KEY_APP_STYLE = "app_surface_style";
    private static final String KEY_APP_THEME = "app_theme";
    private static final String KEY_ERROR = "last_error";
    private static final String KEY_ERROR_AT = "last_error_at";
    private static final String KEY_OAUTH_PENDING = "oauth_pending";
    private static final String KEY_OAUTH_STARTED_AT = "oauth_started_at";
    private static final String KEY_OAUTH_URL = "oauth_url";
    private static final String KEY_REFRESH_MINUTES = "refresh_minutes";
    private static final String KEY_REFRESH_ON_LAUNCH = "refresh_on_launch";
    private static final String KEY_RESET_CREDITS = "reset_credits_snapshot";
    private static final String KEY_RESET_ERROR = "reset_credits_error";
    private static final String KEY_RESET_ERROR_AT = "reset_credits_error_at";
    private static final String KEY_SCHEDULER_ERROR = "scheduler_error";
    private static final String KEY_SNAPSHOT = "last_snapshot";
    private static final long OAUTH_STALE_AFTER_MS = 720000;
    private static final String PREFS = "codex_meter_settings_v1";

    private AppPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    public static boolean saveSnapshot(Context context, UsageSnapshot usageSnapshot) {
        if (usageSnapshot == null) {
            return false;
        }
        try {
            return prefs(context).edit().putString(KEY_SNAPSHOT, usageSnapshot.toJson().toString()).remove(KEY_ERROR).remove(KEY_ERROR_AT).commit();
        } catch (Exception e) {
            setLastError(context, "Could not cache the latest usage response.");
            return false;
        }
    }

    public static UsageSnapshot loadSnapshot(Context context) {
        String string = prefs(context).getString(KEY_SNAPSHOT, null);
        if (string == null || string.isEmpty()) {
            return null;
        }
        try {
            return UsageSnapshot.fromJson(new JSONObject(string));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearSnapshot(Context context) {
        prefs(context).edit().remove(KEY_SNAPSHOT).remove(KEY_ERROR).remove(KEY_ERROR_AT).remove(KEY_RESET_CREDITS).remove(KEY_RESET_ERROR).remove(KEY_RESET_ERROR_AT).apply();
        ResetNotificationManager.clearState(context);
    }

    public static void setLastError(Context context, String str) {
        if (str == null || str.trim().isEmpty()) {
            clearLastError(context);
        } else {
            prefs(context).edit().putString(KEY_ERROR, trim(str, "Refresh failed.")).putLong(KEY_ERROR_AT, System.currentTimeMillis()).apply();
        }
    }

    public static void clearLastError(Context context) {
        prefs(context).edit().remove(KEY_ERROR).remove(KEY_ERROR_AT).apply();
    }

    public static String getLastError(Context context) {
        return prefs(context).getString(KEY_ERROR, "");
    }

    public static String getVisibleRefreshError(Context context) {
        String lastError = getLastError(context);
        if (lastError.isEmpty()) {
            return "";
        }
        UsageSnapshot usageSnapshotLoadSnapshot = loadSnapshot(context);
        if (usageSnapshotLoadSnapshot != null) {
            long j = prefs(context).getLong(KEY_ERROR_AT, 0L);
            if (j <= 0 || j > usageSnapshotLoadSnapshot.fetchedAtMillis) {
                return Math.max(0L, System.currentTimeMillis() - usageSnapshotLoadSnapshot.fetchedAtMillis) < 900000 ? "" : lastError;
            }
            clearLastError(context);
            return "";
        }
        return lastError;
    }

    public static boolean saveResetCredits(Context context, ResetCreditsSnapshot resetCreditsSnapshot) {
        if (resetCreditsSnapshot == null) {
            return false;
        }
        try {
            return prefs(context).edit().putString(KEY_RESET_CREDITS, resetCreditsSnapshot.toJson().toString()).remove(KEY_RESET_ERROR).remove(KEY_RESET_ERROR_AT).commit();
        } catch (Exception e) {
            setResetCreditsError(context, "Could not cache Codex reset credits.");
            return false;
        }
    }

    public static ResetCreditsSnapshot loadResetCredits(Context context) {
        ResetCreditsSnapshot resetCreditsSnapshotFromJson = null;
        String string = prefs(context).getString(KEY_RESET_CREDITS, null);
        if (string != null && !string.isEmpty()) {
            try {
                resetCreditsSnapshotFromJson = ResetCreditsSnapshot.fromJson(new JSONObject(string));
            } catch (Exception e) {
            }
        }
        UsageSnapshot usageSnapshotLoadSnapshot = loadSnapshot(context);
        if (usageSnapshotLoadSnapshot != null && usageSnapshotLoadSnapshot.resetCreditsAvailable >= 0) {
            if (resetCreditsSnapshotFromJson == null) {
                return ResetCreditsSnapshot.summary(usageSnapshotLoadSnapshot.resetCreditsAvailable, usageSnapshotLoadSnapshot.fetchedAtMillis);
            }
            if (usageSnapshotLoadSnapshot.fetchedAtMillis > resetCreditsSnapshotFromJson.fetchedAtMillis && usageSnapshotLoadSnapshot.resetCreditsAvailable != resetCreditsSnapshotFromJson.availableCount) {
                return ResetCreditsSnapshot.summary(usageSnapshotLoadSnapshot.resetCreditsAvailable, usageSnapshotLoadSnapshot.fetchedAtMillis);
            }
            return resetCreditsSnapshotFromJson;
        }
        return resetCreditsSnapshotFromJson;
    }

    public static void setResetCreditsError(Context context, String str) {
        if (str == null || str.trim().isEmpty()) {
            clearResetCreditsError(context);
        } else {
            prefs(context).edit().putString(KEY_RESET_ERROR, trim(str, "Reset-credit refresh failed.")).putLong(KEY_RESET_ERROR_AT, System.currentTimeMillis()).apply();
        }
    }

    public static void clearResetCreditsError(Context context) {
        prefs(context).edit().remove(KEY_RESET_ERROR).remove(KEY_RESET_ERROR_AT).apply();
    }

    public static String getResetCreditsError(Context context) {
        return prefs(context).getString(KEY_RESET_ERROR, "");
    }

    public static String getVisibleResetCreditsError(Context context) {
        String resetCreditsError = getResetCreditsError(context);
        if (resetCreditsError.isEmpty()) {
            return "";
        }
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = loadResetCredits(context);
        if (resetCreditsSnapshotLoadResetCredits != null) {
            long j = prefs(context).getLong(KEY_RESET_ERROR_AT, 0L);
            if (j <= 0 || j > resetCreditsSnapshotLoadResetCredits.fetchedAtMillis) {
                return Math.max(0L, System.currentTimeMillis() - resetCreditsSnapshotLoadResetCredits.fetchedAtMillis) < 1800000 ? "" : resetCreditsError;
            }
            clearResetCreditsError(context);
            return "";
        }
        return resetCreditsError;
    }

    public static void setSchedulerError(Context context, String str) {
        if (str == null || str.trim().isEmpty()) {
            prefs(context).edit().remove(KEY_SCHEDULER_ERROR).apply();
        } else {
            prefs(context).edit().putString(KEY_SCHEDULER_ERROR, trim(str, "Background scheduling is unavailable.")).apply();
        }
    }

    public static String getSchedulerError(Context context) {
        return prefs(context).getString(KEY_SCHEDULER_ERROR, "");
    }

    public static int getRefreshMinutes(Context context) {
        int i = prefs(context).getInt(KEY_REFRESH_MINUTES, 30);
        if (validRefresh(i)) {
            return i;
        }
        return 30;
    }

    public static void setRefreshMinutes(Context context, int i) {
        SharedPreferences.Editor editorEdit = prefs(context).edit();
        if (!validRefresh(i)) {
            i = 30;
        }
        editorEdit.putInt(KEY_REFRESH_MINUTES, i).apply();
    }

    public static boolean getRefreshOnLaunch(Context context) {
        return prefs(context).getBoolean(KEY_REFRESH_ON_LAUNCH, true);
    }

    public static void setRefreshOnLaunch(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_REFRESH_ON_LAUNCH, enabled).apply();
    }

    private static boolean validRefresh(int i) {
        return i == 5 || i == 10 || i == 15 || i == 30 || i == 60 || i == 120;
    }

    public static String getAppTheme(Context context) {
        String string = prefs(context).getString(KEY_APP_THEME, WidgetOptions.THEME_SYSTEM);
        return (WidgetOptions.THEME_DARK.equals(string) || WidgetOptions.THEME_LIGHT.equals(string)) ? string : WidgetOptions.THEME_SYSTEM;
    }

    public static void setAppTheme(Context context, String str) {
        if (!WidgetOptions.THEME_DARK.equals(str) && !WidgetOptions.THEME_LIGHT.equals(str)) {
            str = WidgetOptions.THEME_SYSTEM;
        }
        prefs(context).edit().putString(KEY_APP_THEME, str).commit();
    }

    public static String getAppStyle(Context context) {
        return WidgetOptions.SURFACE_ONE_UI;
    }

    public static void setAppStyle(Context context, String str) {
        prefs(context).edit().putString(KEY_APP_STYLE, WidgetOptions.SURFACE_ONE_UI).apply();
    }

    public static WidgetOptions loadDefaultWidgetOptions(Context context) {
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        return batteryStyle(new WidgetOptions(sharedPreferencesPrefs.getString("default_style", WidgetOptions.STYLE_RINGS), sharedPreferencesPrefs.getString("default_density", "auto"), sharedPreferencesPrefs.getString("default_surface_style", WidgetOptions.SURFACE_ONE_UI), sharedPreferencesPrefs.getString("default_graphic_scale", "auto"), sharedPreferencesPrefs.getString("default_theme", WidgetOptions.THEME_SYSTEM), sharedPreferencesPrefs.getString("default_accent", WidgetOptions.ACCENT_BLUE), sharedPreferencesPrefs.getInt("default_opacity", 88), sharedPreferencesPrefs.getString("default_reset_mode", WidgetOptions.RESET_ABSOLUTE), sharedPreferencesPrefs.getString("default_display_mode", WidgetOptions.DISPLAY_REMAINING), "both", false, sharedPreferencesPrefs.getBoolean("default_show_plan", false), sharedPreferencesPrefs.getBoolean("default_show_updated", false), sharedPreferencesPrefs.getBoolean("default_show_refresh", true), sharedPreferencesPrefs.getBoolean("default_show_reset_credits", false), sharedPreferencesPrefs.getBoolean("default_show_reset_action", false)))
                .withPercentSymbol(sharedPreferencesPrefs.getBoolean(
                        "default_show_percent_symbol", true));
    }

    public static void saveDefaultWidgetOptions(Context context, WidgetOptions widgetOptions) {
        prefs(context).edit().putString("default_style", widgetOptions.layout).putString("default_layout", widgetOptions.layout).putString("default_density", widgetOptions.density).putString("default_surface_style", widgetOptions.surfaceStyle).putString("default_graphic_scale", widgetOptions.graphicScale).putString("default_theme", widgetOptions.theme).putString("default_accent", widgetOptions.accent).putInt("default_opacity", widgetOptions.opacity).putString("default_reset_mode", widgetOptions.resetMode).putString("default_display_mode", widgetOptions.displayMode).putString("default_metric_mode", widgetOptions.metricMode).putBoolean("default_show_title", widgetOptions.showTitle).putBoolean("default_show_plan", widgetOptions.showPlan).putBoolean("default_show_updated", widgetOptions.showUpdated).putBoolean("default_show_refresh", widgetOptions.showRefresh).putBoolean("default_show_reset_credits", widgetOptions.showResetCredits).putBoolean("default_show_reset_action", widgetOptions.showResetAction).putBoolean("default_show_percent_symbol", widgetOptions.showPercentSymbol).apply();
    }

    public static WidgetOptions loadWidgetOptions(Context context, int i) {
        if (i == 0) {
            return loadDefaultWidgetOptions(context);
        }
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        WidgetOptions widgetOptionsLoadDefaultWidgetOptions = loadDefaultWidgetOptions(context);
        String str = "widget_" + i + "_";
        return batteryStyle(new WidgetOptions(sharedPreferencesPrefs.getString(str + "style", widgetOptionsLoadDefaultWidgetOptions.layout), sharedPreferencesPrefs.getString(str + "density", widgetOptionsLoadDefaultWidgetOptions.density), sharedPreferencesPrefs.getString(str + "surface_style", widgetOptionsLoadDefaultWidgetOptions.surfaceStyle), sharedPreferencesPrefs.getString(str + "graphic_scale", widgetOptionsLoadDefaultWidgetOptions.graphicScale), sharedPreferencesPrefs.getString(str + "theme", widgetOptionsLoadDefaultWidgetOptions.theme), sharedPreferencesPrefs.getString(str + "accent", widgetOptionsLoadDefaultWidgetOptions.accent), sharedPreferencesPrefs.getInt(str + "opacity", widgetOptionsLoadDefaultWidgetOptions.opacity), sharedPreferencesPrefs.getString(str + "reset_mode", widgetOptionsLoadDefaultWidgetOptions.resetMode), sharedPreferencesPrefs.getString(str + "display_mode", widgetOptionsLoadDefaultWidgetOptions.displayMode), "both", false, sharedPreferencesPrefs.getBoolean(str + "show_plan", widgetOptionsLoadDefaultWidgetOptions.showPlan), sharedPreferencesPrefs.getBoolean(str + "show_updated", widgetOptionsLoadDefaultWidgetOptions.showUpdated), sharedPreferencesPrefs.getBoolean(str + "show_refresh", widgetOptionsLoadDefaultWidgetOptions.showRefresh), sharedPreferencesPrefs.getBoolean(str + "show_reset_credits", widgetOptionsLoadDefaultWidgetOptions.showResetCredits), sharedPreferencesPrefs.getBoolean(str + "show_reset_action", widgetOptionsLoadDefaultWidgetOptions.showResetAction)))
                .withPercentSymbol(sharedPreferencesPrefs.getBoolean(str + "show_percent_symbol",
                        widgetOptionsLoadDefaultWidgetOptions.showPercentSymbol));
    }

    public static String getWidgetTapAction(Context context, int appWidgetId) {
        if (appWidgetId == 0) {
            return WidgetOptions.TAP_OPEN_APP;
        }
        return WidgetOptions.normalizeTapAction(
                prefs(context).getString("widget_" + appWidgetId + "_tap_action",
                        WidgetOptions.TAP_OPEN_APP));
    }

    public static void saveWidgetTapAction(Context context, int appWidgetId, String action) {
        if (appWidgetId != 0) {
            prefs(context).edit().putString("widget_" + appWidgetId + "_tap_action",
                    WidgetOptions.normalizeTapAction(action)).apply();
        }
    }

    private static WidgetOptions batteryStyle(WidgetOptions options) {
        return new WidgetOptions(WidgetOptions.STYLE_RINGS, WidgetOptions.DENSITY_AUTO,
                WidgetOptions.SURFACE_ONE_UI, "auto", options.theme, options.accent,
                options.opacity, WidgetOptions.RESET_HIDDEN, options.displayMode, "both",
                false, false, false, false, false, false);
    }

    public static void saveWidgetOptions(Context context, int i, WidgetOptions widgetOptions) {
        String str = "widget_" + i + "_";
        prefs(context).edit().putString(str + "style", widgetOptions.layout).putString(str + "layout", widgetOptions.layout).putString(str + "density", widgetOptions.density).putString(str + "surface_style", widgetOptions.surfaceStyle).putString(str + "graphic_scale", widgetOptions.graphicScale).putString(str + "theme", widgetOptions.theme).putString(str + "accent", widgetOptions.accent).putInt(str + "opacity", widgetOptions.opacity).putString(str + "reset_mode", widgetOptions.resetMode).putString(str + "display_mode", widgetOptions.displayMode).putString(str + "metric_mode", widgetOptions.metricMode).putBoolean(str + "show_title", widgetOptions.showTitle).putBoolean(str + "show_plan", widgetOptions.showPlan).putBoolean(str + "show_updated", widgetOptions.showUpdated).putBoolean(str + "show_refresh", widgetOptions.showRefresh).putBoolean(str + "show_reset_credits", widgetOptions.showResetCredits).putBoolean(str + "show_reset_action", widgetOptions.showResetAction).putBoolean(str + "show_percent_symbol", widgetOptions.showPercentSymbol).apply();
    }

    public static void deleteWidgetOptions(Context context, int i) {
        String str = "widget_" + i + "_";
        SharedPreferences.Editor editorEdit = prefs(context).edit();
        for (String str2 : new String[]{"style", "layout", "density", "surface_style", "graphic_scale", "theme", "accent", "opacity", "reset_mode", "display_mode", "metric_mode", "show_title", "show_plan", "show_updated", "show_refresh", "show_reset_credits", "show_reset_action", "show_percent_symbol", "tap_action"}) {
            editorEdit.remove(str + str2);
        }
        editorEdit.apply();
    }

    public static LockWidgetOptions loadLockWidgetOptions(Context context, int i) {
        if (i == 0) {
            return LockWidgetOptions.defaults();
        }
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        String str = "lock_widget_" + i + "_";
        return new LockWidgetOptions(sharedPreferencesPrefs.getString(str + "metric_mode", "both"), sharedPreferencesPrefs.getBoolean(str + "show_reset_credits", false), sharedPreferencesPrefs.getBoolean(str + "show_reset_action", false), sharedPreferencesPrefs.getBoolean(str + "show_countdown", true));
    }

    public static void saveLockWidgetOptions(Context context, int i, LockWidgetOptions lockWidgetOptions) {
        if (i != 0 && lockWidgetOptions != null) {
            String str = "lock_widget_" + i + "_";
            prefs(context).edit().putString(str + "metric_mode", lockWidgetOptions.metricMode).putBoolean(str + "show_reset_credits", lockWidgetOptions.showResetCredits).putBoolean(str + "show_reset_action", lockWidgetOptions.showResetAction).putBoolean(str + "show_countdown", lockWidgetOptions.showCountdown).apply();
        }
    }

    public static void deleteLockWidgetOptions(Context context, int i) {
        String str = "lock_widget_" + i + "_";
        prefs(context).edit().remove(str + "metric_mode").remove(str + "show_reset_credits").remove(str + "show_reset_action").remove(str + "show_countdown").apply();
    }

    public static void setOAuthPending(Context context, boolean z, String str) {
        SharedPreferences.Editor editorPutBoolean = prefs(context).edit().putBoolean(KEY_OAUTH_PENDING, z);
        if (str == null) {
            str = "";
        }
        SharedPreferences.Editor editorPutString = editorPutBoolean.putString(KEY_OAUTH_URL, str);
        if (z) {
            editorPutString.putLong(KEY_OAUTH_STARTED_AT, System.currentTimeMillis());
        } else {
            editorPutString.remove(KEY_OAUTH_STARTED_AT);
        }
        editorPutString.apply();
    }

    public static boolean isOAuthPending(Context context) {
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        if (!sharedPreferencesPrefs.getBoolean(KEY_OAUTH_PENDING, false)) {
            return false;
        }
        long j = sharedPreferencesPrefs.getLong(KEY_OAUTH_STARTED_AT, 0L);
        if (j <= 0 || System.currentTimeMillis() - j > OAUTH_STALE_AFTER_MS) {
            setOAuthPending(context, false, "");
            return false;
        }
        return true;
    }

    public static String getOAuthUrl(Context context) {
        return isOAuthPending(context) ? prefs(context).getString(KEY_OAUTH_URL, "") : "";
    }

    private static String trim(String str, String str2) {
        if (str != null && !str.trim().isEmpty()) {
            str2 = str.trim();
        }
        return str2.length() > 240 ? str2.substring(0, 240) : str2;
    }
}
