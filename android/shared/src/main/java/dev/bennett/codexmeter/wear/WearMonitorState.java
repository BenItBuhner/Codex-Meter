package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.NowBarDisplayMode;
import dev.bennett.codexmeter.NowBarPercentMode;
import org.json.JSONException;
import org.json.JSONObject;

public final class WearMonitorState {
    public final boolean active;
    public final String focusMetric;
    public final String postedMode;
    public final boolean preview;
    public final long untilMillis;
    public final long updatedAtMillis;

    public WearMonitorState(boolean active, boolean preview, long untilMillis,
            String focusMetric, String postedMode, long updatedAtMillis) {
        this.active = active;
        this.preview = preview;
        this.untilMillis = Math.max(0L, untilMillis);
        this.focusMetric = NowBarPercentMode.normalizeFocusMetric(focusMetric);
        this.postedMode = NowBarDisplayMode.normalize(postedMode);
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("active", active);
        json.put("preview", preview);
        json.put("until_millis", untilMillis);
        if (focusMetric != null) {
            json.put("focus_metric", focusMetric);
        }
        json.put("posted_mode", postedMode);
        json.put("updated_at_millis", updatedAtMillis);
        return json;
    }

    public static WearMonitorState fromJson(JSONObject json) {
        if (json == null) return null;
        return new WearMonitorState(
                json.optBoolean("active", false),
                json.optBoolean("preview", false),
                json.optLong("until_millis", 0L),
                json.optString("focus_metric", null),
                json.optString("posted_mode", NowBarDisplayMode.AUTO),
                json.optLong("updated_at_millis", 0L));
    }
}
