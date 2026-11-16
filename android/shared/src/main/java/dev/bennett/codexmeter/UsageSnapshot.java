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
    /** Monthly Codex window (~30 days); reported instead of 5-hour/weekly on the Free tier. */
    public final UsageWindow monthly;
    public final String planType;
    public final int resetCreditsAvailable;
    /** Workspace spend-control monthly credit limit; null for accounts without one. */
    public final SpendControl spendControl;
    public final UsageCredits usageCredits;
    public final UsageWindow weekly;
    /**
     * Included usage per plan cycle, in dollars at API prices, when the provider reports it
     * (Cursor's {@code planUsage.limit}); NaN otherwise. Value estimates use it in place of a
     * researched plan figure, since it is the provider's own number.
     */
    public final double includedValueUsd;

    public UsageSnapshot(String planType, boolean allowed, boolean limitReached, UsageWindow fiveHour, UsageWindow weekly, long fetchedAtMillis) {
        this(planType, allowed, limitReached, fiveHour, weekly, -1, fetchedAtMillis);
    }

    public UsageSnapshot(String planType, boolean allowed, boolean limitReached, UsageWindow fiveHour, UsageWindow weekly, int resetCredits, long fetchedAtMillis) {
        this(planType, allowed, limitReached, fiveHour, weekly, null, Collections.emptyList(), null, resetCredits, fetchedAtMillis);
    }

    public UsageSnapshot(String planType, boolean allowed, boolean limitReached, UsageWindow fiveHour,
            UsageWindow weekly, List<UsageLimit> additionalLimits,
            UsageCredits usageCredits, int resetCredits, long fetchedAtMillis) {
        this(planType, allowed, limitReached, fiveHour, weekly, null, additionalLimits, usageCredits, resetCredits, fetchedAtMillis);
    }

    public UsageSnapshot(String planType, boolean allowed, boolean limitReached, UsageWindow fiveHour,
            UsageWindow weekly, UsageWindow monthlyWindow,
            List<UsageLimit> additionalLimits,
            UsageCredits usageCredits, int resetCredits, long fetchedAtMillis) {
        this(planType, allowed, limitReached, fiveHour, weekly, monthlyWindow, additionalLimits,
                usageCredits, null, resetCredits, fetchedAtMillis);
    }

    public UsageSnapshot(String planType, boolean allowed, boolean limitReached, UsageWindow fiveHour,
            UsageWindow weekly, UsageWindow monthlyWindow,
            List<UsageLimit> additionalLimits,
            UsageCredits usageCredits, SpendControl spendControl, int resetCredits, long fetchedAtMillis) {
        this(planType, allowed, limitReached, fiveHour, weekly, monthlyWindow, additionalLimits,
                usageCredits, spendControl, resetCredits, fetchedAtMillis, Double.NaN);
    }

    private UsageSnapshot(String planType, boolean allowed, boolean limitReached, UsageWindow fiveHour,
            UsageWindow weekly, UsageWindow monthlyWindow,
            List<UsageLimit> additionalLimits,
            UsageCredits usageCredits, SpendControl spendControl, int resetCredits, long fetchedAtMillis,
            double includedValueUsd) {
        this.includedValueUsd = includedValueUsd > 0d && !Double.isInfinite(includedValueUsd)
                ? includedValueUsd : Double.NaN;
        this.planType = planType == null ? "" : planType;
        this.allowed = allowed;
        this.limitReached = limitReached;
        this.fiveHour = fiveHour;
        this.weekly = weekly;
        this.monthly = monthlyWindow;
        this.additionalLimits = additionalLimits == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(additionalLimits));
        this.usageCredits = usageCredits;
        this.spendControl = spendControl;
        this.resetCreditsAvailable = resetCredits < 0 ? -1 : resetCredits;
        this.fetchedAtMillis = fetchedAtMillis;
    }

    /** The same snapshot with a different plan badge. */
    public UsageSnapshot withPlanType(String plan) {
        return new UsageSnapshot(plan, allowed, limitReached, fiveHour, weekly, monthly,
                additionalLimits, usageCredits, spendControl, resetCreditsAvailable,
                fetchedAtMillis, includedValueUsd);
    }

    /** The same snapshot with the plan's included usage value per cycle, in dollars. */
    public UsageSnapshot withIncludedValueUsd(double dollars) {
        return new UsageSnapshot(planType, allowed, limitReached, fiveHour, weekly, monthly,
                additionalLimits, usageCredits, spendControl, resetCreditsAvailable,
                fetchedAtMillis, dollars);
    }

    /** The same snapshot keeping only the first {@code count} named limits. */
    public UsageSnapshot withFirstLimits(int count) {
        int keep = Math.max(0, Math.min(count, additionalLimits.size()));
        if (keep == additionalLimits.size()) return this;
        return new UsageSnapshot(planType, allowed, limitReached, fiveHour, weekly, monthly,
                additionalLimits.subList(0, keep), usageCredits, spendControl, resetCreditsAvailable,
                fetchedAtMillis, includedValueUsd);
    }

    /** Whether the provider reported its own included usage value. */
    public boolean hasIncludedValue() {
        return !Double.isNaN(includedValueUsd);
    }

    /**
     * The longer-cadence Codex window: weekly when present, otherwise the monthly window that
     * free-tier accounts report. Surfaces that used to hardcode "weekly" adapt through this so a
     * subscription change (for example Pro 20x expiring to Free) swaps windows automatically.
     */
    public UsageWindow longWindow() {
        return this.weekly != null ? this.weekly : this.monthly;
    }

    /** Whether {@link #longWindow()} is the monthly window rather than the weekly one. */
    public boolean longWindowIsMonthly() {
        return this.weekly == null && this.monthly != null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("plan_type", this.planType);
        json.put("allowed", this.allowed);
        json.put("limit_reached", this.limitReached);
        if (this.fiveHour != null) {
            json.put("five_hour", this.fiveHour.toJson());
        }
        if (this.weekly != null) {
            json.put("weekly", this.weekly.toJson());
        }
        if (this.monthly != null) {
            json.put("monthly", this.monthly.toJson());
        }
        if (!this.additionalLimits.isEmpty()) {
            JSONArray limits = new JSONArray();
            for (UsageLimit limit : this.additionalLimits) {
                if (limit != null) {
                    limits.put(limit.toJson());
                }
            }
            json.put("additional_limits", limits);
        }
        if (this.usageCredits != null) {
            json.put("usage_credits", this.usageCredits.toJson());
        }
        if (this.spendControl != null) {
            json.put("spend_control", this.spendControl.toJson());
        }
        if (this.resetCreditsAvailable >= 0) {
            json.put("reset_credits_available", this.resetCreditsAvailable);
        }
        if (hasIncludedValue()) {
            json.put("included_value_usd", this.includedValueUsd);
        }
        json.put("fetched_at", this.fetchedAtMillis);
        return json;
    }

    public static UsageSnapshot fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        ArrayList<UsageLimit> additionalLimits = new ArrayList<>();
        JSONArray limits = json.optJSONArray("additional_limits");
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                UsageLimit limit = UsageLimit.fromJson(limits.optJSONObject(i));
                if (limit != null) {
                    additionalLimits.add(limit);
                }
            }
        }
        return new UsageSnapshot(
                json.optString("plan_type", ""),
                json.optBoolean("allowed", true),
                json.optBoolean("limit_reached", false),
                UsageWindow.fromJson(json.optJSONObject("five_hour")),
                UsageWindow.fromJson(json.optJSONObject("weekly")),
                UsageWindow.fromJson(json.optJSONObject("monthly")),
                additionalLimits,
                UsageCredits.fromJson(json.optJSONObject("usage_credits")),
                SpendControl.fromJson(json.optJSONObject("spend_control")),
                json.has("reset_credits_available")
                        ? json.optInt("reset_credits_available", -1) : -1,
                json.optLong("fetched_at", 0L),
                json.optDouble("included_value_usd", Double.NaN));
    }

    public long nextResetMillis(long now) {
        long fiveHourReset = this.fiveHour == null ? 0L
                : this.fiveHour.effectiveResetAtMillis(this.fetchedAtMillis);
        long weeklyReset = this.weekly == null ? 0L
                : this.weekly.effectiveResetAtMillis(this.fetchedAtMillis);
        long monthlyReset = this.monthly == null ? 0L
                : this.monthly.effectiveResetAtMillis(this.fetchedAtMillis);
        long next = fiveHourReset <= now ? Long.MAX_VALUE : fiveHourReset;
        if (weeklyReset > now) {
            next = Math.min(next, weeklyReset);
        }
        if (monthlyReset > now) {
            next = Math.min(next, monthlyReset);
        }
        for (UsageLimit limit : additionalLimits) {
            next = earlierFutureReset(next, limit.primary, now);
            next = earlierFutureReset(next, limit.secondary, now);
        }
        if (next == Long.MAX_VALUE) {
            return 0L;
        }
        return next;
    }

    public boolean hasDisplayableData() {
        return fiveHour != null || weekly != null || monthly != null || !additionalLimits.isEmpty()
                || (usageCredits != null && usageCredits.shouldDisplay())
                || spendControl != null
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
