package dev.bennett.codexmeter;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;

/** Rebinds widget components and RemoteViews after APK replacement or restore. */
public final class WidgetUpgradeRepair {
    private static final String PREFS = "codex_meter_migrations_v1";
    private static final String KEY_REPAIRED_VERSION = "widget_repaired_version";

    private WidgetUpgradeRepair() {
    }

    public static void runIfNeeded(Context context) {
        Context app = application(context);
        long version = versionCode(app);
        long repaired = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_REPAIRED_VERSION, -1L);
        if (version <= 0L || repaired != version) {
            repair(app, version);
        }
    }

    public static void afterPackageReplaced(Context context) {
        Context app = application(context);
        repair(app, versionCode(app));
        clearVerifiedDownloads(app);
    }

    private static void repair(Context context, long version) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context, CodexUsageWidget.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException ignored) {
        }
        SamsungLockWidgetSupport.enableAllProviders(context);
        WidgetRenderer.updateAll(context);
        if (version > 0L) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_REPAIRED_VERSION, version).commit();
        }
    }

    private static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception exception) {
            return 0L;
        }
    }

    private static void clearVerifiedDownloads(Context context) {
        File directory = new File(context.getCacheDir(), "verified-updates");
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file != null) {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
