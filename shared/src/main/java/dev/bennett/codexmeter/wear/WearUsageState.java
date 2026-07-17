package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.UsageSnapshot;
import org.json.JSONException;
import org.json.JSONObject;

public final class WearUsageState {
    public final String sourceNode;
    public final UsageSnapshot snapshot;
    public final long updatedAtMillis;

    public WearUsageState(UsageSnapshot snapshot, long updatedAtMillis, String sourceNode) {
        this.snapshot = snapshot;
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
        this.sourceNode = WearSettingsState.SOURCE_WEAR.equals(sourceNode)
                ? WearSettingsState.SOURCE_WEAR : WearSettingsState.SOURCE_PHONE;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (snapshot != null) {
            json.put("usage", snapshot.toJson());
        }
        json.put("updated_at_millis", updatedAtMillis);
        json.put("source_node", sourceNode);
        return json;
    }

    public static WearUsageState fromJson(JSONObject json) {
        if (json == null) return null;
        return new WearUsageState(
                UsageSnapshot.fromJson(json.optJSONObject("usage")),
                json.optLong("updated_at_millis", 0L),
                json.optString("source_node", WearSettingsState.SOURCE_PHONE));
    }
}
