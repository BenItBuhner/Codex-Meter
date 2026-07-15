package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Starts and completes X OAuth without embedding an app secret in the APK. */
public final class XAuthentication {
    private static final String PREFS = "codex_meter_x_oauth_pending_v1";
    private static final String KEY_STATE = "state";
    private static final String KEY_VERIFIER = "verifier";
    private static final String KEY_STARTED_AT = "started_at";
    private static final long MAX_PENDING_AGE_MS = 10 * 60 * 1000L;

    private XAuthentication() {
    }

    public static boolean isConfigured() {
        return BuildConfig.X_CLIENT_ID != null && !BuildConfig.X_CLIENT_ID.trim().isEmpty();
    }

    public static void begin(Activity activity) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "This build needs an X Native App client ID before reset watch can connect.");
        }
        Pkce pkce = Pkce.generate();
        if (!prefs(activity).edit()
                .putString(KEY_STATE, pkce.state)
                .putString(KEY_VERIFIER, pkce.verifier)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .commit()) {
            throw new Exception("Could not prepare secure X sign-in.");
        }
        activity.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(XOAuthFlow.authorizeUrl(BuildConfig.X_CLIENT_ID, pkce))));
    }

    public static void complete(Context context, Uri callback) throws Exception {
        if (callback == null || !"codexmeter".equals(callback.getScheme())
                || !"x-auth".equals(callback.getHost())
                || !"/complete".equals(callback.getPath())) {
            throw new SecurityException("Invalid X sign-in callback.");
        }
        SharedPreferences pending = prefs(context);
        String expectedState = pending.getString(KEY_STATE, "");
        String verifier = pending.getString(KEY_VERIFIER, "");
        long startedAt = pending.getLong(KEY_STARTED_AT, 0L);
        String actualState = callback.getQueryParameter("state");
        String error = callback.getQueryParameter("error");
        String description = callback.getQueryParameter("error_description");
        if (!secureEquals(expectedState, actualState)
                || verifier.isEmpty()
                || startedAt <= 0L
                || System.currentTimeMillis() - startedAt > MAX_PENDING_AGE_MS) {
            clearPending(context);
            throw new SecurityException("X sign-in expired or its security state did not match.");
        }
        if (error != null && !error.isEmpty()) {
            clearPending(context);
            throw new Exception(description == null || description.isEmpty() ? error : description);
        }
        String code = callback.getQueryParameter("code");
        clearPending(context);
        if (code == null || code.isEmpty()) {
            throw new Exception("X did not return an authorization code.");
        }
        XOAuthTokens tokens = XOAuthClient.exchangeCode(code, verifier);
        SecureXTokenStore.save(context, tokens);
        AnnouncementPreferences.setMonitoringEnabled(context, true);
        ResetWatchScheduler.ensureScheduled(context);
        ResetWatchScheduler.requestImmediate(context);
    }

    public static void disconnect(Context context) {
        SecureXTokenStore.clear(context);
        clearPending(context);
        AnnouncementPreferences.setMonitoringEnabled(context, false);
        AnnouncementPreferences.clearRemoteState(context);
        ResetWatchNotificationManager.clearState(context);
        ResetWatchScheduler.cancel(context);
    }

    private static void clearPending(Context context) {
        prefs(context).edit().clear().commit();
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.isEmpty()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
