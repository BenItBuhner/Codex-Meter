package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * Claude Code subscription sign-in and usage refresh, using the same routes the Claude Code
 * CLI calls for {@code /login} and {@code /usage}.
 *
 * <p>The usage route rate-limits aggressively (HTTP 429 with no Retry-After), so Claude usage
 * is only fetched when the user opens or refreshes the Claude Code tab, never in background
 * jobs.
 */
public final class ClaudeUsageApi {
    private static final UsageProvider PROVIDER = UsageProvider.CLAUDE_CODE;
    /** Opening the tab only re-fetches when the cached data is older than this. */
    public static final long STALE_AFTER_MILLIS = 5L * 60L * 1000L;
    static final Object LOCK = new Object();

    private ClaudeUsageApi() {
    }

    /**
     * Called from the shared refresh job. Scheduled runs fetch Claude usage at most every 30
     * minutes; a widget refresh tap allows one every 5 minutes. Both back off for an hour after
     * a 429. Never throws, so the Codex refresh in the same job always runs.
     */
    public static void refreshInBackgroundIfDue(Context context, boolean userRequested) {
        try {
            if (!SecureTokenStore.isSignedIn(context, PROVIDER)) return;
            long now = System.currentTimeMillis();
            UsageSnapshot cached = ProviderPreferences.loadSnapshot(context, PROVIDER);
            if (!ClaudeCodeApi.isRefreshDue(now,
                    cached == null ? 0L : cached.fetchedAtMillis,
                    ProviderPreferences.getRateLimitedAt(context, PROVIDER),
                    userRequested ? STALE_AFTER_MILLIS
                            : ClaudeCodeApi.BACKGROUND_INTERVAL_MILLIS)) {
                return;
            }
            refreshAndCache(context);
        } catch (Exception exception) {
            DiagnosticLog.warn(context, "scheduler", "claude_background_refresh_failed",
                    "error", exception.getClass().getSimpleName());
        }
    }

    public static boolean isStale(Context context) {
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(context, PROVIDER);
        return snapshot == null
                || System.currentTimeMillis() - snapshot.fetchedAtMillis > STALE_AFTER_MILLIS;
    }

    public static UsageSnapshot refreshAndCache(Context context) throws Exception {
        return refreshAndCache(context, true);
    }

    /**
     * @param autoUse whether to check the saved-reset auto-use rules afterwards; false for the
     *     refresh that follows spending a reset
     */
    static UsageSnapshot refreshAndCache(Context context, boolean autoUse) throws Exception {
        UsageSnapshot fresh = refreshAndCacheLocked(context);
        if (autoUse) ClaudeResetApi.autoUseIfDue(context);
        try {
            ClaudeResetExpiryReceiver.schedule(context);
        } catch (RuntimeException exception) {
            DiagnosticLog.warn(context, "reset", "claude_reset_expiry_schedule_failed",
                    "error", exception.getClass().getSimpleName());
        }
        return fresh;
    }

    private static UsageSnapshot refreshAndCacheLocked(Context context) throws Exception {
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "refresh", "claude_usage_refresh_started");
        try {
            synchronized (LOCK) {
                AuthTokens tokens = usableTokens(context);
                Response usage = get(context, "claude_usage",
                        ClaudeCodeApi.USAGE_WITH_SAVED_RESETS_URL, tokens);
                if (usage.status == 401) {
                    DiagnosticLog.warn(context, "auth", "claude_token_rejected_refreshing");
                    tokens = refresh(context, tokens);
                    SecureTokenStore.save(context, PROVIDER, tokens);
                    usage = get(context, "claude_usage",
                            ClaudeCodeApi.USAGE_WITH_SAVED_RESETS_URL, tokens);
                }
                if (usage.status == 429) {
                    ProviderPreferences.setRateLimitedAt(context, PROVIDER,
                            System.currentTimeMillis());
                }
                if (usage.status < 200 || usage.status >= 300) {
                    throw new Exception(usageError(usage));
                }
                String plan = "";
                try {
                    Response profile = get(context, "claude_profile", ClaudeCodeApi.PROFILE_URL,
                            tokens);
                    if (profile.status >= 200 && profile.status < 300) {
                        plan = ClaudeUsageParser.planLabel(profile.body);
                        ProviderPreferences.setEmail(context, PROVIDER,
                                ClaudeUsageParser.email(profile.body));
                        ProviderPreferences.setOrganization(context, PROVIDER,
                                ClaudeUsageParser.organizationUuid(profile.body));
                    }
                } catch (Exception profileError) {
                    DiagnosticLog.warn(context, "refresh", "claude_profile_failed",
                            "error", profileError.getClass().getSimpleName());
                }
                if (plan.isEmpty()) {
                    UsageSnapshot previous = ProviderPreferences.loadSnapshot(context, PROVIDER);
                    if (previous != null) plan = previous.planType;
                }
                UsageSnapshot snapshot = ClaudeUsageParser.parse(usage.body, plan,
                        System.currentTimeMillis());
                if (snapshot.fiveHour == null && snapshot.weekly == null
                        && snapshot.additionalLimits.isEmpty()) {
                    throw new Exception("Anthropic returned no recognizable Claude Code usage data.");
                }
                if (!ProviderPreferences.saveSnapshot(context, PROVIDER, snapshot)) {
                    throw new Exception("Usage was received, but it could not be saved on this device.");
                }
                UsageHistoryRecorder.record(context, PROVIDER, snapshot);
                ClaudeSavedResets savedResets = ClaudeSavedResets.fromUsageBody(usage.body,
                        snapshot.fetchedAtMillis);
                ProviderPreferences.saveSavedResets(context, PROVIDER, savedResets);
                DiagnosticLog.info(context, "refresh", "claude_usage_refresh_succeeded",
                        "duration_ms", SystemClock.elapsedRealtime() - started,
                        "five_hour", snapshot.fiveHour != null,
                        "weekly", snapshot.weekly != null,
                        "additional_limits", snapshot.additionalLimits.size(),
                        "saved_resets", savedResets == null ? -1
                                : savedResets.availableCount(System.currentTimeMillis()));
                return snapshot;
            }
        } catch (Exception exception) {
            DiagnosticLog.error(context, "refresh", "claude_usage_refresh_failed", exception,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            ProviderPreferences.setLastError(context, PROVIDER, MainActivity.safeMessage(exception));
            throw exception;
        }
    }

    private static String usageError(Response response) {
        switch (response.status) {
            case 401:
                return "Claude rejected the saved sign-in. Sign in with Claude again.";
            case 403:
                return "Claude usage access was denied for this account. Usage limits are only "
                        + "available for Pro, Max, Team, and Enterprise plans.";
            case 404:
                return "The Claude usage endpoint is unavailable or has changed.";
            case 429:
                return "Claude is rate-limiting usage checks. Try again in a few minutes.";
            default:
                return OAuthClient.readError(response.body,
                        "Claude usage refresh failed (HTTP " + response.status + ").");
        }
    }

    static AuthTokens usableTokens(Context context) throws Exception {
        AuthTokens tokens = SecureTokenStore.load(context, PROVIDER);
        if (tokens == null) {
            throw new Exception("Sign in with Claude first.");
        }
        if (tokens.shouldRefresh(System.currentTimeMillis())) {
            DiagnosticLog.info(context, "auth", "claude_token_refresh_due");
            tokens = refresh(context, tokens);
            SecureTokenStore.save(context, PROVIDER, tokens);
        }
        return tokens;
    }

    public static AuthTokens exchangeCode(Context context, String code, String redirectUri,
            String verifier, String state) throws Exception {
        Map<String, String> body = ClaudeCodeApi.codeExchangeBody(code, redirectUri, verifier,
                state);
        Response response = postForm(context, "claude_code_exchange", body);
        if (shouldRetryAsJson(response)) {
            response = postJson(context, "claude_code_exchange", body);
        }
        AuthTokens tokens = parseTokens(response, null);
        ProviderPreferences.setEmail(context, PROVIDER, tokens.email);
        return tokens;
    }

    static AuthTokens refresh(Context context, AuthTokens current) throws Exception {
        Map<String, String> body = ClaudeCodeApi.refreshBody(current.refreshToken);
        Response response = postForm(context, "claude_token_refresh", body);
        if (shouldRetryAsJson(response)) {
            response = postJson(context, "claude_token_refresh", body);
        }
        return current.mergeRefresh(parseTokens(response, current));
    }

    /**
     * Sources disagree on whether the token route wants a form or JSON body. Form is sent first;
     * a 400/415 that is not a definitive grant error gets one JSON retry.
     */
    private static boolean shouldRetryAsJson(Response response) {
        if (response.status != 400 && response.status != 415) return false;
        String error = OAuthClient.readError(response.body, "");
        return !error.contains("invalid_grant") && !error.contains("expired");
    }

    static AuthTokens parseTokens(Response response, AuthTokens previous) throws Exception {
        if (response.status < 200 || response.status >= 300) {
            throw new Exception(OAuthClient.readError(response.body,
                    "Claude sign-in failed (HTTP " + response.status + ")."));
        }
        JSONObject object = new JSONObject(response.body);
        String access = object.optString("access_token", "");
        String refresh = object.optString("refresh_token", "");
        if (refresh.isEmpty() && previous != null) refresh = previous.refreshToken;
        long expiresIn = Math.max(60L, object.optLong("expires_in", 3600L));
        JSONObject account = object.optJSONObject("account");
        JSONObject organization = object.optJSONObject("organization");
        String email = account == null ? "" : account.optString("email_address",
                account.optString("email", ""));
        String orgId = organization == null ? "" : organization.optString("uuid", "");
        AuthTokens tokens = new AuthTokens(access, refresh, "",
                System.currentTimeMillis() + expiresIn * 1000L, orgId, email);
        if (!tokens.isUsable()) {
            throw new Exception("Claude returned incomplete credentials.");
        }
        return tokens;
    }

    static Response get(Context context, String operation, String url, AuthTokens tokens)
            throws Exception {
        HttpsURLConnection connection =
                (HttpsURLConnection) URI.create(url).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "network", "request_started", "operation", operation,
                "method", "GET", "url", DiagnosticSanitizer.safeUrl(url));
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + tokens.accessToken);
            connection.setRequestProperty("anthropic-beta", ClaudeCodeApi.OAUTH_BETA);
            connection.setRequestProperty("User-Agent", AppConstants.userAgent());
            int status = connection.getResponseCode();
            String body = OAuthClient.readBody(connection, status);
            DiagnosticLog.info(context, "network", "request_finished", "operation", operation,
                    "status", status, "duration_ms", SystemClock.elapsedRealtime() - started);
            return new Response(status, body);
        } catch (Exception exception) {
            DiagnosticLog.error(context, "network", "request_failed", exception,
                    "operation", operation,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    private static Response postForm(Context context, String operation, Map<String, String> body)
            throws Exception {
        return post(context, operation, "application/x-www-form-urlencoded",
                ClaudeCodeApi.encode(body).getBytes(StandardCharsets.UTF_8));
    }

    private static Response postJson(Context context, String operation, Map<String, String> body)
            throws Exception {
        return post(context, operation, "application/json",
                new JSONObject(body).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Response post(Context context, String operation, String contentType,
            byte[] payload) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection)
                URI.create(ClaudeCodeApi.TOKEN_URL).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "network", "request_started", "operation", operation,
                "method", "POST", "url", DiagnosticSanitizer.safeUrl(ClaudeCodeApi.TOKEN_URL),
                "content_type", contentType);
        try {
            connection.setRequestMethod("POST");
            // The token route has been slow during incidents; Claude Code's 15s cap caused
            // failed logins, so allow longer here.
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", AppConstants.userAgent());
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            String body = OAuthClient.readBody(connection, status);
            DiagnosticLog.info(context, "network", "request_finished", "operation", operation,
                    "status", status, "duration_ms", SystemClock.elapsedRealtime() - started);
            return new Response(status, body);
        } catch (Exception exception) {
            DiagnosticLog.error(context, "network", "request_failed", exception,
                    "operation", operation,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
