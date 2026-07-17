package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class UsageSnapshot {
    public final boolean allowed;
    public final long fetchedAtMillis;
    public final UsageWindow fiveHour;
    public final boolean limitReached;
    public final String planType;
    public final int resetCreditsAvailable;
    public final UsageWindow weekly;

    public UsageSnapshot(String str, boolean z, boolean z2, UsageWindow usageWindow, UsageWindow usageWindow2, long j) {
        this(str, z, z2, usageWindow, usageWindow2, -1, j);
    }

    public UsageSnapshot(String str, boolean z, boolean z2, UsageWindow usageWindow, UsageWindow usageWindow2, int i, long j) {
        this.planType = str == null ? "" : str;
        this.allowed = z;
        this.limitReached = z2;
        this.fiveHour = usageWindow;
        this.weekly = usageWindow2;
        this.resetCreditsAvailable = i < 0 ? -1 : i;
        this.fetchedAtMillis = j;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("plan_type", this.planType);
        jSONObject.put("allowed", this.allowed);
        jSONObject.put("limit_reached", this.limitReached);
        if (this.fiveHour != null) {
            jSONObject.put("five_hour", this.fiveHour.toJson());
        }
        if (this.weekly != null) {
            jSONObject.put("weekly", this.weekly.toJson());
        }
        if (this.resetCreditsAvailable >= 0) {
            jSONObject.put("reset_credits_available", this.resetCreditsAvailable);
        }
        jSONObject.put("fetched_at", this.fetchedAtMillis);
        return jSONObject;
    }

    public static UsageSnapshot fromJson(JSONObject jSONObject) {
        if (jSONObject == null) {
            return null;
        }
        return new UsageSnapshot(jSONObject.optString("plan_type", ""), jSONObject.optBoolean("allowed", true), jSONObject.optBoolean("limit_reached", false), UsageWindow.fromJson(jSONObject.optJSONObject("five_hour")), UsageWindow.fromJson(jSONObject.optJSONObject("weekly")), jSONObject.has("reset_credits_available") ? jSONObject.optInt("reset_credits_available", -1) : -1, jSONObject.optLong("fetched_at", 0L));
    }

    public long nextResetMillis(long j) {
        long jMin = (this.fiveHour == null || this.fiveHour.resetAtMillis() <= j) ? Long.MAX_VALUE : Math.min(Long.MAX_VALUE, this.fiveHour.resetAtMillis());
        if (this.weekly != null && this.weekly.resetAtMillis() > j) {
            jMin = Math.min(jMin, this.weekly.resetAtMillis());
        }
        if (jMin == Long.MAX_VALUE) {
            return 0L;
        }
        return jMin;
    }

    static UsageWindow currentWindow(UsageWindow window, long now) {
        if (window == null) return null;
        long resetAt = window.resetAtMillis();
        return resetAt > 0L && resetAt <= now ? null : window;
    }
}
