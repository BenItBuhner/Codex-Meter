package dev.bennett.codexmeter;

import org.json.JSONObject;

/** Sanitized post metadata and its local reset signal. */
public final class XPost {
    public final String id;
    public final String text;
    public final long createdAtMillis;
    public final String url;
    public final ResetSignal signal;

    XPost(String id, String text, long createdAtMillis, ResetSignal signal) {
        this.id = safe(id, 32);
        this.text = safe(text, 2000);
        this.createdAtMillis = Math.max(0L, createdAtMillis);
        this.url = ResetWatchSource.postUrl(this.id);
        this.signal = signal == null ? new ResetSignal(ResetSignal.NONE, 0) : signal;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("text", text)
                .put("created_at_millis", createdAtMillis)
                .put("category", signal.category)
                .put("likelihood", signal.likelihood);
    }

    static XPost fromJson(JSONObject object) {
        if (object == null) return null;
        String id = object.optString("id", "");
        if (!XTimelineParser.isValidPostId(id)) return null;
        return new XPost(id, object.optString("text", ""),
                object.optLong("created_at_millis", 0L),
                new ResetSignal(object.optString("category", ResetSignal.NONE),
                        object.optInt("likelihood", 0)));
    }

    private static String safe(String value, int max) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
