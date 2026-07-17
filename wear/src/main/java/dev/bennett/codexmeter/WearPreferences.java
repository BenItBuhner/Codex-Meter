package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import dev.bennett.codexmeter.wear.WearMonitorState;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSurfaceMode;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class WearPreferences {
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_DISPLAY_MODE = "display_mode";
    private static final String KEY_LAST_APPLIED_MONITOR_AT = "last_applied_monitor_at";
    private static final String KEY_LAST_APPLIED_SETTINGS_AT = "last_applied_settings_at";
    private static final String KEY_LAST_APPLIED_USAGE_AT = "last_applied_usage_at";
    private static final String KEY_LAST_LOCAL_SETTINGS_AT = "last_local_settings_at";
    private static final String KEY_METRIC = "metric";
    private static final String KEY_MONITOR_ACTIVE = "monitor_active";
    private static final String KEY_MONITOR_FOCUS = "monitor_focus";
    private static final String KEY_MONITOR_POSTED_MODE = "monitor_posted_mode";
    private static final String KEY_MONITOR_PREVIEW = "monitor_preview";
    private static final String KEY_MONITOR_UNTIL = "monitor_until";
    private static final String KEY_PERCENT_MODE = "percent_mode";
    private static final String KEY_REFRESH_MINUTES = "refresh_minutes";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";
    private static final String KEY_SYNCED = "synced";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String PREFS = "codex_meter_wear_v1";

    private WearPreferences() {
    }

    public static UsageSnapshot loadSnapshot(Context context) {
        String json = prefs(context).getString(KEY_SNAPSHOT_JSON, null);
        if (json == null || json.isEmpty()) return null;
        try {
            return UsageSnapshot.fromJson(new JSONObject(json));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void saveSnapshot(Context context, UsageSnapshot snapshot, long updatedAtMillis) {
        if (snapshot == null) return;
        try {
            prefs(context).edit()
                    .putString(KEY_SNAPSHOT_JSON, snapshot.toJson().toString())
                    .putLong(KEY_LAST_APPLIED_USAGE_AT, Math.max(0L, updatedAtMillis))
                    .putBoolean(KEY_SYNCED, true)
                    .putBoolean(KEY_CONNECTED, true)
                    .apply();
            WearSurfaceUpdater.requestAll(context);
        } catch (Exception ignored) {
        }
    }

    public static UsageSnapshot seedDemoSnapshotIfEmpty(Context context) {
        UsageSnapshot current = loadSnapshot(context);
        if (current != null && (current.fiveHour != null || current.weekly != null)) {
            return current;
        }
        return seedDemoSnapshot(context);
    }

    /** Always writes a local demo snapshot so Wear UI/monitor can be shown without a phone. */
    public static UsageSnapshot seedDemoSnapshot(Context context) {
        long now = System.currentTimeMillis();
        UsageSnapshot demo = new UsageSnapshot("demo", true, false,
                new UsageWindow(62, TimeUnit.HOURS.toSeconds(5),
                        TimeUnit.MINUTES.toSeconds(84),
                        (now + TimeUnit.MINUTES.toMillis(84)) / 1000L),
                new UsageWindow(41, TimeUnit.DAYS.toSeconds(7),
                        TimeUnit.DAYS.toSeconds(3),
                        (now + TimeUnit.DAYS.toMillis(3)) / 1000L),
                now);
        saveSnapshot(context, demo, now);
        return demo;
    }

    public static WearSettingsState settingsState(Context context, long updatedAtMillis,
            String sourceNode) {
        SharedPreferences prefs = prefs(context);
        return new WearSettingsState(
                prefs.getString(KEY_DISPLAY_MODE, NowBarDisplayMode.AUTO),
                prefs.getString(KEY_PERCENT_MODE, NowBarPercentMode.AUTO),
                prefs.getBoolean(KEY_AUTO_START, false),
                prefs.getString(KEY_METRIC, NowBarAutoStart.METRIC_BOTH),
                prefs.getInt(KEY_THRESHOLD, 25),
                prefs.getBoolean(KEY_MONITOR_ACTIVE, false),
                prefs.getInt(KEY_REFRESH_MINUTES, 30),
                updatedAtMillis,
                sourceNode);
    }

    public static boolean applyRemoteSettings(Context context, WearSettingsState remote) {
        if (remote == null || WearSettingsState.SOURCE_WEAR.equals(remote.sourceNode)) {
            return false;
        }
        SharedPreferences prefs = prefs(context);
        long localStamp = Math.max(prefs.getLong(KEY_LAST_LOCAL_SETTINGS_AT, 0L),
                prefs.getLong(KEY_LAST_APPLIED_SETTINGS_AT, 0L));
        if (remote.updatedAtMillis <= localStamp) {
            return false;
        }
        saveSettings(context, remote, false);
        prefs.edit()
                .putLong(KEY_LAST_APPLIED_SETTINGS_AT, remote.updatedAtMillis)
                .putBoolean(KEY_CONNECTED, true)
                .apply();
        return true;
    }

    public static void saveLocalSettings(Context context, WearSettingsState state) {
        saveSettings(context, state, true);
    }

    public static void setMonitorActive(Context context, boolean active) {
        setMonitorActive(context, active, true);
    }

    public static void setMonitorActive(Context context, boolean active, boolean localChange) {
        long now = System.currentTimeMillis();
        UsageSnapshot snapshot = loadSnapshot(context);
        long until = active && snapshot != null ? snapshot.nextResetMillis(now) : 0L;
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(KEY_MONITOR_ACTIVE, active)
                .putLong(KEY_MONITOR_UNTIL, until);
        if (localChange) {
            editor.putLong(KEY_LAST_LOCAL_SETTINGS_AT, now);
        }
        editor.apply();
        WearSurfaceUpdater.requestAll(context);
    }

    public static void applyRemoteMonitor(Context context, WearMonitorState state) {
        if (state == null) return;
        SharedPreferences prefs = prefs(context);
        if (state.updatedAtMillis <= prefs.getLong(KEY_LAST_APPLIED_MONITOR_AT, 0L)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_MONITOR_ACTIVE, state.active)
                .putBoolean(KEY_MONITOR_PREVIEW, state.preview)
                .putLong(KEY_MONITOR_UNTIL, state.untilMillis)
                .putString(KEY_MONITOR_POSTED_MODE, state.postedMode)
                .putLong(KEY_LAST_APPLIED_MONITOR_AT, state.updatedAtMillis)
                .putBoolean(KEY_CONNECTED, true);
        if (state.focusMetric == null) {
            editor.remove(KEY_MONITOR_FOCUS);
        } else {
            editor.putString(KEY_MONITOR_FOCUS, state.focusMetric);
        }
        editor.apply();
        WearSurfaceUpdater.requestAll(context);
    }

    public static WearMonitorState monitorState(Context context, long updatedAtMillis) {
        SharedPreferences prefs = prefs(context);
        return new WearMonitorState(
                prefs.getBoolean(KEY_MONITOR_ACTIVE, false),
                prefs.getBoolean(KEY_MONITOR_PREVIEW, false),
                prefs.getLong(KEY_MONITOR_UNTIL, 0L),
                prefs.getString(KEY_MONITOR_FOCUS, null),
                prefs.getString(KEY_MONITOR_POSTED_MODE, NowBarDisplayMode.AUTO),
                updatedAtMillis);
    }

    public static boolean isMonitorActive(Context context) {
        return prefs(context).getBoolean(KEY_MONITOR_ACTIVE, false)
                && prefs(context).getLong(KEY_MONITOR_UNTIL, Long.MAX_VALUE)
                > System.currentTimeMillis();
    }

    public static void markConnected(Context context, boolean connected) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, connected).apply();
    }

    public static boolean isConnected(Context context) {
        return prefs(context).getBoolean(KEY_CONNECTED, false);
    }

    public static boolean hasSyncedUsage(Context context) {
        return prefs(context).getBoolean(KEY_SYNCED, false);
    }

    public static long lastUsageAt(Context context) {
        return prefs(context).getLong(KEY_LAST_APPLIED_USAGE_AT, 0L);
    }

    public static WearSurfaceMode surfaceMode(Context context) {
        // Wear maps phone Now Bar choices to Wear-native surfaces: Samsung private
        // phone extras never render on Wear, while API 36+ can try local Live Updates.
        return WearSurfaceMode.resolve(settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR).displayMode,
                android.os.Build.VERSION.SDK_INT,
                WearOngoingMonitor.canUseLocalLiveUpdates(context));
    }

    private static void saveSettings(Context context, WearSettingsState state, boolean local) {
        long stamp = state.updatedAtMillis > 0L ? state.updatedAtMillis : System.currentTimeMillis();
        prefs(context).edit()
                .putString(KEY_DISPLAY_MODE, state.displayMode)
                .putString(KEY_PERCENT_MODE, state.percentMode)
                .putBoolean(KEY_AUTO_START, state.autoStartEnabled)
                .putString(KEY_METRIC, state.metric)
                .putInt(KEY_THRESHOLD, state.threshold)
                .putBoolean(KEY_MONITOR_ACTIVE, state.monitorActive)
                .putInt(KEY_REFRESH_MINUTES, state.refreshMinutes)
                .putLong(local ? KEY_LAST_LOCAL_SETTINGS_AT : KEY_LAST_APPLIED_SETTINGS_AT, stamp)
                .apply();
        WearSurfaceUpdater.requestAll(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
