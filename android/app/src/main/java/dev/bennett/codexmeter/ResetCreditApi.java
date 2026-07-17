package dev.bennett.codexmeter;

import android.content.Context;
import android.content.Intent;
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
        ResetCreditsSnapshot resetCreditsSnapshotRefreshAndCacheLocked;
        synchronized (UsageApi.NETWORK_LOCK) {
            UsageApi.installCookieManager();
            resetCreditsSnapshotRefreshAndCacheLocked = refreshAndCacheLocked(context, UsageApi.usableTokens(context));
        }
        return resetCreditsSnapshotRefreshAndCacheLocked;
    }

    static ResetCreditsSnapshot refreshAndCacheLocked(Context context, AuthTokens authTokens) throws Exception {
        if (authTokens == null) {
            authTokens = UsageApi.usableTokens(context);
        }
        Response responseRequest = request("GET", AppConstants.RESET_CREDITS_URL, authTokens, null);
        if (responseRequest.status == 401) {
            AuthTokens authTokensRefresh = OAuthClient.refresh(authTokens);
            SecureTokenStore.save(context, authTokensRefresh);
            responseRequest = request("GET", AppConstants.RESET_CREDITS_URL, authTokensRefresh, null);
        }
        ensureSuccess(responseRequest, "Could not load Codex reset credits");
        ResetCreditsSnapshot resetCreditsSnapshot = ResetCreditsParser.parse(responseRequest.body, System.currentTimeMillis());
        if (!AppPreferences.saveResetCredits(context, resetCreditsSnapshot)) {
            throw new Exception("Reset credits were received, but could not be saved on this device.");
        }
        ResetNotificationManager.onResetCreditsUpdated(context, resetCreditsSnapshot);
        notifyUpdated(context);
        return resetCreditsSnapshot;
    }

    public static ResetConsumeResult consumeBestAvailable(Context context) throws Exception {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
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
            String preferredCreditId = credits.preferredCreditId(now);
            if (!preferredCreditId.isEmpty()) {
                requestBody.put(AppConstants.EXTRA_CREDIT_ID, preferredCreditId);
            }
            byte[] payload = requestBody.toString().getBytes(StandardCharsets.UTF_8);

            Response response = request("POST", AppConstants.RESET_CREDITS_CONSUME_URL, tokens, payload);
            if (response.status == 401) {
                tokens = OAuthClient.refresh(tokens);
                SecureTokenStore.save(app, tokens);
                response = request("POST", AppConstants.RESET_CREDITS_CONSUME_URL, tokens, payload);
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
            return new ResetConsumeResult(code, windowsReset, message);
        }
    }

    private static Response request(String str, String str2, AuthTokens authTokens, byte[] bArr) throws Exception {
        HttpsURLConnection httpsURLConnection = (HttpsURLConnection) URI.create(str2).toURL().openConnection();
        try {
            UsageApi.applyHeaders(httpsURLConnection, authTokens);
            httpsURLConnection.setRequestMethod(str);
            if (bArr != null) {
                httpsURLConnection.setDoOutput(true);
                httpsURLConnection.setRequestProperty("Content-Type", "application/json");
                httpsURLConnection.setFixedLengthStreamingMode(bArr.length);
                OutputStream outputStream = httpsURLConnection.getOutputStream();
                try {
                    outputStream.write(bArr);
                    if (outputStream != null) {
                        outputStream.close();
                    }
                } finally {
                }
            }
            int responseCode = httpsURLConnection.getResponseCode();
            return new Response(responseCode, OAuthClient.readBody(httpsURLConnection, responseCode));
        } finally {
            httpsURLConnection.disconnect();
        }
    }

    private static void ensureSuccess(Response response, String str) throws Exception {
        String str2;
        if (response.status < 200 || response.status >= 300) {
            if (response.status == 403) {
                str2 = str + ": this account is not allowed to use reset credits.";
            } else if (response.status == 404) {
                str2 = str + ": the reset-credit endpoint is unavailable.";
            } else {
                str2 = str + " (HTTP " + response.status + ").";
            }
            throw new Exception(OAuthClient.readError(response.body, str2));
        }
    }

    private static void notifyUpdated(Context context) {
        try {
            context.sendBroadcast(new Intent(AppConstants.ACTION_RESET_CREDITS_UPDATED).setPackage(context.getPackageName()), "dev.bennett.codexmeter.permission.INTERNAL");
        } catch (RuntimeException e) {
        }
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
