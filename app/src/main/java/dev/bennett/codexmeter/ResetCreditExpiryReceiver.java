package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Revalidates a scheduled reset-credit reminder before showing it. */
public final class ResetCreditExpiryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !AppConstants.ACTION_RESET_CREDIT_EXPIRY_ALERT.equals(intent.getAction())
                || !SecureTokenStore.isSignedIn(context)
                || !ResetAlertPreferences.enabled(context)
                || !ResetAlertPreferences.resetCreditExpiryEnabled(context)) {
            return;
        }
        String creditId = intent.getStringExtra(ResetCreditExpiryScheduler.EXTRA_CREDIT_ID);
        long expiresAt = intent.getLongExtra(
                ResetCreditExpiryScheduler.EXTRA_EXPIRES_AT, 0L);
        long leadTime = intent.getLongExtra(
                ResetCreditExpiryScheduler.EXTRA_LEAD_TIME, 0L);
        if (!ResetAlertPreferences.getResetCreditExpiryLeadTimes(context).contains(leadTime)
                || !isStillAvailable(context, creditId, expiresAt)) {
            return;
        }
        ResetNotificationManager.showResetCreditExpiryNotification(context,
                creditId, expiresAt, leadTime);
        RefreshScheduler.scheduleImmediate(context);
        WidgetRenderer.updateAll(context);
    }

    static boolean isStillAvailable(Context context, String creditId, long expiresAt) {
        if (expiresAt <= System.currentTimeMillis()) return false;
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(context);
        if (snapshot == null) return false;
        for (RateLimitResetCredit credit : snapshot.credits) {
            if (credit == null || !credit.isAvailable()
                    || credit.expiresAtMillis != expiresAt) {
                continue;
            }
            if (creditId == null || creditId.isEmpty() || credit.id.equals(creditId)) {
                return true;
            }
        }
        return false;
    }
}
