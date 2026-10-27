package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;

/**
 * Cursor sign-in and usage refresh, using the routes the Cursor editor and CLI call for login
 * and the in-editor usage meter.
 */
public final class CursorUsageApi {
    private static final UsageProvider PROVIDER = UsageProvider.CURSOR;
    /** Opening the tab only re-fetches when the cached data is older than this. */
    public static final long STALE_AFTER_MILLIS = 5L * 60L * 1000L;
    private static final Object LOCK = new Object();

    private CursorUsageApi() {
    }

    /** Called from the shared refresh job. Never throws, so other providers always refresh. */
    public static void refreshInBackgroundIfDue(Context context, boolean userRequested) {
        try {
            if (!SecureTokenStore.isSignedIn(context, PROVIDER)) return;
            UsageSnapshot cached = ProviderPreferences.loadSnapshot(context, PROVIDER);
            if (!CursorApi.isRefreshDue(System.currentTimeMillis(),
                    cached == null ? 0L : cached.fetchedAtMillis,
                    userRequested ? STALE_AFTER_MILLIS : CursorApi.BACKGROUND_INTERVAL_MILLIS)) {
                return;
            }
            refreshAndCache(context);
        } catch (Exception exception) {
            DiagnosticLog.warn(context, "scheduler", "cursor_background_refresh_failed",
                    "error", exception.getClass().getSimpleName());
        }
    }

    public static boolean isStale(Context context) {
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(context, PROVIDER);
        return snapshot == null
                || System.currentTimeMillis() - snapshot.fetchedAtMillis > STALE_AFTER_MILLIS;
    }

    public static UsageSnapshot refreshAndCache(Context context) throws Exception {
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "refresh", "cursor_usage_refresh_started");
        try {
            synchronized (LOCK) {
                AuthTokens tokens = usableTokens(context);
                Response usage = request(context, "cursor_usage", CursorApi.USAGE_URL, "{}",
                        tokens.accessToken);
                if (CursorApi.isUnauthenticated(usage.status, usage.body)) {
                    DiagnosticLog.warn(context, "auth", "cursor_token_rejected_refreshing");
                    tokens = refresh(context, tokens);
                    SecureTokenStore.save(context, PROVIDER, tokens);
                    usage = request(context, "cursor_usage", CursorApi.USAGE_URL, "{}",
                            tokens.accessToken);
                }
                if (CursorApi.isUnauthenticated(usage.status, usage.body)) {
                    throw new Exception("Cursor rejected the saved sign-in. Sign in with Cursor again.");
                }
                if (usage.status < 200 || usage.status >= 300) {
                    throw new Exception(usageError(usage));
                }
                String plan = "";
                try {
                    Response profile = request(context, "cursor_profile", CursorApi.PROFILE_URL,
                            null, tokens.accessToken);
                    if (profile.status >= 200 && profile.status < 300) {
                        plan = CursorUsageParser.planLabel(profile.body);
                        String email = CursorUsageParser.email(profile.body);
                        if (!email.isEmpty()) ProviderPreferences.setEmail(context, PROVIDER, email);
                    }
                } catch (Exception profileError) {
                    DiagnosticLog.warn(context, "refresh", "cursor_profile_failed",
                            "error", profileError.getClass().getSimpleName());
                }
                if (ProviderPreferences.getEmail(context, PROVIDER).isEmpty()) {
                    fetchEmail(context, tokens.accessToken);
                }
                if (plan.isEmpty()) {
                    UsageSnapshot previous = ProviderPreferences.loadSnapshot(context, PROVIDER);
                    if (previous != null) plan = previous.planType;
                }
                UsageSnapshot snapshot = CursorUsageParser.parse(usage.body, plan,
                        System.currentTimeMillis());
                if (snapshot.weekly == null && snapshot.monthly == null
                        && snapshot.additionalLimits.isEmpty()) {
                    throw new Exception("Cursor returned no recognizable usage data.");
                }
                if (!ProviderPreferences.saveSnapshot(context, PROVIDER, snapshot)) {
                    throw new Exception("Usage was received, but it could not be saved on this device.");
                }
                UsageHistoryRecorder.record(context, PROVIDER, snapshot);
                DiagnosticLog.info(context, "refresh", "cursor_usage_refresh_succeeded",
                        "duration_ms", SystemClock.elapsedRealtime() - started,
                        "plan_window", snapshot.weekly != null ? "weekly"
                                : snapshot.monthly != null ? "monthly" : "none",
                        "additional_limits", snapshot.additionalLimits.size());
                return snapshot;
            }
        } catch (Exception exception) {
            DiagnosticLog.error(context, "refresh", "cursor_usage_refresh_failed", exception,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            ProviderPreferences.setLastError(context, PROVIDER, MainActivity.safeMessage(exception));
            throw exception;
        }
    }

    private static String usageError(Response response) {
        if (CursorApi.isBotCheckpoint(response.status, response.body)) {
            return "Cursor's bot protection blocked the usage check. Try again in a few minutes.";
        }
        switch (response.status) {
            case 403:
                return "Cursor denied usage access for this account.";
            case 404:
                return "The Cursor usage endpoint is unavailable or has changed.";
            case 429:
                return "Cursor is rate-limiting usage checks. Try again in a few minutes.";
            default:
                return OAuthClient.readError(response.body,
                        "Cursor usage refresh failed (HTTP " + response.status + ").");
        }
    }

    static AuthTokens usableTokens(Context context) throws Exception {
        AuthTokens tokens = SecureTokenStore.load(context, PROVIDER);
        if (tokens == null) {
            throw new Exception("Sign in with Cursor first.");
        }
        if (tokens.expiresAtMillis > 0L && tokens.expiresAtMillis
                <= System.currentTimeMillis() + CursorApi.REFRESH_MARGIN_MILLIS) {
            DiagnosticLog.info(context, "auth", "cursor_token_refresh_due");
            try {
                tokens = refresh(context, tokens);
                SecureTokenStore.save(context, PROVIDER, tokens);
            } catch (Exception refreshError) {
                // A token that has not expired yet still works; only fail once it has.
                if (tokens.expiresAtMillis <= System.currentTimeMillis()) throw refreshError;
                DiagnosticLog.warn(context, "auth", "cursor_early_refresh_failed",
                        "error", refreshError.getClass().getSimpleName());
            }
        }
        return tokens;
    }

    static AuthTokens refresh(Context context, AuthTokens current) throws Exception {
        Response response = request(context, "cursor_token_refresh", CursorApi.TOKEN_URL,
                CursorApi.refreshBody(current.refreshToken), null);
        CursorApi.Tokens parsed = CursorApi.parseTokens(response.body);
        if (parsed.shouldLogout || response.status == 401) {
            throw new Exception("Your Cursor sign-in expired. Sign in with Cursor again.");
        }
        if (response.status < 200 || response.status >= 300 || parsed.accessToken.isEmpty()) {
            throw new Exception(OAuthClient.readError(response.body,
                    "Cursor sign-in refresh failed (HTTP " + response.status + ")."));
        }
        return current.mergeRefresh(toAuthTokens(parsed));
    }

    /**
     * Polls until the user finishes the browser sign-in. Cursor answers 404 while it waits and
     * 200 with tokens once the login page is approved.
     */
    public static AuthTokens pollForTokens(Context context, CursorApi.LoginRequest login,
            BooleanSupplier cancelled) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + CursorApi.POLL_TIMEOUT_MILLIS;
        int failures = 0;
        DiagnosticLog.info(context, "auth", "cursor_poll_started");
        while (!cancelled.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new Exception("Sign-in timed out. Start again from the app.");
            }
            try {
                // The verifier is a one-time secret, so the poll is never logged with its URL.
                Response response = request(null, "cursor_poll", login.pollUrl(), null, null);
                if (response.status == 200) {
                    CursorApi.Tokens parsed = CursorApi.parseTokens(response.body);
                    if (!parsed.accessToken.isEmpty()) {
                        AuthTokens tokens = toAuthTokens(parsed);
                        if (!tokens.email.isEmpty()) {
                            ProviderPreferences.setEmail(context, PROVIDER, tokens.email);
                        }
                        return tokens;
                    }
                    failures++;
                } else if (response.status == 403) {
                    throw new Exception("Cursor refused this sign-in. Start again from the app.");
                } else if (response.status != 404) {
                    failures++;
                } else {
                    failures = 0;
                }
            } catch (java.io.IOException network) {
                failures++;
            }
            if (failures >= 5) {
                throw new Exception("Cursor sign-in could not be confirmed. Check your connection "
                        + "and try again.");
            }
            Thread.sleep(CursorApi.POLL_INTERVAL_MILLIS);
        }
        throw new Exception("Sign-in cancelled.");
    }

    static AuthTokens toAuthTokens(CursorApi.Tokens parsed) {
        long expires = CursorApi.expiresAtMillis(parsed.accessToken);
        if (expires <= 0L) expires = System.currentTimeMillis() + 24L * 60L * 60L * 1000L;
        return new AuthTokens(parsed.accessToken, parsed.refreshToken, "", expires,
                parsed.authId, CursorApi.email(parsed.accessToken));
    }

    /** POST with a JSON body when {@code body} is set, otherwise GET. */
    /** Saves the account email so Settings → Accounts can show it like other providers. */
    private static void fetchEmail(Context context, String accessToken) {
        try {
            Response response = request(context, "cursor_email", CursorApi.EMAIL_URL, "{}",
                    accessToken);
            if (response.status >= 200 && response.status < 300) {
                String email = CursorUsageParser.email(response.body);
                if (!email.isEmpty()) ProviderPreferences.setEmail(context, PROVIDER, email);
            }
        } catch (Exception emailError) {
            DiagnosticLog.warn(context, "refresh", "cursor_email_failed",
                    "error", emailError.getClass().getSimpleName());
        }
    }

    private static Response request(Context context, String operation, String url, String body,
            String bearer) throws Exception {
        HttpsURLConnection connection =
                (HttpsURLConnection) URI.create(url).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        String method = body == null ? "GET" : "POST";
        if (context != null) {
            DiagnosticLog.info(context, "network", "request_started", "operation", operation,
                    "method", method, "url", DiagnosticSanitizer.safeUrl(url));
        }
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", AppConstants.userAgent());
            if (bearer != null) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            byte[] payload = null;
            if (body != null) {
                payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Connect-Protocol-Version", "1");
                connection.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            int status = connection.getResponseCode();
            String responseBody = OAuthClient.readBody(connection, status);
            if (context != null) {
                DiagnosticLog.info(context, "network", "request_finished",
                        "operation", operation, "status", status,
                        "duration_ms", SystemClock.elapsedRealtime() - started);
            }
            return new Response(status, responseBody);
        } catch (Exception exception) {
            if (context != null) {
                DiagnosticLog.error(context, "network", "request_failed", exception,
                        "operation", operation,
                        "duration_ms", SystemClock.elapsedRealtime() - started);
            }
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
