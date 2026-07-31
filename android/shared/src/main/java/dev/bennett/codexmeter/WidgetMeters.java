package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Stable meter keys and resolution rules for home and lock widgets. Preference selects which
 * meters fill the slots; host size and visual style select how many slots fit.
 */
public final class WidgetMeters {
    public static final String FIVE_HOUR = "five_hour";
    public static final String WEEKLY = "weekly";
    public static final String NEXT_RESET = "next_reset";
    public static final String RESET_CREDITS = "reset_credits";

    /** Internal render styles used by the home widget renderer. */
    public static final String VISUAL_RINGS = "rings";
    public static final String VISUAL_DIALS = "dials";
    public static final String VISUAL_FOUR_DIALS = "four_dials";
    public static final String VISUAL_BATTERY_LIST = "battery_list";

    public static final String PREF_AUTO = "auto";
    public static final String PREF_DIALS = "dials";
    public static final String PREF_BARS = "bars";

    private static final String LIMIT_PREFIX = "limit:";
    private static final String PRIMARY_SUFFIX = ":primary";
    private static final String SECONDARY_SUFFIX = ":secondary";

    private WidgetMeters() {
    }

    /** Default visible meters for the classic "both windows" home widget. */
    public static List<String> defaultVisible() {
        List<String> keys = new ArrayList<>();
        keys.add(FIVE_HOUR);
        keys.add(WEEKLY);
        keys.add(NEXT_RESET);
        keys.add(RESET_CREDITS);
        return keys;
    }

    /** Migrates the legacy metric_mode spinner into an explicit visible-meters list. */
    public static List<String> fromMetricMode(String metricMode) {
        if ("five_hour".equals(metricMode)) {
            List<String> keys = new ArrayList<>();
            keys.add(FIVE_HOUR);
            return keys;
        }
        if ("weekly".equals(metricMode)) {
            List<String> keys = new ArrayList<>();
            keys.add(WEEKLY);
            return keys;
        }
        return defaultVisible();
    }

    /**
     * Returns the effective visible-meters CSV. An empty/missing saved value falls back to the
     * legacy metric_mode mapping so upgraded widgets keep their previous content.
     */
    public static String effectiveVisibleCsv(String savedCsv, String metricMode) {
        if (savedCsv != null && !savedCsv.trim().isEmpty()) {
            return serialize(parse(savedCsv));
        }
        return serialize(fromMetricMode(metricMode));
    }

    public static List<String> parse(String csv) {
        List<String> keys = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return keys;
        }
        for (String part : csv.split(",")) {
            String key = part.trim();
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    public static String serialize(List<String> keys) {
        StringBuilder csv = new StringBuilder();
        if (keys == null) {
            return "";
        }
        for (String key : keys) {
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

    public static boolean isLimitKey(String key) {
        return key != null && key.startsWith(LIMIT_PREFIX)
                && (key.endsWith(PRIMARY_SUFFIX) || key.endsWith(SECONDARY_SUFFIX));
    }

    public static String limitPrimaryKey(UsageLimit limit) {
        return LIMIT_PREFIX + limitIdentity(limit) + PRIMARY_SUFFIX;
    }

    public static String limitSecondaryKey(UsageLimit limit) {
        return LIMIT_PREFIX + limitIdentity(limit) + SECONDARY_SUFFIX;
    }

    public static String limitIdentity(UsageLimit limit) {
        if (limit == null) {
            return "additional";
        }
        String identity = limit.id;
        if (identity == null || identity.isEmpty()) {
            identity = limit.name;
        }
        if (identity == null || identity.isEmpty()) {
            identity = limit.meteredFeature;
        }
        if (identity == null || identity.isEmpty()) {
            identity = "additional";
        }
        return identity.toLowerCase(Locale.ROOT).replace(',', '_');
    }

    /**
     * Catalog of meters that can be offered in widget config. Model-specific additional limits
     * (for example GPT-5.3-Codex-Spark) stay on the in-app dashboard only — widgets stick to the
     * Codex 5-hour/weekly windows plus next-reset and reset-credit helpers.
     */
    public static List<String> availableKeys(UsageSnapshot snapshot) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(FIVE_HOUR);
        keys.add(WEEKLY);
        keys.add(NEXT_RESET);
        keys.add(RESET_CREDITS);
        return new ArrayList<>(keys);
    }

    /**
     * Applies a saved ordered visible list to currently available keys. Unknown/stale keys are
     * dropped; order of surviving keys is preserved.
     */
    public static List<String> resolveVisible(String savedCsv, List<String> available) {
        List<String> availableKeys = available == null
                ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(available));
        List<String> result = new ArrayList<>();
        for (String key : parse(savedCsv)) {
            if (availableKeys.contains(key) && !result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * Like {@link #resolveVisible} but never returns an empty list: when every saved key has
     * gone stale (for example a widget pinned only to a model limit the account no longer
     * reports), falls back to the legacy metric-mode selection so the widget keeps rendering.
     */
    public static List<String> resolveVisibleOrDefault(String savedCsv, List<String> available,
            String metricMode) {
        List<String> resolved = resolveVisible(savedCsv, available);
        if (!resolved.isEmpty()) {
            return resolved;
        }
        return resolveVisible(serialize(fromMetricMode(metricMode)), available);
    }

    /** Truncates an ordered visible list to the host's slot capacity. */
    public static List<String> cap(List<String> visible, int capacity) {
        List<String> source = visible == null ? new ArrayList<>() : visible;
        int limit = Math.max(0, capacity);
        if (source.size() <= limit) {
            return new ArrayList<>(source);
        }
        return new ArrayList<>(source.subList(0, limit));
    }

    public static boolean contains(List<String> keys, String key) {
        return keys != null && key != null && keys.contains(key);
    }

    /** Number of usage-window meters (Codex + additional limits), excluding reset helpers. */
    public static int usageMeterCount(List<String> keys) {
        if (keys == null) {
            return 0;
        }
        int count = 0;
        for (String key : keys) {
            if (FIVE_HOUR.equals(key) || WEEKLY.equals(key) || isLimitKey(key)) {
                count++;
            }
        }
        return count;
    }

    public static boolean singleUsageMetric(List<String> keys) {
        return usageMeterCount(keys) <= 1;
    }

    /**
     * Normalizes a stored layout preference to auto / dials / bars. Legacy forced {@code rings}
     * and {@code minimal} values map to adaptive auto.
     */
    public static String layoutPreference(String layout) {
        if (PREF_DIALS.equals(layout)) {
            return PREF_DIALS;
        }
        if (PREF_BARS.equals(layout) || "detailed".equals(layout)) {
            return PREF_BARS;
        }
        return PREF_AUTO;
    }

    /**
     * Resolves the concrete home-widget visual style from preference + host size.
     * {@code auto} preserves the pre-customization size map exactly.
     */
    public static String resolveHomeVisualStyle(String layoutPreference, boolean singleUsageMetric,
            int rows, int columns, int heightDp, int widthDp) {
        String preference = layoutPreference(layoutPreference);
        if (PREF_BARS.equals(preference)) {
            return VISUAL_BATTERY_LIST;
        }
        String autoStyle = autoStyleForSize(singleUsageMetric, rows, columns, heightDp, widthDp);
        if (PREF_DIALS.equals(preference) && VISUAL_BATTERY_LIST.equals(autoStyle)) {
            return singleUsageMetric ? VISUAL_DIALS : VISUAL_FOUR_DIALS;
        }
        return autoStyle;
    }

    /** Pixel-identical to the former {@code WidgetRenderer.styleForSize} auto path. */
    public static String autoStyleForSize(boolean singleUsageMetric, int rows, int columns,
            int heightDp, int widthDp) {
        if (singleUsageMetric) {
            if (rows >= 2 || heightDp >= 130) {
                return VISUAL_DIALS;
            }
            return VISUAL_RINGS;
        }
        if (rows > 0) {
            if (rows >= 2) {
                return VISUAL_BATTERY_LIST;
            }
            return columns >= 3 ? VISUAL_FOUR_DIALS : VISUAL_RINGS;
        }
        if (heightDp >= 130) {
            return VISUAL_BATTERY_LIST;
        }
        return widthDp >= 240 ? VISUAL_FOUR_DIALS : VISUAL_RINGS;
    }

    /**
     * Slot capacity for a resolved visual style. Forced bars on short hosts only fit two rows.
     */
    public static int slotCapacity(String visualStyle, int heightDp) {
        if (VISUAL_FOUR_DIALS.equals(visualStyle)) {
            return 4;
        }
        if (VISUAL_BATTERY_LIST.equals(visualStyle)) {
            return heightDp >= 130 ? 4 : 2;
        }
        if (VISUAL_RINGS.equals(visualStyle) || VISUAL_DIALS.equals(visualStyle)) {
            return 2;
        }
        return 2;
    }

    /** Lock widgets expose at most two meter groups regardless of shape. */
    public static int lockSlotCapacity() {
        return 2;
    }

    /** Short label for dial/bar faces; config can use the fuller display name. */
    public static String shortLabel(String key, UsageSnapshot snapshot) {
        if (FIVE_HOUR.equals(key)) {
            return "5h";
        }
        if (WEEKLY.equals(key)) {
            return "Wk";
        }
        if (NEXT_RESET.equals(key)) {
            return "Reset";
        }
        if (RESET_CREDITS.equals(key)) {
            return "Credits";
        }
        UsageLimit limit = findLimit(key, snapshot);
        if (limit != null) {
            String base = shortLimitName(limit.displayName());
            if (key.endsWith(PRIMARY_SUFFIX)) {
                return base + " 5h";
            }
            if (key.endsWith(SECONDARY_SUFFIX)) {
                return base + " W";
            }
        }
        return "Meter";
    }

    /** Config-row title for a meter key. */
    public static String configLabel(String key, UsageSnapshot snapshot) {
        if (FIVE_HOUR.equals(key)) {
            return "Codex · 5 hours";
        }
        if (WEEKLY.equals(key)) {
            return "Codex · Weekly";
        }
        if (NEXT_RESET.equals(key)) {
            return "Next reset";
        }
        if (RESET_CREDITS.equals(key)) {
            return "Reset credits";
        }
        UsageLimit limit = findLimit(key, snapshot);
        if (limit != null) {
            String name = limit.displayName();
            if (key.endsWith(PRIMARY_SUFFIX)) {
                return name + " · 5 hours";
            }
            if (key.endsWith(SECONDARY_SUFFIX)) {
                return name + " · Weekly";
            }
            return name;
        }
        return key;
    }

    public static UsageLimit findLimit(String key, UsageSnapshot snapshot) {
        if (!isLimitKey(key) || snapshot == null || snapshot.additionalLimits == null) {
            return null;
        }
        String identity;
        if (key.endsWith(PRIMARY_SUFFIX)) {
            identity = key.substring(LIMIT_PREFIX.length(),
                    key.length() - PRIMARY_SUFFIX.length());
        } else if (key.endsWith(SECONDARY_SUFFIX)) {
            identity = key.substring(LIMIT_PREFIX.length(),
                    key.length() - SECONDARY_SUFFIX.length());
        } else {
            return null;
        }
        for (UsageLimit limit : snapshot.additionalLimits) {
            if (limit != null && identity.equals(limitIdentity(limit))) {
                return limit;
            }
        }
        return null;
    }

    public static boolean isLimitPrimary(String key) {
        return key != null && key.endsWith(PRIMARY_SUFFIX);
    }

    private static String shortLimitName(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "Limit";
        }
        String trimmed = displayName.trim();
        if (trimmed.length() <= 10) {
            return trimmed;
        }
        String[] parts = trimmed.split("[\\s\\-_/]+");
        if (parts.length > 0 && !parts[parts.length - 1].isEmpty()) {
            String last = parts[parts.length - 1];
            return last.length() > 10 ? last.substring(0, 10) : last;
        }
        return trimmed.substring(0, 10);
    }
}
