package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class UsageWindow {
    public final long resetAfterSeconds;
    public final long resetAtEpochSeconds;
    public final int usedPercent;
    public final long windowSeconds;

    public UsageWindow(int i, long j, long j2, long j3) {
        this.usedPercent = clamp(i);
        this.windowSeconds = Math.max(0L, j);
        this.resetAfterSeconds = Math.max(0L, j2);
        this.resetAtEpochSeconds = Math.max(0L, j3);
    }

    public int remainingPercent() {
        return clamp(100 - this.usedPercent);
    }

    /**
     * Full windows keep drifting the reset clock without real consumption.
     * Hide countdown/reset labels until remaining drops to 99% or less.
     */
    public boolean showsResetCountdown() {
        return remainingPercent() <= 99;
    }

    public long resetAtMillis() {
        if (this.resetAtEpochSeconds > 0) {
            return this.resetAtEpochSeconds * 1000;
        }
        return 0L;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("used_percent", this.usedPercent);
        jSONObject.put("limit_window_seconds", this.windowSeconds);
        jSONObject.put("reset_after_seconds", this.resetAfterSeconds);
        jSONObject.put("reset_at", this.resetAtEpochSeconds);
        return jSONObject;
    }

    public static UsageWindow fromJson(JSONObject jSONObject) {
        if (jSONObject == null || jSONObject.isNull("used_percent") || jSONObject.isNull("limit_window_seconds") || !jSONObject.has("used_percent") || !jSONObject.has("limit_window_seconds")) {
            return null;
        }
        double dOptDouble = jSONObject.optDouble("used_percent", Double.NaN);
        long jOptLong = jSONObject.optLong("limit_window_seconds", -1L);
        if (Double.isNaN(dOptDouble) || Double.isInfinite(dOptDouble) || jOptLong <= 0) {
            return null;
        }
        return new UsageWindow((int) Math.round(dOptDouble), jOptLong, jSONObject.optLong("reset_after_seconds", 0L), jSONObject.optLong("reset_at", 0L));
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(100, i));
    }
}
