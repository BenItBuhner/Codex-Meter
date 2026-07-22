package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/** One locally-observed allowance value. No account data or credentials are stored. */
public final class UsageSample {
    public final long observedAtMillis;
    public final int usedPercent;
    public final long resetAtMillis;
    public final long windowSeconds;

    public UsageSample(long observedAtMillis, int usedPercent, long resetAtMillis,
            long windowSeconds) {
        this.observedAtMillis = Math.max(0L, observedAtMillis);
        this.usedPercent = Math.max(0, Math.min(100, usedPercent));
        this.resetAtMillis = Math.max(0L, resetAtMillis);
        this.windowSeconds = Math.max(0L, windowSeconds);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("observed_at", observedAtMillis)
                .put("used_percent", usedPercent)
                .put("reset_at", resetAtMillis)
                .put("window_seconds", windowSeconds);
    }

    public static UsageSample fromJson(JSONObject json) {
        if (json == null) return null;
        long observedAt = json.optLong("observed_at", 0L);
        long resetAt = json.optLong("reset_at", 0L);
        long windowSeconds = json.optLong("window_seconds", 0L);
        double used = json.optDouble("used_percent", Double.NaN);
        if (observedAt <= 0L || resetAt <= 0L || windowSeconds <= 0L
                || Double.isNaN(used) || Double.isInfinite(used)) {
            return null;
        }
        return new UsageSample(observedAt, (int) Math.round(used), resetAt, windowSeconds);
    }
}
