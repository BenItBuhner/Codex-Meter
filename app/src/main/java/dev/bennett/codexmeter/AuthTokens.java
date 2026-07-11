package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class AuthTokens {
    public final String accessToken;
    public final String accountId;
    public final String email;
    public final long expiresAtMillis;
    public final String idToken;
    public final String refreshToken;

    public AuthTokens(String str, String str2, String str3, long j, String str4, String str5) {
        this.accessToken = safe(str);
        this.refreshToken = safe(str2);
        this.idToken = safe(str3);
        this.expiresAtMillis = j;
        this.accountId = safe(str4);
        this.email = safe(str5);
    }

    public boolean isUsable() {
        return (this.accessToken.isEmpty() || this.refreshToken.isEmpty()) ? false : true;
    }

    public boolean shouldRefresh(long j) {
        return this.expiresAtMillis <= 300000 + j;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("access_token", this.accessToken);
        jSONObject.put("refresh_token", this.refreshToken);
        jSONObject.put("id_token", this.idToken);
        jSONObject.put("expires_at", this.expiresAtMillis);
        jSONObject.put("account_id", this.accountId);
        jSONObject.put("email", this.email);
        return jSONObject;
    }

    public static AuthTokens fromJson(JSONObject jSONObject) {
        return new AuthTokens(jSONObject.optString("access_token", ""), jSONObject.optString("refresh_token", ""), jSONObject.optString("id_token", ""), jSONObject.optLong("expires_at", 0L), jSONObject.optString("account_id", ""), jSONObject.optString("email", ""));
    }

    public AuthTokens mergeRefresh(AuthTokens authTokens) {
        return new AuthTokens(authTokens.accessToken.isEmpty() ? this.accessToken : authTokens.accessToken, authTokens.refreshToken.isEmpty() ? this.refreshToken : authTokens.refreshToken, authTokens.idToken.isEmpty() ? this.idToken : authTokens.idToken, authTokens.expiresAtMillis > 0 ? authTokens.expiresAtMillis : this.expiresAtMillis, authTokens.accountId.isEmpty() ? this.accountId : authTokens.accountId, authTokens.email.isEmpty() ? this.email : authTokens.email);
    }

    private static String safe(String str) {
        return str == null ? "" : str;
    }
}
