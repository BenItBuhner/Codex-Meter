package dev.bennett.codexmeter;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints and request shapes Claude Code itself uses for its subscription sign-in and the
 * {@code /usage} screen. These are the same routes the CLI calls; Anthropic does not document
 * them publicly, so everything that touches them is defensive.
 */
public final class ClaudeCodeApi {
    /** Claude Code's public OAuth client (PKCE, no secret). */
    public static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    public static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    public static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    public static final String USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
    public static final String PROFILE_URL = "https://api.anthropic.com/api/oauth/profile";
    /**
     * The usage route with Claude Code's saved-reset status added ({@code cedar_ember} block).
     * One request keeps the rate-limited usage route at its usual load.
     */
    public static final String USAGE_WITH_SAVED_RESETS_URL = USAGE_URL + "?cedar_ember=1";
    /** Spends a saved reset; {@code %s} is the organization UUID. */
    public static final String RESET_RATE_LIMITS_URL =
            "https://api.anthropic.com/api/organizations/%s/reset_rate_limits";
    /** Required beta header for OAuth-token access to api.anthropic.com. */
    public static final String OAUTH_BETA = "oauth-2025-04-20";
    /** Read-only scopes: profile for the plan badge, inference matches Claude Code's grant. */
    public static final String SCOPE = "user:profile user:inference";
    /** Claude Code's loopback callback path; any localhost port is accepted. */
    public static final String CALLBACK_PATH = "/callback";

    /** Scheduled background refresh spacing; the usage route throttles frequent polling. */
    public static final long BACKGROUND_INTERVAL_MILLIS = 30L * 60L * 1000L;
    /** After an HTTP 429, background refresh waits this long before trying again. */
    public static final long RATE_LIMIT_BACKOFF_MILLIS = 60L * 60L * 1000L;

    private ClaudeCodeApi() {
    }

    /** Background-refresh pacing: honor the 429 back-off, then the minimum spacing. */
    public static boolean isRefreshDue(long now, long fetchedAt, long rateLimitedAt,
            long minIntervalMillis) {
        if (rateLimitedAt > 0L && now >= rateLimitedAt
                && now - rateLimitedAt < RATE_LIMIT_BACKOFF_MILLIS) {
            return false;
        }
        return fetchedAt <= 0L || now < fetchedAt || now - fetchedAt >= minIntervalMillis;
    }

    public static String resetRateLimitsUrl(String organizationUuid) {
        String org = organizationUuid == null ? "" : organizationUuid.trim();
        if (!org.matches("^[A-Za-z0-9-]{1,64}$")) {
            throw new IllegalArgumentException("Missing Claude organization.");
        }
        return String.format(java.util.Locale.ROOT, RESET_RATE_LIMITS_URL, org);
    }

    public static String redirectUri(int port) {
        return "http://localhost:" + port + CALLBACK_PATH;
    }

    public static String authorizeUrl(String redirectUri, String challenge, String state)
            throws UnsupportedEncodingException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "true");
        params.put("client_id", CLIENT_ID);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", SCOPE);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("state", state);
        return AUTHORIZE_URL + "?" + encode(params);
    }

    public static Map<String, String> codeExchangeBody(String code, String redirectUri,
            String verifier, String state) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", stripStateSuffix(code));
        body.put("redirect_uri", redirectUri);
        body.put("client_id", CLIENT_ID);
        body.put("code_verifier", verifier);
        body.put("state", state);
        return body;
    }

    public static Map<String, String> refreshBody(String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", CLIENT_ID);
        return body;
    }

    /** Claude's manual flow shows {@code code#state}; only the part before '#' is the code. */
    public static String stripStateSuffix(String code) {
        if (code == null) return "";
        int hash = code.indexOf('#');
        return (hash >= 0 ? code.substring(0, hash) : code).trim();
    }

    public static String encode(Map<String, String> params) throws UnsupportedEncodingException {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            query.append('=');
            query.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return query.toString();
    }
}
