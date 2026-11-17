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
        if (DemoMode.isOn(context, UsageProvider.CODEX)) {
            return DemoMode.refresh(context, UsageProvider.CODEX);
        }
        AuthTokens authTokens;
        Response response;
        String fallbackError;
        UsageSnapshot usageSnapshot;
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "refresh", "usage_refresh_started");
        try {
            synchronized (NETWORK_LOCK) {
                installCookieManager();
                AuthTokens stored = usableTokens(context);
                Response firstResponse = requestUsage(context, stored);
                if (firstResponse.status == 401) {
                    DiagnosticLog.warn(context, "auth", "usage_token_rejected_refreshing");
                    AuthTokens refreshed = OAuthClient.refresh(context,
                            stored);
                    SecureTokenStore.save(context, refreshed);
                    authTokens = refreshed;
                    response = requestUsage(context, refreshed);
                } else {
                    authTokens = stored;
                    response = firstResponse;
                }
                if (response.status < 200 || response.status >= 300) {
                    if (response.status == 403) {
                        fallbackError = "Codex usage access was denied for this account.";
                    } else {
                        fallbackError = response.status == 404 ? "The Codex usage endpoint is unavailable or has changed." : "Usage refresh failed (HTTP " + response.status + ").";
                    }
                    throw new Exception(OAuthClient.readError(response.body, fallbackError));
                }
                usageSnapshot = UsageParser.parse(response.body,
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
                UsageRefillAlerts.onUsageSaved(context, UsageProvider.CODEX, usageSnapshot);
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
                ResetCreditExpiryApplyReceiver.evaluateLowUsageAsync(context, usageSnapshot);
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
        DemoMode.requireRealAccount(context, UsageProvider.CODEX);
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

    static String safeMessage(Exception exception) {
        String message = exception == null ? "" : exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Reset-credit refresh failed.";
        }
        String trimmed = message.trim();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
    }

    private static final class Response {
        final String body;
        final int status;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
