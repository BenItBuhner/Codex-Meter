package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires when a date/time schedule (or its retry) comes due. It only asks for a fresh usage
 * refresh; {@link ScheduledResetManager#onUsageRefreshed} makes the actual decision from the
 * data that refresh returns, so a stale snapshot can never spend the credit.
 */
public final class ScheduledResetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !AppConstants.ACTION_SCHEDULED_RESET_DUE.equals(intent.getAction())
                || !ScheduledResetManager.isArmed(context)) {
            return;
        }
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        DiagnosticLog.info(app, "scheduled_reset", "alarm_fired",
                "demo", DemoMode.isActive(app));
        if (SecureTokenStore.isSignedIn(app)) {
            RefreshScheduler.scheduleImmediate(app);
            return;
        }
        if (!DemoMode.isActive(app)) {
            return;
        }
        // Demo refreshes are local, so they run right here instead of through JobScheduler.
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                UsageApi.refreshAndCache(app);
                WidgetRenderer.updateAll(app);
            } catch (Exception exception) {
                ScheduledResetManager.onRefreshFailed(app, exception);
            } finally {
                result.finish();
            }
        }, "scheduled-reset-demo").start();
    }
}
