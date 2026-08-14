package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;

/* JADX INFO: loaded from: classes.dex */
public final class UsageApi {
    static final Object NETWORK_LOCK = new Object();
    private static boolean cookiesInstalled;

    private UsageApi() {
    }

    public static UsageSnapshot refreshAndCache(Context context) throws Exception {
        AuthTokens authTokens;
        Response responseRequestUsage;
        String str;
        UsageSnapshot usageSnapshot;
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "refresh", "usage_refresh_started");
        try {
            synchronized (NETWORK_LOCK) {
                installCookieManager();
                AuthTokens authTokensUsableTokens = usableTokens(context);
                Response responseRequestUsage2 = requestUsage(context, authTokensUsableTokens);
                if (responseRequestUsage2.status == 401) {
                    DiagnosticLog.warn(context, "auth", "usage_token_rejected_refreshing");
                    AuthTokens authTokensRefresh = OAuthClient.refresh(context,
                            authTokensUsableTokens);
                    SecureTokenStore.save(context, authTokensRefresh);
                    authTokens = authTokensRefresh;
                    responseRequestUsage = requestUsage(context, authTokensRefresh);
                } else {
                    authTokens = authTokensUsableTokens;
                    responseRequestUsage = responseRequestUsage2;
                }
                if (responseRequestUsage.status < 200 || responseRequestUsage.status >= 300) {
                    if (responseRequestUsage.status == 403) {
                        str = "Codex usage access was denied for this account.";
                    } else {
                        str = responseRequestUsage.status == 404 ? "The Codex usage endpoint is unavailable or has changed." : "Usage refresh failed (HTTP " + responseRequestUsage.status + ").";
                    }
                    throw new Exception(OAuthClient.readError(responseRequestUsage.body, str));
                }
                usageSnapshot = UsageParser.parse(responseRequestUsage.body,
                        System.currentTimeMillis());
                if (!usageSnapshot.hasDisplayableData()) {
                    throw new Exception("OpenAI returned no recognizable Codex usage data.");
                }
                UsageSnapshot previousSnapshot = AppPreferences.loadSnapshot(context);
                if (!AppPreferences.saveSnapshot(context, usageSnapshot)) {
                    throw new Exception("Usage was received, but it could not be saved on this device.");
                }
                UsageHistoryRecorder.record(context, usageSnapshot);
                PhoneWearSync.pushUsage(context, usageSnapshot);
                NowBarManager.onUsageUpdated(context, usageSnapshot);
                ResetNotificationManager.onUsageUpdated(context, previousSnapshot, usageSnapshot);
                try {
                    ResetAlertScheduler.scheduleFromSnapshot(context, usageSnapshot);
                } catch (RuntimeException exception) {
                    DiagnosticLog.error(context, "scheduler", "reset_alert_schedule_failed",
                            exception);
                }
                try {
                    ResetCreditApi.refreshAndCacheLocked(context, authTokens);
                } catch (Exception exception) {
                    DiagnosticLog.error(context, "refresh", "reset_credit_side_refresh_failed",
                            exception);
                    ResetNotificationManager.onResetCreditSummaryUpdated(context,
                            usageSnapshot.resetCreditsAvailable);
                    AppPreferences.setResetCreditsError(context, safeMessage(exception));
                }
            }
        } catch (Exception exception) {
            DiagnosticLog.error(context, "refresh", "usage_refresh_failed", exception,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            throw exception;
        }
        DiagnosticLog.info(context, "refresh", "usage_refresh_succeeded",
                "duration_ms", SystemClock.elapsedRealtime() - started,
                "five_hour", usageSnapshot.fiveHour != null,
                "weekly", usageSnapshot.weekly != null,
                "monthly", usageSnapshot.monthly != null,
                "additional_limits", usageSnapshot.additionalLimits.size());
        return usageSnapshot;
    }

    static AuthTokens usableTokens(Context context) throws Exception {
        AuthTokens authTokensLoad = SecureTokenStore.load(context);
        if (authTokensLoad == null) {
            throw new Exception("Sign in to ChatGPT first.");
        }
        if (authTokensLoad.shouldRefresh(System.currentTimeMillis())) {
            DiagnosticLog.info(context, "auth", "token_refresh_due");
            AuthTokens authTokensRefresh = OAuthClient.refresh(context, authTokensLoad);
            SecureTokenStore.save(context, authTokensRefresh);
            return authTokensRefresh;
        }
        return authTokensLoad;
    }

    private static Response requestUsage(Context context, AuthTokens authTokens) throws Exception {
        HttpsURLConnection httpsURLConnection = (HttpsURLConnection) URI.create(AppConstants.USAGE_URL).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "network", "request_started",
                "operation", "usage",
                "method", "GET",
                "url", DiagnosticSanitizer.safeUrl(AppConstants.USAGE_URL));
        try {
            applyHeaders(httpsURLConnection, authTokens);
            httpsURLConnection.setRequestMethod("GET");
            int responseCode = httpsURLConnection.getResponseCode();
            String body = OAuthClient.readBody(httpsURLConnection, responseCode);
            DiagnosticLog.info(context, "network", "request_finished",
                    "operation", "usage",
                    "status", responseCode,
                    "duration_ms", SystemClock.elapsedRealtime() - started,
                    "response_bytes", body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            return new Response(responseCode, body);
        } catch (Exception exception) {
            DiagnosticLog.error(context, "network", "request_failed", exception,
                    "operation", "usage",
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            throw exception;
        } finally {
            httpsURLConnection.disconnect();
        }
    }

    static void applyHeaders(HttpsURLConnection httpsURLConnection, AuthTokens authTokens) {
        httpsURLConnection.setConnectTimeout(15000);
        httpsURLConnection.setReadTimeout(25000);
        httpsURLConnection.setUseCaches(false);
        httpsURLConnection.setRequestProperty("Accept", "application/json");
        httpsURLConnection.setRequestProperty("Authorization", "Bearer " + authTokens.accessToken);
        httpsURLConnection.setRequestProperty("User-Agent", AppConstants.userAgent());
        httpsURLConnection.setRequestProperty("originator", AppConstants.ORIGINATOR);
        if (!authTokens.accountId.isEmpty()) {
            httpsURLConnection.setRequestProperty("ChatGPT-Account-Id", authTokens.accountId);
        }
    }

    static void installCookieManager() {
        if (!cookiesInstalled) {
            try {
                if (CookieHandler.getDefault() == null) {
                    CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER));
                }
            } catch (Exception e) {
            }
            cookiesInstalled = true;
        }
    }

    static String safeMessage(Exception exc) {
        String message = exc == null ? "" : exc.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Reset-credit refresh failed.";
        }
        String strTrim = message.trim();
        return strTrim.length() > 240 ? strTrim.substring(0, 240) : strTrim;
    }

    private static final class Response {
        final String body;
        final int status;

        Response(int i, String str) {
            this.status = i;
            this.body = str == null ? "" : str;
        }
    }
}
