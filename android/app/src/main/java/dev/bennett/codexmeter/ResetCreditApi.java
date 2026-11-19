package dev.bennett.codexmeter;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;

/* JADX INFO: loaded from: classes.dex */
public final class ResetCreditApi {
    private static final long DETAIL_FRESH_MS = 300000;

    private ResetCreditApi() {
    }

    public static ResetCreditsSnapshot refreshAndCache(Context context) throws Exception {
        if (DemoMode.isOn(context, UsageProvider.CODEX)) {
            DemoMode.refresh(context, UsageProvider.CODEX);
            return AppPreferences.loadResetCredits(context);
        }
        ResetCreditsSnapshot resetCreditsSnapshotRefreshAndCacheLocked;
        synchronized (UsageApi.NETWORK_LOCK) {
            UsageApi.installCookieManager();
            resetCreditsSnapshotRefreshAndCacheLocked = refreshAndCacheLocked(context, UsageApi.usableTokens(context));
        }
        return resetCreditsSnapshotRefreshAndCacheLocked;
    }

    static ResetCreditsSnapshot refreshAndCacheLocked(Context context, AuthTokens authTokens) throws Exception {
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "refresh", "reset_credit_refresh_started");
        if (authTokens == null) {
            authTokens = UsageApi.usableTokens(context);
        }
        Response responseRequest = request(context, "reset_credit_list", "GET",
                AppConstants.RESET_CREDITS_URL, authTokens, null);
        if (responseRequest.status == 401) {
            DiagnosticLog.warn(context, "auth", "reset_credit_token_rejected_refreshing");
            AuthTokens authTokensRefresh = OAuthClient.refresh(context, authTokens);
            SecureTokenStore.save(context, authTokensRefresh);
            responseRequest = request(context, "reset_credit_list", "GET",
                    AppConstants.RESET_CREDITS_URL, authTokensRefresh, null);
        }
        ensureSuccess(responseRequest, "Could not load Codex reset credits");
        ResetCreditsSnapshot resetCreditsSnapshot = ResetCreditsParser.parse(responseRequest.body, System.currentTimeMillis());
        if (!AppPreferences.saveResetCredits(context, resetCreditsSnapshot)) {
            throw new Exception("Reset credits were received, but could not be saved on this device.");
        }
        ResetNotificationManager.onResetCreditsUpdated(context, resetCreditsSnapshot);
        notifyUpdated(context);
        DiagnosticLog.info(context, "refresh", "reset_credit_refresh_succeeded",
                "duration_ms", SystemClock.elapsedRealtime() - started,
                "available", resetCreditsSnapshot.availableCount);
        return resetCreditsSnapshot;
    }

    public static ResetConsumeResult consumeBestAvailable(Context context) throws Exception {
        return consumeAvailable(context, null);
    }

    public static ResetConsumeResult consumeAvailable(Context context, String creditId)
            throws Exception {
        DemoMode.requireRealAccount(context, UsageProvider.CODEX);
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(app, "user", "reset_credit_use_requested");
        synchronized (UsageApi.NETWORK_LOCK) {
            UsageApi.installCookieManager();
            AuthTokens tokens = UsageApi.usableTokens(app);
            ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(app);
            long now = System.currentTimeMillis();

            if (credits == null || now - credits.fetchedAtMillis > DETAIL_FRESH_MS
                    || credits.availableCount <= 0) {
                try {
                    credits = refreshAndCacheLocked(app, tokens);
                    tokens = UsageApi.usableTokens(app);
                } catch (Exception exception) {
                    if (credits == null || credits.availableCount <= 0) throw exception;
                }
            }

            if (credits == null || credits.availableCount <= 0) {
                return new ResetConsumeResult(ResetConsumeResult.NO_CREDIT, 0, "");
            }

            JSONObject requestBody = new JSONObject();
            requestBody.put("redeem_request_id", UUID.randomUUID().toString());
            String preferredCreditId = preferredCreditId(credits, creditId, now);
            if (creditId != null && !creditId.isEmpty() && preferredCreditId.isEmpty()) {
                return new ResetConsumeResult(ResetConsumeResult.NO_CREDIT, 0, "");
            }
            if (!preferredCreditId.isEmpty()) {
                requestBody.put(AppConstants.EXTRA_CREDIT_ID, preferredCreditId);
            }
            byte[] payload = requestBody.toString().getBytes(StandardCharsets.UTF_8);

            Response response = request(app, "reset_credit_consume", "POST",
                    AppConstants.RESET_CREDITS_CONSUME_URL, tokens, payload);
            if (response.status == 401) {
                DiagnosticLog.warn(app, "auth", "reset_consume_token_rejected_refreshing");
                tokens = OAuthClient.refresh(app, tokens);
                SecureTokenStore.save(app, tokens);
                response = request(app, "reset_credit_consume", "POST",
                        AppConstants.RESET_CREDITS_CONSUME_URL, tokens, payload);
            }
            ensureSuccess(response, "Could not apply the Codex reset");

            JSONObject object = new JSONObject(response.body);
            String code = object.optString("code", "");
            int windowsReset = object.optInt("windows_reset", 0);
            String message = "";

            if (ResetConsumeResult.RESET.equals(code)) {
                ResetNotificationManager.markUserReset(app, AppPreferences.loadSnapshot(app));
                try {
                    UsageSnapshot snapshot = UsageApi.refreshAndCache(app);
                    RefreshScheduler.scheduleAtNextReset(app, snapshot);
                    ResetAlertScheduler.scheduleFromSnapshot(app, snapshot);
                } catch (Exception exception) {
                    message = "The reset succeeded, but the new usage values could not be loaded yet.";
                    AppPreferences.setLastError(app, UsageApi.safeMessage(exception));
                }
            }

            try {
                refreshAndCacheLocked(app, UsageApi.usableTokens(app));
            } catch (Exception exception) {
                AppPreferences.setResetCreditsError(app, UsageApi.safeMessage(exception));
            }
            WidgetRenderer.updateAll(app);
            notifyUpdated(app);
            DiagnosticLog.info(app, "user", "reset_credit_use_finished",
                    "result", code,
                    "windows_reset", windowsReset,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            return new ResetConsumeResult(code, windowsReset, message);
        }
    }

    private static String preferredCreditId(ResetCreditsSnapshot credits, String creditId,
            long nowMillis) {
        if (creditId != null && !creditId.isEmpty()) {
            for (RateLimitResetCredit credit : credits.credits) {
                if (credit != null && credit.isAvailable() && credit.id.equals(creditId)
                        && (credit.expiresAtMillis <= 0L || credit.expiresAtMillis > nowMillis)) {
                    return creditId;
                }
            }
            return "";
        }
        return credits.preferredCreditId(nowMillis);
    }

    private static Response request(Context context, String operation, String method, String url,
            AuthTokens authTokens, byte[] payload) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "network", "request_started",
                "operation", operation,
                "method", method,
                "url", DiagnosticSanitizer.safeUrl(url),
                "request_bytes", payload == null ? 0 : payload.length);
        try {
            UsageApi.applyHeaders(connection, authTokens);
            connection.setRequestMethod(method);
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream outputStream = connection.getOutputStream();
                try {
                    outputStream.write(payload);
                    if (outputStream != null) {
                        outputStream.close();
                    }
                } finally {
                }
            }
            int responseCode = connection.getResponseCode();
            String body = OAuthClient.readBody(connection, responseCode);
            DiagnosticLog.info(context, "network", "request_finished",
                    "operation", operation,
                    "status", responseCode,
                    "duration_ms", SystemClock.elapsedRealtime() - started,
                    "response_bytes",
                    body.getBytes(StandardCharsets.UTF_8).length);
            return new Response(responseCode, body);
        } catch (Exception exception) {
            DiagnosticLog.error(context, "network", "request_failed", exception,
                    "operation", operation,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    private static void ensureSuccess(Response response, String operation) throws Exception {
        String fallbackError;
        if (response.status < 200 || response.status >= 300) {
            if (response.status == 403) {
                fallbackError = operation + ": this account is not allowed to use reset credits.";
            } else if (response.status == 404) {
                fallbackError = operation + ": the reset-credit endpoint is unavailable.";
            } else {
                fallbackError = operation + " (HTTP " + response.status + ").";
            }
            throw new Exception(OAuthClient.readError(response.body, fallbackError));
        }
    }

    private static void notifyUpdated(Context context) {
        try {
            context.sendBroadcast(new Intent(AppConstants.ACTION_RESET_CREDITS_UPDATED).setPackage(context.getPackageName()), "dev.bennett.codexmeter.permission.INTERNAL");
        } catch (RuntimeException error) {
            DiagnosticLog.error(context, "reset", "credits_broadcast_failed", error);
        }
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
