package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Stable keys and ordering rules for the movable dashboard sections: the built-in 5-hour and
 * weekly windows, every automatically detected additional limit (for example
 * GPT-5.3-Codex-Spark), and the usage-credit balance. The saved order is a comma-separated key
 * list; unknown saved keys are dropped and newly detected sections slot into their default
 * position, so accounts gaining or losing model-specific limits never lose their arrangement.
 */
public final class DashboardSections {
    public static final String FIVE_HOUR = "five_hour";
    public static final String WEEKLY = "weekly";
    public static final String USAGE_CREDITS = "usage_credits";
    private static final String LIMIT_PREFIX = "limit:";

    private DashboardSections() {
    }

    /** Stable key for an additional limit, preferring the API id over display names. */
    public static String limitKey(UsageLimit limit) {
        String identity = limit.id;
        if (identity.isEmpty()) {
            identity = limit.name;
        }
        if (identity.isEmpty()) {
            identity = limit.meteredFeature;
        }
        if (identity.isEmpty()) {
            identity = "additional";
        }
        return LIMIT_PREFIX + identity.toLowerCase(Locale.ROOT).replace(',', '_');
    }

    public static boolean isLimitKey(String key) {
        return key != null && key.startsWith(LIMIT_PREFIX);
    }

    /** Default order: 5-hour, weekly, detected additional limits, usage credits. */
    public static List<String> defaultOrder(List<UsageLimit> additionalLimits) {
        List<String> order = new ArrayList<>();
        order.add(FIVE_HOUR);
        order.add(WEEKLY);
        if (additionalLimits != null) {
            for (UsageLimit limit : additionalLimits) {
                if (limit != null && !order.contains(limitKey(limit))) {
                    order.add(limitKey(limit));
                }
            }
        }
        order.add(USAGE_CREDITS);
        return order;
    }

    /**
     * Applies a saved order to the currently available section keys. Saved keys that are no
     * longer available are ignored; available keys missing from the saved order are inserted
     * right after the nearest preceding available key that survived, so new sections appear in
     * their natural position instead of being dumped at the end.
     */
    public static List<String> resolveOrder(String savedCsv, List<String> available) {
        List<String> availableKeys = new ArrayList<>(new LinkedHashSet<>(available));
        List<String> result = new ArrayList<>();
        for (String key : parseCsv(savedCsv)) {
            if (availableKeys.contains(key) && !result.contains(key)) {
                result.add(key);
            }
        }
        for (int i = 0; i < availableKeys.size(); i++) {
            String key = availableKeys.get(i);
            if (result.contains(key)) {
                continue;
            }
            int insertAt = 0;
            for (int previous = 0; previous < i; previous++) {
                int position = result.indexOf(availableKeys.get(previous));
                if (position >= 0) {
                    insertAt = Math.max(insertAt, position + 1);
                }
            }
            result.add(insertAt, key);
        }
        return result;
    }

    public static String serialize(List<String> order) {
        StringBuilder csv = new StringBuilder();
        for (String key : order) {
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(key.trim());
        }
        return csv.toString();
    }

    private static List<String> parseCsv(String csv) {
        List<String> keys = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return keys;
        }
        for (String part : csv.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }
}
