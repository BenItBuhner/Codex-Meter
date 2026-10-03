package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.UsageSnapshot;
import org.json.JSONObject;
import org.json.JSONException;

/** Explicit allowlist of non-sensitive account data shared with Wear. */
public final class WearAccount {
    public final String accountId;
    public final String displayName;
    public final boolean signedIn;
    public final boolean refreshFailed;
    public final UsageSnapshot snapshot;

    public WearAccount(String id, String name, boolean signedIn, UsageSnapshot snapshot) {
        this(id, name, signedIn, snapshot, false);
    }

    public WearAccount(String id, String name, boolean signedIn, UsageSnapshot snapshot,
            boolean refreshFailed) {
        this.refreshFailed = refreshFailed;
        this.accountId = id == null ? "" : id;
        String label = name == null ? "Account" : name;
        this.displayName = label.substring(0, Math.min(40, label.length()));
        this.signedIn = signedIn;
        this.snapshot = snapshot;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject().put("account_id", accountId)
                .put("display_name", displayName).put("signed_in", signedIn)
                .put("refresh_failed", refreshFailed);
        if (snapshot != null) json.put("usage", snapshot.toJson());
        return json;
    }

    static WearAccount fromJson(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("account_id", "");
        try { java.util.UUID.fromString(id); }
        catch (IllegalArgumentException ignored) { return null; }
        return new WearAccount(id, json.optString("display_name", "Account"),
                json.optBoolean("signed_in", false), UsageSnapshot.fromJson(json.optJSONObject("usage")),
                json.optBoolean("refresh_failed", false));
    }
}
