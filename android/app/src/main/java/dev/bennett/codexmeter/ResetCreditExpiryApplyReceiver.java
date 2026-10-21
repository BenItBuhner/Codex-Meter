package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Uses banked reset credits automatically: an unused credit shortly before it expires, and
 * (when the user opts in) one credit when remaining usage drops to a chosen threshold.
 */
public final class ResetCreditExpiryApplyReceiver extends BroadcastReceiver {
    private static final Object APPLY_LOCK = new Object();
    private static final String PREFS = "codex_meter_reset_auto_use_v1";
    /** Expiry tokens already handled (used, nothing to reset, or gone), so they never repeat. */
    private static final String KEY_HANDLED_EXPIRY = "handled_expiry_tokens";
    /** Expiry tokens whose failure was already announced; retries stay silent. */
    private static final String KEY_FAILED_EXPIRY = "failed_expiry_tokens";
    private static final String KEY_LOW_USAGE_WINDOW = "low_usage_window_token";
    private static final String KEY_LOW_USAGE_FAILED = "low_usage_failed_token";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !AppConstants.ACTION_RESET_CREDIT_EXPIRY_APPLY.equals(intent.getAction())
                || !SecureTokenStore.isSignedIn(context)
                || !ResetAlertPreferences.resetCreditExpiryAutoApplyEnabled(context)) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = appContext(context);
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
        final Context app = appContext(context);
        new Thread(() -> applyDue(app), "reset-credit-expiry-apply").start();
    }

    /** Runs after every successful usage refresh, on the snapshot that refresh just returned. */
    static void evaluateLowUsageAsync(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null
                || !SecureTokenStore.isSignedIn(context)
                || !ResetAlertPreferences.resetCreditLowUsageAutoUseEnabled(context)) {
            return;
        }
        final Context app = appContext(context);
        new Thread(() -> applyLowUsage(app, snapshot), "reset-credit-low-usage").start();
    }

    static void clearState(Context context) {
        if (context == null) return;
        prefs(context).edit().clear().apply();
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
            long lead = ResetAlertPreferences.resetCreditExpiryAutoApplyLeadMillis(context);
            Set<String> handled = stringSet(context, KEY_HANDLED_EXPIRY);
            ResetCreditExpiryApply apply = null;
            for (ResetCreditExpiryApply candidate
                    : ResetCreditExpiryApply.due(snapshot.credits, now, lead)) {
                if (!handled.contains(candidate.token())) {
                    apply = candidate;
                    break;
                }
            }
            if (apply == null) return;
            String token = apply.token();
            try {
                DiagnosticLog.info(context, "user", "reset_credit_expiry_auto_apply_started",
                        "credit_id", apply.creditId);
                ResetConsumeResult result = ResetCreditApi.consumeAvailable(
                        context, apply.creditId);
                // Any answer from OpenAI is final for this credit; only network errors retry.
                handled.add(token);
                putStringSet(context, KEY_HANDLED_EXPIRY, pruneTokens(handled, now));
                ResetNotificationManager.showResetCreditExpiryApplyNotification(
                        context, apply.expiresAtMillis, result);
                DiagnosticLog.info(context, "user", "reset_credit_expiry_auto_apply_finished",
                        "credit_id", apply.creditId,
                        "result", result == null ? "" : result.outcome);
            } catch (Exception exception) {
                DiagnosticLog.error(context, "user", "reset_credit_expiry_auto_apply_failed",
                        exception, "credit_id", apply.creditId);
                Set<String> failed = stringSet(context, KEY_FAILED_EXPIRY);
                if (failed.add(token)) {
                    putStringSet(context, KEY_FAILED_EXPIRY, pruneTokens(failed, now));
                    ResetNotificationManager.showResetCreditExpiryApplyNotification(
                            context, apply.expiresAtMillis,
                            new ResetConsumeResult("", 0, UsageApi.safeMessage(exception)));
                }
            }
        }
    }

    static void applyLowUsage(Context context, UsageSnapshot snapshot) {
        if (context == null) return;
        synchronized (APPLY_LOCK) {
            if (!SecureTokenStore.isSignedIn(context)
                    || !ResetAlertPreferences.resetCreditLowUsageAutoUseEnabled(context)) {
                return;
            }
            ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(context);
            int available = credits != null ? credits.availableCount
                    : snapshot.resetCreditsAvailable;
            SharedPreferences prefs = prefs(context);
            ResetCreditLowUsageAutoUse decision = ResetCreditLowUsageAutoUse.evaluate(snapshot,
                    ResetAlertPreferences.resetCreditLowUsageMetric(context),
                    ResetAlertPreferences.resetCreditLowUsageThreshold(context),
                    available, prefs.getString(KEY_LOW_USAGE_WINDOW, ""),
                    System.currentTimeMillis());
            if (!decision.fires()) {
                if (!ResetCreditLowUsageAutoUse.WAIT.equals(decision.outcome)) {
                    DiagnosticLog.info(context, "user", "reset_credit_low_usage_skipped",
                            "reason", decision.outcome, "window", decision.windowLabel);
                }
                return;
            }
            try {
                DiagnosticLog.info(context, "user", "reset_credit_low_usage_started",
                        "window", decision.windowLabel,
                        "remaining", decision.remainingPercent);
                ResetConsumeResult result = ResetCreditApi.consumeBestAvailable(context);
                prefs.edit().putString(KEY_LOW_USAGE_WINDOW, decision.windowToken).apply();
                ResetNotificationManager.showResetCreditLowUsageNotification(context,
                        decision.windowLabel, decision.remainingPercent, result);
                DiagnosticLog.info(context, "user", "reset_credit_low_usage_finished",
                        "result", result == null ? "" : result.outcome);
            } catch (Exception exception) {
                DiagnosticLog.error(context, "user", "reset_credit_low_usage_failed", exception);
                if (!decision.windowToken.equals(prefs.getString(KEY_LOW_USAGE_FAILED, ""))) {
                    prefs.edit().putString(KEY_LOW_USAGE_FAILED, decision.windowToken).apply();
                    ResetNotificationManager.showResetCreditLowUsageNotification(context,
                            decision.windowLabel, decision.remainingPercent,
                            new ResetConsumeResult("", 0, UsageApi.safeMessage(exception)));
                }
            }
        }
    }

    /** Drops tokens for credits that expired more than a day ago. */
    private static Set<String> pruneTokens(Set<String> tokens, long now) {
        Set<String> kept = new HashSet<>();
        for (String token : tokens) {
            int split = token.lastIndexOf(':');
            try {
                long expires = Long.parseLong(token.substring(split + 1));
                if (expires > now - 24L * 60L * 60L * 1000L) kept.add(token);
            } catch (RuntimeException ignored) {
            }
        }
        return kept;
    }

    private static Set<String> stringSet(Context context, String key) {
        Set<String> stored = prefs(context).getStringSet(key, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    private static void putStringSet(Context context, String key, Set<String> values) {
        prefs(context).edit().putStringSet(key, values).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context appContext(Context context) {
        Context applicationContext = context.getApplicationContext();
        return applicationContext == null ? context : applicationContext;
    }

    static List<ResetCreditExpiryApply> plan(Context context, ResetCreditsSnapshot snapshot) {
        return ResetCreditExpiryApply.plan(snapshot == null ? null : snapshot.credits,
                System.currentTimeMillis(),
                ResetAlertPreferences.resetCreditExpiryAutoApplyLeadMillis(context));
    }
}
