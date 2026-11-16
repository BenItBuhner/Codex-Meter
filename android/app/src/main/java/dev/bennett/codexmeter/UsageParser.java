package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses the ChatGPT {@code wham/usage} response into a {@link UsageSnapshot}. */
public final class UsageParser {
    private static final long FIVE_HOURS = 18000;
    private static final long WEEK = 604800;
    private static final long MONTH = 2592000;
    // Free-tier accounts report a single ~30-day Codex window; accept 10-45 days so calendar
    // months and drifting billing periods still classify while staying clear of the weekly
    // window's 9-day ceiling.
    private static final long MONTH_MIN = 864000;
    private static final long MONTH_MAX = 3888000;

    private UsageParser() {
    }

    public static UsageSnapshot parse(String body, long fetchedAtMillis) throws JSONException {
        JSONObject root = new JSONObject(body);
        String planType = root.optString("plan_type", "");
        JSONObject rateLimit = nullableObject(root, "rate_limit");
        List<UsageWindow> windows = new ArrayList<>();
        boolean allowed = true;
        boolean limitReached = false;
        if (rateLimit != null) {
            allowed = rateLimit.optBoolean("allowed", true);
            limitReached = rateLimit.optBoolean("limit_reached", false);
            UsageWindow primary = UsageWindow.fromJson(nullableObject(rateLimit, "primary_window"));
            UsageWindow secondary = UsageWindow.fromJson(nullableObject(rateLimit, "secondary_window"));
            if (primary != null) windows.add(primary);
            if (secondary != null) windows.add(secondary);
        }
        List<UsageLimit> additionalLimits = new ArrayList<>();
        JSONArray additional = root.optJSONArray("additional_rate_limits");
        for (int i = 0; additional != null && i < additional.length(); i++) {
            JSONObject entry = additional.optJSONObject(i);
            if (entry == null) continue;
            JSONObject limit = nullableObject(entry, "rate_limit");
            if (limit == null) limit = entry;
            UsageWindow primary = UsageWindow.fromJson(nullableObject(limit, "primary_window"));
            UsageWindow secondary = UsageWindow.fromJson(nullableObject(limit, "secondary_window"));
            if (primary == null && secondary == null) continue;
            String name = entry.optString("limit_name", "");
            String feature = entry.optString("metered_feature", "");
            String id = firstNonEmpty(entry.optString("limit_id", ""), name, feature, "additional");
            additionalLimits.add(new UsageLimit(id + "-" + i, name, feature,
                    limit.optBoolean("allowed", true), limit.optBoolean("limit_reached", false),
                    primary, secondary));
        }
        UsageWindow fiveHour = nearestExcluding(windows, FIVE_HOURS, 10800L, 28800L);
        UsageWindow weekly = nearestExcluding(windows, WEEK, 432000L, 777600L, fiveHour);
        UsageWindow monthly = nearestExcluding(windows, MONTH, MONTH_MIN, MONTH_MAX, fiveHour, weekly);
        JSONObject resetCredits = nullableObject(root, "rate_limit_reset_credits");
        return new UsageSnapshot(
                planType,
                allowed,
                limitReached,
                fiveHour,
                weekly,
                monthly,
                additionalLimits,
                UsageCredits.fromJson(nullableObject(root, "credits")),
                SpendControl.fromJson(nullableObject(root, "spend_control")),
                resetCredits == null ? -1 : resetCredits.optInt("available_count", -1),
                fetchedAtMillis);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static JSONObject nullableObject(JSONObject parent, String key) {
        if (parent == null || parent.isNull(key)) {
            return null;
        }
        return parent.optJSONObject(key);
    }

    /** The window closest to {@code target} seconds within the range, skipping ones already used. */
    private static UsageWindow nearestExcluding(List<UsageWindow> list, long target,
            long minimumSeconds, long maximumSeconds, UsageWindow... excluded) {
        UsageWindow best = null;
        long bestDistance = Long.MAX_VALUE;
        for (UsageWindow candidate : list) {
            if (isExcluded(candidate, excluded)
                    || candidate.windowSeconds < minimumSeconds
                    || candidate.windowSeconds > maximumSeconds) {
                continue;
            }
            long distance = Math.abs(candidate.windowSeconds - target);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isExcluded(UsageWindow candidate, UsageWindow[] excluded) {
        for (UsageWindow window : excluded) {
            if (candidate == window) {
                return true;
            }
        }
        return false;
    }

}
