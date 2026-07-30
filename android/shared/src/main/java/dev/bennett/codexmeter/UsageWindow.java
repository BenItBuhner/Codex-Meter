package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;
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

    public long effectiveResetAtMillis(long observedAtMillis) {
        long explicit = resetAtMillis();
        if (explicit > 0L) return explicit;
        if (resetAfterSeconds <= 0L || observedAtMillis <= 0L
                || resetAfterSeconds > (Long.MAX_VALUE - observedAtMillis) / 1000L) {
            return 0L;
        }
        return observedAtMillis + resetAfterSeconds * 1000L;
    }

    /**
     * OpenAI reset timestamps drift slightly across refreshes. Treat nearby reset times for the
     * same window length as one window so low-usage alerts stay one-shot until the real reset.
     */
    public static long resetWindowToleranceMillis(long windowSeconds) {
        if (windowSeconds <= 0L) return TimeUnit.MINUTES.toMillis(1);
        return Math.min(TimeUnit.MINUTES.toMillis(15),
                Math.max(TimeUnit.MINUTES.toMillis(1), windowSeconds * 1000L / 20L));
    }

    public static boolean sameResetWindow(long leftResetAtMillis, long leftWindowSeconds,
            long rightResetAtMillis, long rightWindowSeconds) {
        if (leftResetAtMillis <= 0L || rightResetAtMillis <= 0L
                || leftWindowSeconds != rightWindowSeconds) {
            return false;
        }
        return Math.abs(leftResetAtMillis - rightResetAtMillis)
                <= resetWindowToleranceMillis(leftWindowSeconds);
    }

    /**
     * Whether a low-usage alert should fire for {@code currentResetAtMillis}. Returns false when
     * {@code lastAnnouncedResetAtMillis} already covers the same usage window.
     */
    public static boolean shouldAnnounceLowUsage(long lastAnnouncedResetAtMillis,
            long currentResetAtMillis, long windowSeconds) {
        if (currentResetAtMillis <= 0L) return false;
        if (lastAnnouncedResetAtMillis <= 0L) return true;
        return !sameResetWindow(lastAnnouncedResetAtMillis, windowSeconds,
                currentResetAtMillis, windowSeconds);
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
