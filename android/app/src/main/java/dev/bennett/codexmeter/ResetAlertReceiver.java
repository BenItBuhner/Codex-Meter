package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* JADX INFO: loaded from: classes.dex */
public final class ResetAlertReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (context != null && intent != null && AppConstants.ACTION_RESET_ALERT.equals(intent.getAction()) && SecureTokenStore.isSignedIn(context) && ResetAlertPreferences.enabled(context)) {
            String stringExtra = intent.getStringExtra("metric");
            if (!"weekly".equals(stringExtra)) {
                stringExtra = "five_hour";
            }
            if (stillRelevant(context, stringExtra, intent.getLongExtra("reset_at", 0L))) {
                ResetNotificationManager.showResetNotification(context, stringExtra);
                RefreshScheduler.scheduleImmediate(context);
                WidgetRenderer.updateAll(context);
            }
        }
    }

    private static boolean stillRelevant(Context context, String str, long j) {
        UsageSnapshot usageSnapshotLoadSnapshot;
        if (j <= 0 || (usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(context)) == null) {
            return true;
        }
        UsageWindow usageWindow = "weekly".equals(str) ? usageSnapshotLoadSnapshot.weekly : usageSnapshotLoadSnapshot.fiveHour;
        return usageWindow == null || usageWindow.resetAtMillis() <= 0 || Math.abs(usageWindow.resetAtMillis() - j) < 60000;
    }

}
