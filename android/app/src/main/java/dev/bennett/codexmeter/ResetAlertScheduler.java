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
    private static final int REQUEST_MONTHLY = 74208;

    private ResetAlertScheduler() {
    }

    public static void scheduleFromSnapshot(Context context, UsageSnapshot usageSnapshot) {
        Context contextAppContext = appContext(context);
        if (contextAppContext != null) {
            cancelAll(contextAppContext);
            if (usageSnapshot != null && SecureTokenStore.isSignedIn(contextAppContext) && ResetAlertPreferences.enabled(contextAppContext)) {
                String metric = ResetAlertPreferences.getMetric(contextAppContext);
                if (!"weekly".equals(metric)) {
                    scheduleWindow(contextAppContext, usageSnapshot.fiveHour, "five_hour", REQUEST_FIVE_HOUR);
                }
                if (!"five_hour".equals(metric)) {
                    scheduleWindow(contextAppContext, usageSnapshot.weekly, "weekly", REQUEST_WEEKLY);
                    scheduleWindow(contextAppContext, usageSnapshot.monthly, "monthly", REQUEST_MONTHLY);
                }
            }
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager alarmManager;
        Context contextAppContext = appContext(context);
        if (contextAppContext != null && (alarmManager = (AlarmManager) contextAppContext.getSystemService(Context.ALARM_SERVICE)) != null) {
            alarmManager.cancel(pending(contextAppContext, "five_hour", 0L, REQUEST_FIVE_HOUR));
            alarmManager.cancel(pending(contextAppContext, "weekly", 0L, REQUEST_WEEKLY));
            alarmManager.cancel(pending(contextAppContext, "monthly", 0L, REQUEST_MONTHLY));
        }
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    private static void scheduleWindow(Context context, UsageWindow window, String metric, int requestCode) {
        AlarmManager alarmManager;
        if (window != null && window.resetAtMillis() > System.currentTimeMillis()) {
            if ((alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE)) != null) {
                long fireAtMillis = window.resetAtMillis() + DELIVERY_GRACE_MS;
                PendingIntent intent = pending(context, metric, window.resetAtMillis(), requestCode);
                try {
                    if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, intent);
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, intent);
                    }
                } catch (SecurityException e) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, intent);
                }
            }
        }
    }

    private static PendingIntent pending(Context context, String metric, long resetAtMillis, int requestCode) {
        return PendingIntent.getBroadcast(context, requestCode, new Intent(context, (Class<?>) ResetAlertReceiver.class).setAction(AppConstants.ACTION_RESET_ALERT).putExtra(EXTRA_METRIC, metric).putExtra(EXTRA_RESET_AT, resetAtMillis), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context applicationContext = context.getApplicationContext();
        return applicationContext != null ? applicationContext : context;
    }
}
