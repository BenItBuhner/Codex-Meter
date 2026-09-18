package dev.bennett.codexmeter;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The local "Explore demo" session. It feeds {@link DemoData} through the same caches the live
 * refresh writes, so the dashboard, widgets, Now Bar, and history behave as if signed in while
 * no request leaves the device and {@link SecureTokenStore} is never written.
 */
public final class DemoMode {
    private DemoMode() {
    }

    /** Real credentials always win: a stale demo flag never serves sample data to an account. */
    public static boolean isActive(Context context) {
        return context != null && hasDemoState(appContext(context))
                && !SecureTokenStore.isSignedIn(context);
    }

    /** Whether real credentials or the demo session can populate the usage surfaces. */
    public static boolean hasSession(Context context) {
        return isActive(context) || SecureTokenStore.isSignedIn(context);
    }

    /** Seeds the demo session; refused while a ChatGPT account is signed in. */
    public static boolean enter(Context context) {
        Context app = appContext(context);
        if (SecureTokenStore.isSignedIn(app)) {
            DiagnosticLog.warn(app, "user", "demo_enter_rejected", "reason", "signed_in");
            return false;
        }
        long now = System.currentTimeMillis();
        DemoData.State state = DemoData.State.initial(now).refreshed(now);
        AppPreferences.saveUsageHistory(app,
                DemoData.seededHistory(state, UsageHistory.FIVE_HOUR));
        AppPreferences.saveUsageHistory(app,
                DemoData.seededHistory(state, UsageHistory.WEEKLY));
        AppPreferences.completeOnboarding(app);
        publish(app, state, now);
        WidgetRenderer.updateAll(app);
        DiagnosticLog.info(app, "user", "demo_entered");
        return true;
    }

    /**
     * Clears the demo flag and every cache it populated; a no-op outside the demo. Sign-out
     * reaches the same state through {@link AppPreferences#clearSnapshot}.
     */
    public static void leave(Context context) {
        Context app = appContext(context);
        if (!hasDemoState(app)) {
            return;
        }
        AppPreferences.clearSnapshot(app);
        WidgetRenderer.updateAll(app);
        DiagnosticLog.info(app, "user", "demo_left");
    }

    /** Stands in for the network fetch inside {@link UsageApi#refreshAndCache}. */
    static UsageSnapshot refreshAndCache(Context context) {
        Context app = appContext(context);
        long now = System.currentTimeMillis();
        DemoData.State state = loadState(app).refreshed(now);
        UsageSnapshot snapshot = publish(app, state, now);
        DiagnosticLog.info(app, "refresh", "demo_refresh_succeeded",
                "refresh_count", state.refreshCount);
        return snapshot;
    }

    static ResetCreditsSnapshot refreshResetCredits(Context context) {
        Context app = appContext(context);
        ResetCreditsSnapshot credits = DemoData.resetCredits(loadState(app),
                System.currentTimeMillis());
        AppPreferences.saveResetCredits(app, credits);
        return credits;
    }

    static ResetConsumeResult consumeReset(Context context) {
        Context app = appContext(context);
        long now = System.currentTimeMillis();
        DemoData.State state = loadState(app);
        if (state.availableCredits <= 0) {
            return new ResetConsumeResult(ResetConsumeResult.NO_CREDIT, 0, "");
        }
        publish(app, state.resetConsumed(now), now);
        WidgetRenderer.updateAll(app);
        DiagnosticLog.info(app, "user", "demo_reset_consumed");
        return new ResetConsumeResult(ResetConsumeResult.RESET, 2, "");
    }

    private static UsageSnapshot publish(Context app, DemoData.State state, long now) {
        try {
            AppPreferences.setDemoState(app, state.toJson().toString());
        } catch (JSONException exception) {
            DiagnosticLog.error(app, "refresh", "demo_state_encode_failed", exception);
        }
        UsageSnapshot snapshot = DemoData.snapshot(state, now);
        AppPreferences.saveSnapshot(app, snapshot);
        UsageHistoryRecorder.record(app, snapshot);
        AppPreferences.saveResetCredits(app, DemoData.resetCredits(state, now));
        NowBarManager.onUsageUpdated(app, snapshot);
        return snapshot;
    }

    private static DemoData.State loadState(Context app) {
        try {
            DemoData.State state = DemoData.State.fromJson(
                    new JSONObject(AppPreferences.getDemoState(app)));
            if (state != null) {
                return state;
            }
        } catch (JSONException ignored) {
            // A damaged state falls back to a fresh seed below.
        }
        return DemoData.State.initial(System.currentTimeMillis());
    }

    private static boolean hasDemoState(Context app) {
        return !AppPreferences.getDemoState(app).isEmpty();
    }

    private static Context appContext(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }
}
