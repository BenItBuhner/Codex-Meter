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

    public UsageWindow(int usedPercent, long windowSeconds, long resetAfterSeconds, long resetAtEpochSeconds) {
        this.usedPercent = clamp(usedPercent);
        this.windowSeconds = Math.max(0L, windowSeconds);
        this.resetAfterSeconds = Math.max(0L, resetAfterSeconds);
        this.resetAtEpochSeconds = Math.max(0L, resetAtEpochSeconds);
    }

    public int remainingPercent() {
        return clamp(100 - this.usedPercent);
    }

    /**
     * Whether OpenAI reported a reset timeline for this window.
     * Unused windows with no {@code reset_at} / {@code reset_after_seconds} stay blank.
     * A 100% remaining window that still includes a timeline is shown as-is.
     */
    public boolean showsResetCountdown() {
        return resetAtEpochSeconds > 0L || resetAfterSeconds > 0L;
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
        JSONObject json = new JSONObject();
        json.put("used_percent", this.usedPercent);
        json.put("limit_window_seconds", this.windowSeconds);
        json.put("reset_after_seconds", this.resetAfterSeconds);
        json.put("reset_at", this.resetAtEpochSeconds);
        return json;
    }

    public static UsageWindow fromJson(JSONObject json) {
        if (json == null || json.isNull("used_percent") || json.isNull("limit_window_seconds") || !json.has("used_percent") || !json.has("limit_window_seconds")) {
            return null;
        }
        double usedPercent = json.optDouble("used_percent", Double.NaN);
        long windowSeconds = json.optLong("limit_window_seconds", -1L);
        if (Double.isNaN(usedPercent) || Double.isInfinite(usedPercent) || windowSeconds <= 0) {
            return null;
        }
        return new UsageWindow((int) Math.round(usedPercent), windowSeconds, json.optLong("reset_after_seconds", 0L), json.optLong("reset_at", 0L));
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(100, i));
    }
}
