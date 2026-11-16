package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/** An OAuth sign-in's tokens and the account they belong to. Never null fields. */
public final class AuthTokens {
    /** Refresh this long before the access token expires. */
    private static final long REFRESH_MARGIN_MILLIS = 5 * 60_000L;

    public final String accessToken;
    public final String accountId;
    public final String email;
    public final long expiresAtMillis;
    public final String idToken;
    public final String refreshToken;

    public AuthTokens(String accessToken, String refreshToken, String idToken, long expiresAtMillis,
            String accountId, String email) {
        this.accessToken = safe(accessToken);
        this.refreshToken = safe(refreshToken);
        this.idToken = safe(idToken);
        this.expiresAtMillis = expiresAtMillis;
        this.accountId = safe(accountId);
        this.email = safe(email);
    }

    public boolean isUsable() {
        return !accessToken.isEmpty() && !refreshToken.isEmpty();
    }

    public boolean shouldRefresh(long nowMillis) {
        return expiresAtMillis <= nowMillis + REFRESH_MARGIN_MILLIS;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("access_token", accessToken)
                .put("refresh_token", refreshToken)
                .put("id_token", idToken)
                .put("expires_at", expiresAtMillis)
                .put("account_id", accountId)
                .put("email", email);
    }

    public static AuthTokens fromJson(JSONObject json) {
        return new AuthTokens(json.optString("access_token", ""), json.optString("refresh_token", ""),
                json.optString("id_token", ""), json.optLong("expires_at", 0L),
                json.optString("account_id", ""), json.optString("email", ""));
    }

    /** These tokens updated by a refresh response, which may leave fields out. */
    public AuthTokens mergeRefresh(AuthTokens fresh) {
        return new AuthTokens(
                fresh.accessToken.isEmpty() ? accessToken : fresh.accessToken,
                fresh.refreshToken.isEmpty() ? refreshToken : fresh.refreshToken,
                fresh.idToken.isEmpty() ? idToken : fresh.idToken,
                fresh.expiresAtMillis > 0 ? fresh.expiresAtMillis : expiresAtMillis,
                fresh.accountId.isEmpty() ? accountId : fresh.accountId,
                fresh.email.isEmpty() ? email : fresh.email);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
