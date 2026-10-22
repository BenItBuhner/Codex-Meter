package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Endpoints and request shapes the Cursor editor and CLI use for sign-in and the usage meter.
 * Cursor does not document them publicly; they match open-source trackers (CodexBar, quotabar,
 * cpa-cursor-plugin) and the editor's own bundle, so everything that touches them is defensive.
 *
 * <p>Sign-in needs no redirect URI: the browser opens {@code loginDeepControl} with a PKCE
 * challenge and a random id, and the app polls {@code auth/poll} with the verifier until Cursor
 * hands back tokens.
 */
public final class CursorApi {
    public static final String LOGIN_URL = "https://cursor.com/loginDeepControl";
    public static final String POLL_URL = "https://api2.cursor.sh/auth/poll";
    public static final String TOKEN_URL = "https://api2.cursor.sh/oauth/token";
    public static final String USAGE_URL =
            "https://api2.cursor.sh/aiserver.v1.DashboardService/GetCurrentPeriodUsage";
    public static final String PROFILE_URL = "https://api2.cursor.sh/auth/full_stripe_profile";
    /** Connect RPC that returns the signed-in account's email; the profile above has none. */
    public static final String EMAIL_URL =
            "https://api2.cursor.sh/aiserver.v1.AuthService/GetEmail";
    /** Cursor's production auth client id, used by the editor for token refresh. */
    public static final String CLIENT_ID = "KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB";

    /** The editor polls every 500 ms; a phone does fine with one poll per second. */
    public static final long POLL_INTERVAL_MILLIS = 1000L;
    /** Give up on a sign-in the user never finished after five minutes. */
    public static final long POLL_TIMEOUT_MILLIS = 5L * 60L * 1000L;
    /** Background refresh spacing; Cursor's meter only moves as you use the editor. */
    public static final long BACKGROUND_INTERVAL_MILLIS = 15L * 60L * 1000L;
    /** Access tokens last about 60 days; refresh when fewer than this many remain. */
    public static final long REFRESH_MARGIN_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private CursorApi() {
    }

    /** One browser sign-in attempt: the PKCE pair plus the id Cursor uses to match the poll. */
    public static final class LoginRequest {
        public final String verifier;
        public final String challenge;
        public final String uuid;

        LoginRequest(String verifier, String challenge, String uuid) {
            this.verifier = verifier;
            this.challenge = challenge;
            this.uuid = uuid;
        }

        public String loginUrl() {
            return LOGIN_URL + "?challenge=" + challenge + "&uuid=" + uuid + "&mode=login";
        }

        public String pollUrl() {
            return POLL_URL + "?uuid=" + uuid + "&verifier=" + verifier;
        }
    }

    public static LoginRequest newLoginRequest() throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = base64Url(random);
        return new LoginRequest(verifier, challengeFor(verifier), UUID.randomUUID().toString());
    }

    /** {@code base64url(SHA-256(verifier))}, the same S256 challenge the editor sends. */
    public static String challengeFor(String verifier) throws Exception {
        return base64Url(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Tokens from a finished poll or a refresh. Missing values are empty strings. */
    public static final class Tokens {
        public final String accessToken;
        public final String refreshToken;
        public final String authId;
        public final boolean shouldLogout;

        Tokens(String accessToken, String refreshToken, String authId, boolean shouldLogout) {
            this.accessToken = accessToken == null ? "" : accessToken;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.authId = authId == null ? "" : authId;
            this.shouldLogout = shouldLogout;
        }
    }

    /**
     * Reads {@code auth/poll} ({@code accessToken}) and {@code oauth/token} ({@code access_token})
     * bodies. The editor stores a refreshed access token as both tokens, so a missing refresh
     * token falls back to the access token.
     */
    public static Tokens parseTokens(String body) {
        try {
            JSONObject object = new JSONObject(body == null ? "" : body);
            String access = firstString(object, "accessToken", "access_token");
            String refresh = firstString(object, "refreshToken", "refresh_token");
            if (refresh.isEmpty()) refresh = access;
            return new Tokens(access, refresh, firstString(object, "authId", "auth_id"),
                    object.optBoolean("shouldLogout", false));
        } catch (Exception ignored) {
            return new Tokens("", "", "", false);
        }
    }

    public static String refreshBody(String refreshToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("grant_type", "refresh_token");
            body.put("client_id", CLIENT_ID);
            body.put("refresh_token", refreshToken == null ? "" : refreshToken);
            return body.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Expiry from the JWT {@code exp} claim in epoch millis, or 0 when it can't be read. */
    public static long expiresAtMillis(String jwt) {
        JSONObject claims = claims(jwt);
        long exp = claims == null ? 0L : claims.optLong("exp", 0L);
        return exp > 0L ? exp * 1000L : 0L;
    }

    /** Email claim when Cursor includes one in the access token. */
    public static String email(String jwt) {
        JSONObject claims = claims(jwt);
        String email = claims == null ? "" : claims.optString("email", "");
        return email.contains("@") ? email : "";
    }

    /**
     * Background-refresh pacing. Cursor has no published rate limit, so this only keeps the
     * job from calling the route more often than the meter can move.
     */
    public static boolean isRefreshDue(long now, long fetchedAt, long minIntervalMillis) {
        return fetchedAt <= 0L || now < fetchedAt || now - fetchedAt >= minIntervalMillis;
    }

    /**
     * Cursor answers some rejected sessions with HTTP 200 and an error body, so auth failures
     * are recognized by body as well as by status.
     */
    public static boolean isUnauthenticated(int status, String body) {
        if (status == 401) return true;
        if (body == null || body.isEmpty()) return false;
        try {
            JSONObject object = new JSONObject(body);
            if (object.optBoolean("shouldLogout", false)) return true;
            String code = (object.optString("code", "") + " " + object.optString("error", ""))
                    .toLowerCase(Locale.ROOT);
            return code.contains("not_authenticated") || code.contains("unauthenticated");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Vercel's bot checkpoint is an HTML 403, not a sign-in problem. */
    public static boolean isBotCheckpoint(int status, String body) {
        return status == 403 && body != null && body.contains("Vercel Security Checkpoint");
    }

    private static JSONObject claims(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            return new JSONObject(new String(Base64.getUrlDecoder().decode(pad(parts[1])),
                    StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "====".substring(remainder);
    }

    private static String firstString(JSONObject object, String first, String second) {
        String value = object.optString(first, "");
        if (value.isEmpty() || "null".equals(value)) value = object.optString(second, "");
        return "null".equals(value) ? "" : value;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
