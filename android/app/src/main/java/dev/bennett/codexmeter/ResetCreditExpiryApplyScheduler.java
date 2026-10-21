package dev.bennett.codexmeter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Schedules one alarm per available reset credit, at the chosen lead time before expiry. */
public final class ResetCreditExpiryApplyScheduler {
    static final String EXTRA_CREDIT_ID = "expiry_apply_credit_id";
    static final String EXTRA_EXPIRES_AT = "expiry_apply_expires_at";
    private static final long DELIVERY_GRACE_MS = 1000L;
    private static final String KEY_ALARM_URIS = "alarm_uris";
    private static final String PREFS = "codex_meter_reset_expiry_apply_alarms_v1";
    private static final int REQUEST_CODE = 74219;

    private ResetCreditExpiryApplyScheduler() {
    }

    public static void scheduleFromSnapshot(Context context, ResetCreditsSnapshot snapshot) {
        Context app = appContext(context);
        if (app == null) return;
        cancelAll(app);
        if (snapshot == null || !SecureTokenStore.isSignedIn(app)
                || !ResetAlertPreferences.resetCreditExpiryAutoApplyEnabled(app)) {
            return;
        }
        List<ResetCreditExpiryApply> planned =
                ResetCreditExpiryApplyReceiver.plan(app, snapshot);
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        long now = System.currentTimeMillis();
        Set<String> scheduledUris = new HashSet<>();
        for (ResetCreditExpiryApply apply : planned) {
            Uri data = data(apply.creditId, apply.expiresAtMillis);
            PendingIntent pendingIntent = pending(app, data, apply);
            long triggerAt = Math.max(now + DELIVERY_GRACE_MS, apply.triggerAtMillis);
            try {
                if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                            triggerAt, pendingIntent);
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                            triggerAt, pendingIntent);
                }
                scheduledUris.add(data.toString());
            } catch (SecurityException exception) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        triggerAt, pendingIntent);
                scheduledUris.add(data.toString());
            }
        }
        prefs(app).edit().putStringSet(KEY_ALARM_URIS, scheduledUris).apply();
    }

    public static void cancelAll(Context context) {
        Context app = appContext(context);
        if (app == null) return;
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        Set<String> uris = prefs(app).getStringSet(KEY_ALARM_URIS, null);
        if (manager != null && uris != null) {
            for (String uri : new HashSet<>(uris)) {
                PendingIntent pendingIntent = existingPending(app, Uri.parse(uri));
                if (pendingIntent != null) {
                    manager.cancel(pendingIntent);
                    pendingIntent.cancel();
                }
            }
        }
        prefs(app).edit().remove(KEY_ALARM_URIS).apply();
    }

    private static PendingIntent pending(Context context, Uri data,
            ResetCreditExpiryApply apply) {
        Intent intent = baseIntent(context, data)
                .putExtra(EXTRA_CREDIT_ID, apply.creditId)
                .putExtra(EXTRA_EXPIRES_AT, apply.expiresAtMillis);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent existingPending(Context context, Uri data) {
        return PendingIntent.getBroadcast(context, REQUEST_CODE, baseIntent(context, data),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Intent baseIntent(Context context, Uri data) {
        return new Intent(context, ResetCreditExpiryApplyReceiver.class)
                .setAction(AppConstants.ACTION_RESET_CREDIT_EXPIRY_APPLY)
                .setData(data);
    }

    private static Uri data(String creditId, long expiresAt) {
        return new Uri.Builder()
                .scheme("codexmeter")
                .authority("reset-credit-expiry-apply")
                .appendPath(creditId == null || creditId.isEmpty() ? "_" : creditId)
                .appendQueryParameter("expires", String.valueOf(expiresAt))
                .build();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context appContext(Context context) {
        if (context == null) return null;
        Context applicationContext = context.getApplicationContext();
        return applicationContext == null ? context : applicationContext;
    }
}
