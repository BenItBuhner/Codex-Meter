package dev.bennett.codexmeter;

import org.json.JSONObject;

/** OAuth 2.0 user tokens for read-only X API access. */
public final class XOAuthTokens {
    public final String accessToken;
    public final String refreshToken;
    public final long expiresAtMillis;

    XOAuthTokens(String accessToken, String refreshToken, long expiresAtMillis) {
        this.accessToken = safe(accessToken);
        this.refreshToken = safe(refreshToken);
        this.expiresAtMillis = Math.max(0L, expiresAtMillis);
    }

    boolean isUsable() {
        return !accessToken.isEmpty();
    }

    boolean shouldRefresh(long now) {
        return !refreshToken.isEmpty() && expiresAtMillis <= now + 5 * 60 * 1000L;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("access_token", accessToken)
                .put("refresh_token", refreshToken)
                .put("expires_at", expiresAtMillis);
    }

    static XOAuthTokens fromJson(JSONObject object) {
        return new XOAuthTokens(object.optString("access_token", ""),
                object.optString("refresh_token", ""),
                object.optLong("expires_at", 0L));
    }

    XOAuthTokens mergeRefresh(XOAuthTokens updated) {
        if (updated == null) return this;
        return new XOAuthTokens(
                updated.accessToken.isEmpty() ? accessToken : updated.accessToken,
                updated.refreshToken.isEmpty() ? refreshToken : updated.refreshToken,
                updated.expiresAtMillis > 0L ? updated.expiresAtMillis : expiresAtMillis);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
