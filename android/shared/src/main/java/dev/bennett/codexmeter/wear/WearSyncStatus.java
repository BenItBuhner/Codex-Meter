package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.Text;
import org.json.JSONException;
import org.json.JSONObject;

/** Phone-owned connection and refresh state shown by the Wear companion. */
public final class WearSyncStatus {
    public final String lastError;
    public final long lastSuccessAtMillis;
    public final String phoneVersion;
    public final boolean refreshInProgress;
    public final boolean signedIn;
    public final long updatedAtMillis;

    public WearSyncStatus(boolean signedIn, boolean refreshInProgress,
            long lastSuccessAtMillis, String lastError, String phoneVersion,
            long updatedAtMillis) {
        this.signedIn = signedIn;
        this.refreshInProgress = refreshInProgress;
        this.lastSuccessAtMillis = Math.max(0L, lastSuccessAtMillis);
        this.lastError = Text.clip(Text.clean(lastError).replace('\n', ' '), 160);
        this.phoneVersion = Text.clip(Text.clean(phoneVersion).replace('\n', ' '), 32);
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("signed_in", signedIn)
                .put("refresh_in_progress", refreshInProgress)
                .put("last_success_at_millis", lastSuccessAtMillis)
                .put("last_error", lastError)
                .put("phone_version", phoneVersion)
                .put("updated_at_millis", updatedAtMillis);
    }

    public static WearSyncStatus fromJson(JSONObject json) {
        if (json == null) return null;
        return new WearSyncStatus(
                json.optBoolean("signed_in", false),
                json.optBoolean("refresh_in_progress", false),
                json.optLong("last_success_at_millis", 0L),
                json.optString("last_error", ""),
                json.optString("phone_version", ""),
                json.optLong("updated_at_millis", 0L));
    }
}
