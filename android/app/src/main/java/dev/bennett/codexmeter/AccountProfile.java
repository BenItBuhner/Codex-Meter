package dev.bennett.codexmeter;

import org.json.JSONObject;
import org.json.JSONException;

/** Phone-only persisted profile. Credentials never enter the shared Wear contract. */
public final class AccountProfile {
    public final String id;
    public String label;
    public final String identity;
    AuthTokens tokens;
    UsageSnapshot usage;

    public String serverAccountId() { return tokens == null ? "" : tokens.accountId; }
    public UsageSnapshot latestUsage() { return usage; }
    public long lastSuccessfulRefreshTime() { return usage == null ? 0L : usage.fetchedAtMillis; }
    public String planInfo() { return usage == null ? "" : usage.planType; }

    AccountProfile(String id, String label, String identity, AuthTokens tokens) {
        this.id = id;
        this.label = label;
        this.identity = identity;
        this.tokens = tokens;
    }

    public boolean isAuthenticated() { return tokens != null && tokens.isUsable(); }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject().put("id", id).put("label", label)
                .put("identity", identity);
        if (tokens != null) json.put("credentials", tokens.toJson());
        return json;
    }

    static AccountProfile fromJson(JSONObject json) throws JSONException {
        String id = json.getString("id");
        java.util.UUID.fromString(id);
        return new AccountProfile(id, json.optString("label", "ChatGPT account"),
                json.optString("identity", ""), json.optJSONObject("credentials") == null
                ? null : AuthTokens.fromJson(json.getJSONObject("credentials")));
    }
}
