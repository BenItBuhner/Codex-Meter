package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Pure parsing for device-code sign-in (RFC 8628 and the camelCase variants some providers
 * use) and for token responses. Shared by {@link ProviderClients} and the self-tests.
 */
public final class DeviceAuth {
    private DeviceAuth() {
    }

    /** What the sign-in screen shows: a code to type on the provider's page. */
    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        /** Page that already has the code filled in, when the provider gives one. */
        public final String verificationUriComplete;
        public final long intervalSeconds;
        public final long expiresInSeconds;

        public DeviceCode(String deviceCode, String userCode, String verificationUri,
                String verificationUriComplete, long intervalSeconds, long expiresInSeconds) {
            this.deviceCode = deviceCode == null ? "" : deviceCode;
            this.userCode = userCode == null ? "" : userCode;
            this.verificationUri = verificationUri == null ? "" : verificationUri;
            this.verificationUriComplete =
                    verificationUriComplete == null ? "" : verificationUriComplete;
            this.intervalSeconds = Math.max(1L, Math.min(30L, intervalSeconds <= 0 ? 5L
                    : intervalSeconds));
            this.expiresInSeconds = expiresInSeconds <= 0 ? 900L : expiresInSeconds;
        }

        /** The page to open: the pre-filled one when available. */
        public String openUrl() {
            return verificationUriComplete.isEmpty() ? verificationUri : verificationUriComplete;
        }

        public boolean isValid() {
            return !deviceCode.isEmpty() && !verificationUri.isEmpty();
        }
    }

    public static DeviceCode parseDeviceCode(String body) {
        JSONObject object = json(body);
        return new DeviceCode(
                string(object, "device_code", "deviceCode", "code", "device_auth_id"),
                string(object, "user_code", "userCode", "code"),
                string(object, "verification_uri", "verificationUri", "verification_url",
                        "verificationUrl"),
                string(object, "verification_uri_complete", "verificationUriComplete",
                        "verification_url_complete", "verificationUrlComplete"),
                object.optLong("interval", 5L),
                object.optLong("expires_in", object.optLong("expiresIn", 900L)));
    }

    /** Outcome of one poll of a device-code token endpoint. */
    public enum PollState { PENDING, SLOW_DOWN, DENIED, EXPIRED, DONE, FAILED }

    public static final class Poll {
        public final PollState state;
        public final Tokens tokens;
        public final String message;

        Poll(PollState state, Tokens tokens, String message) {
            this.state = state;
            this.tokens = tokens;
            this.message = message == null ? "" : message;
        }
    }

    /** Reads a token endpoint's reply while the user is still approving the code. */
    public static Poll parsePoll(int status, String body) {
        JSONObject object = json(body);
        String error = string(object, "error", "code").toLowerCase(Locale.ROOT);
        Object nested = object.opt("error");
        if (nested instanceof JSONObject) {
            error = string((JSONObject) nested, "code", "type", "message").toLowerCase(Locale.ROOT);
        }
        Tokens tokens = parseTokens(body);
        if (status >= 200 && status < 300 && !tokens.accessToken.isEmpty()) {
            return new Poll(PollState.DONE, tokens, "");
        }
        if (error.contains("authorization_pending") || error.contains("pending")
                || status == 202 || status == 428) {
            return new Poll(PollState.PENDING, null, "");
        }
        if (error.contains("slow_down")) return new Poll(PollState.SLOW_DOWN, null, "");
        if (error.contains("access_denied") || error.contains("denied") || status == 403) {
            return new Poll(PollState.DENIED, null, "The sign-in was declined.");
        }
        if (error.contains("expired") || status == 410) {
            return new Poll(PollState.EXPIRED, null, "The code expired. Start again.");
        }
        String description = string(object, "error_description", "message");
        return new Poll(PollState.FAILED, null, description.isEmpty()
                ? "Sign-in failed (HTTP " + status + ")." : description);
    }

    /** Tokens from any token endpoint. Missing values are empty; expiry is in seconds. */
    public static final class Tokens {
        public final String accessToken;
        public final String refreshToken;
        public final String idToken;
        public final long expiresInSeconds;
        public final String email;

        public Tokens(String accessToken, String refreshToken, String idToken,
                long expiresInSeconds, String email) {
            this.accessToken = accessToken == null ? "" : accessToken;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.idToken = idToken == null ? "" : idToken;
            this.expiresInSeconds = Math.max(0L, expiresInSeconds);
            this.email = email == null ? "" : email;
        }

        /**
         * Stored form. Tokens that never expire keep the access token as the refresh token
         * too, because {@link AuthTokens#isUsable} needs both.
         */
        public AuthTokens toAuthTokens(long nowMillis, String accountId) {
            long expires = expiresInSeconds > 0 ? nowMillis + expiresInSeconds * 1000L : 0L;
            return new AuthTokens(accessToken, refreshToken.isEmpty() ? accessToken : refreshToken,
                    idToken, expires, accountId, email);
        }
    }

    public static Tokens parseTokens(String body) {
        JSONObject object = json(body);
        JSONObject data = object.optJSONObject("data");
        if (data != null && !object.has("access_token") && !object.has("accessToken")) {
            object = data;
        }
        String email = string(object, "email");
        JSONObject user = object.optJSONObject("user");
        if (email.isEmpty() && user != null) email = string(user, "email");
        JSONObject userInfo = object.optJSONObject("userInfo");
        if (email.isEmpty() && userInfo != null) email = string(userInfo, "email");
        long expiresIn = object.optLong("expires_in", object.optLong("expiresIn", 0L));
        if (expiresIn <= 0) {
            long expiresAt = ProviderUsageParsers.epochSeconds(
                    ProviderUsageParsers.first(object, "expiresAt", "expires_at"));
            if (expiresAt > 0) expiresIn = Math.max(1L, expiresAt - System.currentTimeMillis() / 1000L);
        }
        return new Tokens(
                string(object, "access_token", "accessToken", "token", "key", "api_key"),
                string(object, "refresh_token", "refreshToken"),
                string(object, "id_token", "idToken"),
                expiresIn,
                email.contains("@") ? email : "");
    }

    /** Random URL-safe value for PKCE verifiers, states and one-time ids. */
    public static String randomToken(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** {@code base64url(SHA-256(verifier))}. */
    public static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Pulls the useful part out of whatever the user pasted: a bare token, a JSON object, or a
     * URL with the value in its query or fragment.
     */
    public static String extractPasted(String pasted, String... keys) {
        String text = pasted == null ? "" : pasted.trim();
        if (text.isEmpty()) return "";
        if (text.startsWith("{")) {
            JSONObject object = json(text);
            String value = string(object, keys);
            return value.isEmpty() ? text : value;
        }
        int marker = Math.max(text.indexOf('?'), text.indexOf('#'));
        if (text.startsWith("http") && marker > 0) {
            String query = text.substring(marker + 1);
            for (String pair : query.split("[&#]")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                String name = pair.substring(0, equals);
                for (String key : keys) {
                    if (key.equals(name)) {
                        try {
                            return java.net.URLDecoder.decode(pair.substring(equals + 1),
                                    "UTF-8");
                        } catch (Exception ignored) {
                            return pair.substring(equals + 1);
                        }
                    }
                }
            }
        }
        return text.replaceAll("\\s+", "");
    }

    static JSONObject json(String body) {
        try {
            return new JSONObject(body == null ? "" : body.trim());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static String string(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).isEmpty()
                    && !"null".equals(value)) {
                return (String) value;
            }
        }
        return "";
    }
}
