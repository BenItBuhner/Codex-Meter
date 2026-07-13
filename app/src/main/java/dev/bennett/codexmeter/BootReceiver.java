package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* JADX INFO: loaded from: classes.dex */
public final class BootReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if ("android.intent.action.BOOT_COMPLETED".equals(action) || "android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            RefreshScheduler.schedulePeriodic(context);
            ReleaseUpdateScheduler.ensureScheduled(context);
            ResetAlertScheduler.scheduleFromSnapshot(context, AppPreferences.loadSnapshot(context));
            ResetCreditExpiryScheduler.scheduleFromSnapshot(context,
                    AppPreferences.loadResetCredits(context));
            if ("android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
                WidgetUpgradeRepair.afterPackageReplaced(context);
            } else {
                WidgetUpgradeRepair.runIfNeeded(context);
            }
        }
    }
}
