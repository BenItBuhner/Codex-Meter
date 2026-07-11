package dev.bennett.codexmeter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/* JADX INFO: loaded from: classes.dex */
public final class ResetAlertScheduler {
    private static final long DELIVERY_GRACE_MS = 3000;
    static final String EXTRA_METRIC = "metric";
    static final String EXTRA_RESET_AT = "reset_at";
    private static final int REQUEST_FIVE_HOUR = 74205;
    private static final int REQUEST_WEEKLY = 74207;

    private ResetAlertScheduler() {
    }

    public static void scheduleFromSnapshot(Context context, UsageSnapshot usageSnapshot) {
        Context contextAppContext = appContext(context);
        if (contextAppContext != null) {
            cancelAll(contextAppContext);
            if (usageSnapshot != null && SecureTokenStore.isSignedIn(contextAppContext) && ResetAlertPreferences.enabled(contextAppContext)) {
                String metric = ResetAlertPreferences.getMetric(contextAppContext);
                int threshold = ResetAlertPreferences.getThreshold(contextAppContext);
                if (!"weekly".equals(metric)) {
                    scheduleWindow(contextAppContext, usageSnapshot.fiveHour, "five_hour", REQUEST_FIVE_HOUR, threshold);
                }
                if (!"five_hour".equals(metric)) {
                    scheduleWindow(contextAppContext, usageSnapshot.weekly, "weekly", REQUEST_WEEKLY, threshold);
                }
            }
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager alarmManager;
        Context contextAppContext = appContext(context);
        if (contextAppContext != null && (alarmManager = (AlarmManager) contextAppContext.getSystemService(ResetAlertPreferences.STYLE_ALARM)) != null) {
            alarmManager.cancel(pending(contextAppContext, "five_hour", 0L, REQUEST_FIVE_HOUR));
            alarmManager.cancel(pending(contextAppContext, "weekly", 0L, REQUEST_WEEKLY));
        }
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ResetAlertPreferences.STYLE_ALARM);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    private static void scheduleWindow(Context context, UsageWindow usageWindow, String str, int i, int i2) {
        AlarmManager alarmManager;
        if (usageWindow != null && usageWindow.resetAtMillis() > System.currentTimeMillis()) {
            if ((i2 >= 100 || usageWindow.remainingPercent() <= i2) && (alarmManager = (AlarmManager) context.getSystemService(ResetAlertPreferences.STYLE_ALARM)) != null) {
                long jResetAtMillis = usageWindow.resetAtMillis() + DELIVERY_GRACE_MS;
                PendingIntent pendingIntentPending = pending(context, str, usageWindow.resetAtMillis(), i);
                try {
                    if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(0, jResetAtMillis, pendingIntentPending);
                    } else {
                        alarmManager.setAndAllowWhileIdle(0, jResetAtMillis, pendingIntentPending);
                    }
                } catch (SecurityException e) {
                    alarmManager.setAndAllowWhileIdle(0, jResetAtMillis, pendingIntentPending);
                }
            }
        }
    }

    private static PendingIntent pending(Context context, String str, long j, int i) {
        return PendingIntent.getBroadcast(context, i, new Intent(context, (Class<?>) ResetAlertReceiver.class).setAction(AppConstants.ACTION_RESET_ALERT).putExtra(EXTRA_METRIC, str).putExtra(EXTRA_RESET_AT, j), 201326592);
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context applicationContext = context.getApplicationContext();
        return applicationContext != null ? applicationContext : context;
    }
}
