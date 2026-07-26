package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;

/**
 * Stable dashboard item ids and order merging. Pure Java so {@link ParserSelfTest}
 * can cover reordering without the Android SDK.
 */
public final class DashboardOrder {
    public static final String FIVE_HOUR = "five_hour";
    public static final String WEEKLY = "weekly";
    public static final String USAGE_CREDITS = "usage_credits";
    public static final String RESET_CREDITS = "reset_credits";

    private static final String ADDITIONAL_PREFIX = "additional:";

    private DashboardOrder() {
    }

    public static String additionalPrimary(String limitId) {
        return ADDITIONAL_PREFIX + cleanId(limitId) + ":primary";
    }

    public static String additionalSecondary(String limitId) {
        return ADDITIONAL_PREFIX + cleanId(limitId) + ":secondary";
    }

    public static boolean isAdditional(String id) {
        return id != null && id.startsWith(ADDITIONAL_PREFIX);
    }

    /**
     * Keep preferred order for items that still exist, then append any newly
     * available items in their natural (API / default) order.
     */
    public static List<String> merge(List<String> preferred, List<String> available) {
        List<String> availableClean = dedupe(available);
        LinkedHashSet<String> availableSet = new LinkedHashSet<>(availableClean);
        List<String> merged = new ArrayList<>();
        if (preferred != null) {
            for (String id : preferred) {
                if (id != null && availableSet.remove(id)) {
                    merged.add(id);
                }
            }
        }
        for (String id : availableClean) {
            if (availableSet.contains(id)) {
                merged.add(id);
            }
        }
        return merged;
    }

    public static List<String> defaultAvailable(UsageSnapshot snapshot,
            boolean includeResetCredits) {
        List<String> available = new ArrayList<>();
        if (snapshot != null) {
            if (snapshot.fiveHour != null) {
                available.add(FIVE_HOUR);
            }
            if (snapshot.weekly != null) {
                available.add(WEEKLY);
            }
            if (snapshot.additionalLimits != null) {
                for (UsageLimit limit : snapshot.additionalLimits) {
                    if (limit == null) {
                        continue;
                    }
                    if (limit.primary != null) {
                        available.add(additionalPrimary(limit.id));
                    }
                    if (limit.secondary != null) {
                        available.add(additionalSecondary(limit.id));
                    }
                }
            }
            if (snapshot.usageCredits != null && snapshot.usageCredits.isDashboardVisible()) {
                available.add(USAGE_CREDITS);
            }
        }
        if (includeResetCredits) {
            available.add(RESET_CREDITS);
        }
        return available;
    }

    public static String serialize(List<String> order) {
        JSONArray array = new JSONArray();
        if (order != null) {
            for (String id : dedupe(order)) {
                array.put(id);
            }
        }
        return array.toString();
    }

    public static List<String> parse(String json) {
        List<String> order = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return order;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "").trim();
                if (!id.isEmpty()) {
                    order.add(id);
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return dedupe(order);
    }

    private static List<String> dedupe(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    String clean = value.trim();
                    if (!clean.isEmpty()) {
                        unique.add(clean);
                    }
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private static String cleanId(String limitId) {
        return limitId == null ? "" : limitId.trim();
    }
}
