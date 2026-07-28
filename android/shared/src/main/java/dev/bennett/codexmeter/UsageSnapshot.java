package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class UsageSnapshot {
    public final boolean allowed;
    public final List<UsageLimit> additionalLimits;
    public final long fetchedAtMillis;
    public final UsageWindow fiveHour;
    public final boolean limitReached;
    public final String planType;
    public final int resetCreditsAvailable;
    public final UsageCredits usageCredits;
    public final UsageWindow weekly;

    public UsageSnapshot(String str, boolean z, boolean z2, UsageWindow usageWindow, UsageWindow usageWindow2, long j) {
        this(str, z, z2, usageWindow, usageWindow2, -1, j);
    }

    public UsageSnapshot(String str, boolean z, boolean z2, UsageWindow usageWindow, UsageWindow usageWindow2, int i, long j) {
        this(str, z, z2, usageWindow, usageWindow2, Collections.emptyList(), null, i, j);
    }

    public UsageSnapshot(String str, boolean z, boolean z2, UsageWindow usageWindow,
            UsageWindow usageWindow2, List<UsageLimit> additionalLimits,
            UsageCredits usageCredits, int i, long j) {
        this.planType = str == null ? "" : str;
        this.allowed = z;
        this.limitReached = z2;
        this.fiveHour = usageWindow;
        this.weekly = usageWindow2;
        this.additionalLimits = additionalLimits == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(additionalLimits));
        this.usageCredits = usageCredits;
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
        if (!this.additionalLimits.isEmpty()) {
            JSONArray limits = new JSONArray();
            for (UsageLimit limit : this.additionalLimits) {
                if (limit != null) {
                    limits.put(limit.toJson());
                }
            }
            jSONObject.put("additional_limits", limits);
        }
        if (this.usageCredits != null) {
            jSONObject.put("usage_credits", this.usageCredits.toJson());
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
        ArrayList<UsageLimit> additionalLimits = new ArrayList<>();
        JSONArray limits = jSONObject.optJSONArray("additional_limits");
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                UsageLimit limit = UsageLimit.fromJson(limits.optJSONObject(i));
                if (limit != null) {
                    additionalLimits.add(limit);
                }
            }
        }
        return new UsageSnapshot(
                jSONObject.optString("plan_type", ""),
                jSONObject.optBoolean("allowed", true),
                jSONObject.optBoolean("limit_reached", false),
                UsageWindow.fromJson(jSONObject.optJSONObject("five_hour")),
                UsageWindow.fromJson(jSONObject.optJSONObject("weekly")),
                additionalLimits,
                UsageCredits.fromJson(jSONObject.optJSONObject("usage_credits")),
                jSONObject.has("reset_credits_available")
                        ? jSONObject.optInt("reset_credits_available", -1) : -1,
                jSONObject.optLong("fetched_at", 0L));
    }

    public long nextResetMillis(long j) {
        long fiveHourReset = this.fiveHour == null ? 0L
                : this.fiveHour.effectiveResetAtMillis(this.fetchedAtMillis);
        long weeklyReset = this.weekly == null ? 0L
                : this.weekly.effectiveResetAtMillis(this.fetchedAtMillis);
        long jMin = fiveHourReset <= j ? Long.MAX_VALUE : fiveHourReset;
        if (weeklyReset > j) {
            jMin = Math.min(jMin, weeklyReset);
        }
        for (UsageLimit limit : additionalLimits) {
            jMin = earlierFutureReset(jMin, limit.primary, j);
            jMin = earlierFutureReset(jMin, limit.secondary, j);
        }
        if (jMin == Long.MAX_VALUE) {
            return 0L;
        }
        return jMin;
    }

    public boolean hasDisplayableData() {
        return fiveHour != null || weekly != null || !additionalLimits.isEmpty()
                || (usageCredits != null && usageCredits.shouldDisplay())
                || resetCreditsAvailable > 0;
    }

    private long earlierFutureReset(long current, UsageWindow window, long now) {
        if (window == null) {
            return current;
        }
        long reset = window.effectiveResetAtMillis(fetchedAtMillis);
        return reset > now ? Math.min(current, reset) : current;
    }

    static UsageWindow currentWindow(UsageWindow window, long now) {
        return currentWindow(window, 0L, now);
    }

    static UsageWindow currentWindow(UsageWindow window, long observedAtMillis, long now) {
        if (window == null) return null;
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        return resetAt > 0L && resetAt <= now ? null : window;
    }
}
