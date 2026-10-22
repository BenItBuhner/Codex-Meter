package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

/**
 * Spends Claude Code saved resets with the same call the Claude Code CLI makes:
 * {@code POST api.anthropic.com/api/organizations/{org}/reset_rate_limits} with
 * {@code {"program":"cedar_ember","grant_id":...,"request_id":...}}.
 */
public final class ClaudeResetApi {
    private static final UsageProvider PROVIDER = UsageProvider.CLAUDE_CODE;
    private static final Object CLAIM_LOCK = new Object();
    private static final String RULE_EXPIRY = "expiry";
    private static final String RULE_LOW_USAGE = "low_usage";

    private ClaudeResetApi() {
    }

    /** Uses one reset from {@code grantId}, then reloads usage so the dashboard shows it. */
    public static ClaudeSavedResets.ClaimResult use(Context context, String grantId,
            String source) {
        Context app = context.getApplicationContext() == null ? context
                : context.getApplicationContext();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(app, "user", "claude_saved_reset_use_requested", "source", source);
        ClaudeSavedResets.ClaimResult result;
        synchronized (CLAIM_LOCK) {
            result = claim(app, grantId);
            if (result.applied()) {
                ClaudeSavedResets cached = ProviderPreferences.loadSavedResets(app, PROVIDER);
                if (cached != null) {
                    ProviderPreferences.saveSavedResets(app, PROVIDER, cached.afterUse(grantId));
                }
                try {
                    ClaudeUsageApi.refreshAndCache(app, false);
                } catch (Exception exception) {
                    DiagnosticLog.warn(app, "refresh", "claude_refresh_after_reset_failed",
                            "error", exception.getClass().getSimpleName());
                }
            }
        }
        try {
            WidgetRenderer.updateAll(app);
        } catch (RuntimeException ignored) {
        }
        DiagnosticLog.info(app, "user", "claude_saved_reset_use_finished",
                "result", result.result, "reason", result.reason,
                "duration_ms", SystemClock.elapsedRealtime() - started);
        return result;
    }

    private static ClaudeSavedResets.ClaimResult claim(Context context, String grantId) {
        try {
            byte[] payload = ClaudeSavedResets.claimBody(grantId,
                    UUID.randomUUID().toString()).toString().getBytes(StandardCharsets.UTF_8);
            AuthTokens tokens;
            String org;
            synchronized (ClaudeUsageApi.LOCK) {
                tokens = ClaudeUsageApi.usableTokens(context);
                org = organization(context, tokens);
            }
            String url = ClaudeCodeApi.resetRateLimitsUrl(org);
            ClaudeUsageApi.Response response = post(context, url, tokens, payload);
            if (response.status == 401) {
                synchronized (ClaudeUsageApi.LOCK) {
                    tokens = ClaudeUsageApi.refresh(context, tokens);
                    SecureTokenStore.save(context, PROVIDER, tokens);
                }
                response = post(context, url, tokens, payload);
            }
            if (response.status == 429) {
                return new ClaudeSavedResets.ClaimResult(
                        ClaudeSavedResets.ClaimResult.RATE_LIMITED, "", -1, null);
            }
            if (response.status == 401 || response.status == 403) {
                return new ClaudeSavedResets.ClaimResult(
                        ClaudeSavedResets.ClaimResult.AUTH_ERROR, "", -1, null);
            }
            if (response.status < 200 || response.status >= 300) {
                return new ClaudeSavedResets.ClaimResult(ClaudeSavedResets.ClaimResult.ERROR,
                        "http_" + response.status, -1, null);
            }
            return ClaudeSavedResets.ClaimResult.parse(response.body);
        } catch (Exception exception) {
            DiagnosticLog.error(context, "network", "claude_saved_reset_claim_failed", exception);
            return new ClaudeSavedResets.ClaimResult(ClaudeSavedResets.ClaimResult.ERROR,
                    exception.getClass().getSimpleName(), -1, null);
        }
    }

    /** The organization from the sign-in, else from the profile route. */
    private static String organization(Context context, AuthTokens tokens) throws Exception {
        if (tokens.accountId != null && !tokens.accountId.isEmpty()) return tokens.accountId;
        String cached = ProviderPreferences.getOrganization(context, PROVIDER);
        if (!cached.isEmpty()) return cached;
        ClaudeUsageApi.Response profile = ClaudeUsageApi.get(context, "claude_profile",
                ClaudeCodeApi.PROFILE_URL, tokens);
        String uuid = profile.status >= 200 && profile.status < 300
                ? ClaudeUsageParser.organizationUuid(profile.body) : "";
        if (uuid.isEmpty()) throw new Exception("Could not find your Claude organization.");
        ProviderPreferences.setOrganization(context, PROVIDER, uuid);
        return uuid;
    }

    /**
     * Runs the automatic-use rules on the status the last refresh just stored. Never throws, so
     * the refresh that called it still succeeds.
     */
    static void autoUseIfDue(Context context) {
        try {
            boolean expiry = ResetAlertPreferences.claudeSavedResetExpiryEnabled(context);
            boolean lowUsage = ResetAlertPreferences.claudeSavedResetLowUsageEnabled(context);
            if (!expiry && !lowUsage) return;
            ClaudeResetAutoUse decision = ClaudeResetAutoUse.evaluate(
                    ProviderPreferences.loadSavedResets(context, PROVIDER),
                    ProviderPreferences.loadSnapshot(context, PROVIDER),
                    expiry, ResetAlertPreferences.claudeSavedResetExpiryLeadMillis(context),
                    lowUsage, ResetAlertPreferences.claudeSavedResetThreshold(context),
                    ResetAlertPreferences.claudeSavedResetMetric(context),
                    ProviderPreferences.getSavedResetAttempt(context, PROVIDER, RULE_EXPIRY),
                    ProviderPreferences.getSavedResetAttempt(context, PROVIDER, RULE_LOW_USAGE),
                    System.currentTimeMillis());
            if (!decision.fires()) {
                if (!ClaudeResetAutoUse.WAIT.equals(decision.outcome)) {
                    DiagnosticLog.info(context, "reset", "claude_saved_reset_auto_skipped",
                            "outcome", decision.outcome);
                }
                return;
            }
            boolean expiring = ClaudeResetAutoUse.FIRE_EXPIRING.equals(decision.outcome);
            String rule = expiring ? RULE_EXPIRY : RULE_LOW_USAGE;
            ProviderPreferences.setSavedResetAttempt(context, PROVIDER, rule,
                    decision.attemptKey);
            ClaudeSavedResets.ClaimResult result = use(context, decision.grant.id,
                    decision.outcome);
            if (result.retryable()) {
                // Let the next refresh try again; the notice below still says what happened.
                ProviderPreferences.setSavedResetAttempt(context, PROVIDER, rule, "");
            }
            ResetNotificationManager.showClaudeSavedResetNotification(context, decision,
                    result);
        } catch (RuntimeException exception) {
            DiagnosticLog.warn(context, "reset", "claude_saved_reset_auto_failed",
                    "error", exception.getClass().getSimpleName());
        }
    }

    private static ClaudeUsageApi.Response post(Context context, String url, AuthTokens tokens,
            byte[] payload) throws Exception {
        HttpsURLConnection connection =
                (HttpsURLConnection) URI.create(url).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "network", "request_started",
                "operation", "claude_saved_reset_claim", "method", "POST",
                "url", DiagnosticSanitizer.safeUrl(url));
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + tokens.accessToken);
            connection.setRequestProperty("anthropic-beta", ClaudeCodeApi.OAUTH_BETA);
            connection.setRequestProperty("User-Agent", ClaudeCodeApi.CLI_USER_AGENT);
            connection.setRequestProperty("x-app", ClaudeCodeApi.CLI_APP);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            String body = OAuthClient.readBody(connection, status);
            DiagnosticLog.info(context, "network", "request_finished",
                    "operation", "claude_saved_reset_claim", "status", status,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            return new ClaudeUsageApi.Response(status, body);
        } finally {
            connection.disconnect();
        }
    }
}
