package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Consumes a banked reset credit in the last minute before it would expire unused. */
public final class ResetCreditExpiryApplyReceiver extends BroadcastReceiver {
    private static final Object APPLY_LOCK = new Object();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !AppConstants.ACTION_RESET_CREDIT_EXPIRY_APPLY.equals(intent.getAction())
                || !SecureTokenStore.isSignedIn(context)
                || !ResetAlertPreferences.resetCreditExpiryAutoApplyEnabled(context)) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        new Thread(() -> {
            try {
                applyDue(app);
            } finally {
                pending.finish();
            }
        }, "reset-credit-expiry-apply").start();
    }

    static void evaluateAsync(Context context) {
        if (context == null
                || !SecureTokenStore.isSignedIn(context)
                || !ResetAlertPreferences.resetCreditExpiryAutoApplyEnabled(context)) {
            return;
        }
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        new Thread(() -> applyDue(app), "reset-credit-expiry-apply").start();
    }

    static void applyDue(Context context) {
        if (context == null) return;
        synchronized (APPLY_LOCK) {
            if (!SecureTokenStore.isSignedIn(context)
                    || !ResetAlertPreferences.resetCreditExpiryAutoApplyEnabled(context)) {
                return;
            }
            ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(context);
            if (snapshot == null) return;
            long now = System.currentTimeMillis();
            java.util.List<ResetCreditExpiryApply> due =
                    ResetCreditExpiryApply.due(snapshot.credits, now);
            if (due.isEmpty()) return;
            ResetCreditExpiryApply apply = due.get(0);
            try {
                DiagnosticLog.info(context, "user", "reset_credit_expiry_auto_apply_started",
                        "credit_id", apply.creditId);
                ResetConsumeResult result = ResetCreditApi.consumeAvailable(
                        context, apply.creditId);
                ResetNotificationManager.showResetCreditExpiryApplyNotification(
                        context, apply.creditId, result);
                DiagnosticLog.info(context, "user", "reset_credit_expiry_auto_apply_finished",
                        "credit_id", apply.creditId,
                        "result", result == null ? "" : result.outcome);
            } catch (Exception exception) {
                DiagnosticLog.error(context, "user", "reset_credit_expiry_auto_apply_failed",
                        exception, "credit_id", apply.creditId);
                ResetNotificationManager.showResetCreditExpiryApplyNotification(
                        context, apply.creditId,
                        new ResetConsumeResult("", 0, UsageApi.safeMessage(exception)));
            }
        }
    }
}
