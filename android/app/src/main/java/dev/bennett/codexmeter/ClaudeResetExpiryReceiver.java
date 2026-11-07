package dev.bennett.codexmeter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Exact alarm at the chosen lead time before the next Claude Code reset expires, like the Codex
 * reset-credit alarm. When it fires it refreshes Claude usage, which runs the auto-use rules on
 * fresh data and schedules the next alarm.
 */
public final class ClaudeResetExpiryReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.bennett.codexmeter.action.CLAUDE_RESET_EXPIRY_APPLY";
    private static final int REQUEST_CODE = 74220;
    private static final long DELIVERY_GRACE_MS = 1000L;
    private static final UsageProvider PROVIDER = UsageProvider.CLAUDE_CODE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())
                || !SecureTokenStore.isSignedIn(context, PROVIDER)
                || !ResetAlertPreferences.claudeSavedResetExpiryEnabled(context)) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext() == null ? context
                : context.getApplicationContext();
        new Thread(() -> {
            try {
                ClaudeUsageApi.refreshAndCache(app);
            } catch (Exception exception) {
                DiagnosticLog.warn(app, "reset", "claude_reset_expiry_refresh_failed",
                        "error", exception.getClass().getSimpleName());
            } finally {
                pending.finish();
            }
        }, "claude-reset-expiry-apply").start();
    }

    /** Re-plans the alarm from the cached saved resets. */
    public static void schedule(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext() == null ? context
                : context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(app, REQUEST_CODE,
                new Intent(app, ClaudeResetExpiryReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(pendingIntent);
        if (!SecureTokenStore.isSignedIn(app, PROVIDER)
                || !ResetAlertPreferences.claudeSavedResetExpiryEnabled(app)) {
            return;
        }
        ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(app, PROVIDER);
        long now = System.currentTimeMillis();
        long expiry = saved == null ? 0L : saved.nextExpiryMillis(now);
        if (expiry <= 0L) return;
        long triggerAt = expiry - ResetAlertPreferences.claudeSavedResetExpiryLeadMillis(app);
        // Already inside the lead window: the refresh that called this has just checked it.
        if (triggerAt <= now) return;
        triggerAt = Math.max(now + DELIVERY_GRACE_MS, triggerAt);
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt,
                        pendingIntent);
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException exception) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }
}
