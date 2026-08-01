package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Stable keys for the optional Usage history highlights: the chart guide, the tappable
 * previous-window list, each insight row, and the dollar value estimates. Visibility is
 * stored as a comma-separated list of keys the user toggled away from their default, so
 * changing a default later never overrides an explicit user choice. The chart itself and
 * the clear-history action are always shown and have no key here.
 */
public final class HistorySections {
    public static final String GUIDE = "guide";
    public static final String WINDOW_LIST = "window_list";
    public static final String INSIGHT_PACE = "insight_pace";
    public static final String INSIGHT_EXHAUSTION = "insight_exhaustion";
    public static final String INSIGHT_AVERAGE = "insight_average";
    public static final String INSIGHT_PEAK = "insight_peak";
    public static final String VALUE_ESTIMATES = "value_estimates";

    private static final List<String> ALL = Arrays.asList(GUIDE, WINDOW_LIST, INSIGHT_PACE,
            INSIGHT_EXHAUSTION, INSIGHT_AVERAGE, INSIGHT_PEAK, VALUE_ESTIMATES);

    private HistorySections() {
    }

    /** Every customizable highlight, in the order the customize sheet lists them. */
    public static List<String> all() {
        return ALL;
    }

    /** The guide is opt-in extra reading; every other highlight starts visible. */
    public static boolean defaultVisible(String key) {
        return !GUIDE.equals(key);
    }

    /** User-facing label for a highlight key. */
    public static String label(String key) {
        switch (key == null ? "" : key) {
            case GUIDE:
                return "How to read the charts";
            case WINDOW_LIST:
                return "Previous window list";
            case INSIGHT_PACE:
                return "Pace vs. typical";
            case INSIGHT_EXHAUSTION:
                return "Projected exhaustion";
            case INSIGHT_AVERAGE:
                return "Average completed window";
            case INSIGHT_PEAK:
                return "Peak burn rate";
            case VALUE_ESTIMATES:
                return "Value estimates ($)";
            default:
                return key == null ? "" : key;
        }
    }

    /** Whether a highlight is visible given the saved override CSV. */
    public static boolean isVisible(String overridesCsv, String key) {
        return defaultVisible(key) != parseCsv(overridesCsv).contains(key);
    }

    /** Returns the override CSV updated so the key resolves to the requested visibility. */
    public static String setVisible(String overridesCsv, String key, boolean visible) {
        if (key == null || key.trim().isEmpty()) {
            return serialize(parseCsv(overridesCsv));
        }
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(parseCsv(overridesCsv)));
        String trimmed = key.trim();
        if (visible == defaultVisible(trimmed)) {
            keys.remove(trimmed);
        } else if (!keys.contains(trimmed)) {
            keys.add(trimmed);
        }
        return serialize(keys);
    }

    private static String serialize(List<String> keys) {
        StringBuilder csv = new StringBuilder();
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
