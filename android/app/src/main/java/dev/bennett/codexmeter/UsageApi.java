package dev.bennett.codexmeter;

import android.content.Context;
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
        synchronized (NETWORK_LOCK) {
            installCookieManager();
            AuthTokens authTokensUsableTokens = usableTokens(context);
            Response responseRequestUsage2 = requestUsage(authTokensUsableTokens);
            if (responseRequestUsage2.status == 401) {
                AuthTokens authTokensRefresh = OAuthClient.refresh(authTokensUsableTokens);
                SecureTokenStore.save(context, authTokensRefresh);
                authTokens = authTokensRefresh;
                responseRequestUsage = requestUsage(authTokensRefresh);
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
            usageSnapshot = UsageParser.parse(responseRequestUsage.body, System.currentTimeMillis());
            if (usageSnapshot.fiveHour == null && usageSnapshot.weekly == null) {
                throw new Exception("OpenAI returned no recognizable Codex usage windows.");
            }
            UsageSnapshot previousSnapshot = AppPreferences.loadSnapshot(context);
            if (!AppPreferences.saveSnapshot(context, usageSnapshot)) {
                throw new Exception("Usage was received, but it could not be saved on this device.");
            }
            PhoneWearSync.pushUsage(context, usageSnapshot);
            NowBarManager.onUsageUpdated(context, usageSnapshot);
            ResetNotificationManager.onUsageUpdated(context, previousSnapshot, usageSnapshot);
            try {
                ResetAlertScheduler.scheduleFromSnapshot(context, usageSnapshot);
            } catch (RuntimeException e) {
            }
            try {
                ResetCreditApi.refreshAndCacheLocked(context, authTokens);
            } catch (Exception e2) {
                ResetNotificationManager.onResetCreditSummaryUpdated(context,
                        usageSnapshot.resetCreditsAvailable);
                AppPreferences.setResetCreditsError(context, safeMessage(e2));
            }
        }
        return usageSnapshot;
    }

    static AuthTokens usableTokens(Context context) throws Exception {
        AuthTokens authTokensLoad = SecureTokenStore.load(context);
        if (authTokensLoad == null) {
            throw new Exception("Sign in to ChatGPT first.");
        }
        if (authTokensLoad.shouldRefresh(System.currentTimeMillis())) {
            AuthTokens authTokensRefresh = OAuthClient.refresh(authTokensLoad);
            SecureTokenStore.save(context, authTokensRefresh);
            return authTokensRefresh;
        }
        return authTokensLoad;
    }

    private static Response requestUsage(AuthTokens authTokens) throws Exception {
        HttpsURLConnection httpsURLConnection = (HttpsURLConnection) URI.create(AppConstants.USAGE_URL).toURL().openConnection();
        try {
            applyHeaders(httpsURLConnection, authTokens);
            httpsURLConnection.setRequestMethod("GET");
            int responseCode = httpsURLConnection.getResponseCode();
            return new Response(responseCode, OAuthClient.readBody(httpsURLConnection, responseCode));
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
