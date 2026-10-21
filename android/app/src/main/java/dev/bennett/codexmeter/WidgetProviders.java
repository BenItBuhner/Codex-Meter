package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Which providers a home or lock-screen widget shows. Pure Java so the self-tests cover it.
 *
 * <p>A widget stores its providers as an ordered CSV of {@link UsageProvider} ids. Widgets saved
 * before multi-provider support stored a single id, which parses as a one-item list.
 */
public final class WidgetProviders {
    /** Most providers a home widget stacks; matches the battery-list row count. */
    public static final int MAX_HOME = 3;
    /** Wide lock widgets have two slots, so they show at most two providers. */
    public static final int MAX_LOCK_WIDE = 2;

    private WidgetProviders() {
    }

    /** Saved ids in order, skipping blanks, unknown ids, and duplicates. */
    public static List<UsageProvider> parse(String csv) {
        List<UsageProvider> providers = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return providers;
        }
        for (String part : csv.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            for (UsageProvider provider : UsageProvider.values()) {
                if (provider.id.equals(id) && !providers.contains(provider)) {
                    providers.add(provider);
                }
            }
        }
        return providers;
    }

    public static String serialize(Collection<UsageProvider> providers) {
        StringBuilder csv = new StringBuilder();
        if (providers != null) {
            for (UsageProvider provider : providers) {
                if (provider == null) continue;
                if (csv.length() > 0) csv.append(',');
                csv.append(provider.id);
            }
        }
        return csv.toString();
    }

    /**
     * {@code current} with one provider switched on or off, arranged in {@code order} (the
     * user's tab order). The last provider can't be turned off, and turning one on past
     * {@code max} is refused, so both return {@code current} unchanged.
     */
    public static List<UsageProvider> toggled(List<UsageProvider> current,
            UsageProvider provider, boolean on, List<UsageProvider> order, int max) {
        List<UsageProvider> base = current == null ? new ArrayList<>() : current;
        if (on && !base.contains(provider) && base.size() >= Math.max(1, max)) {
            return new ArrayList<>(base);
        }
        List<UsageProvider> result = new ArrayList<>();
        List<UsageProvider> sequence = order == null || order.isEmpty()
                ? UsageProvider.orderFromIds(null) : order;
        for (UsageProvider candidate : sequence) {
            boolean selected = candidate == provider ? on : base.contains(candidate);
            if (selected && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result.isEmpty() ? new ArrayList<>(base) : result;
    }

    /**
     * Provider for a widget that never saved one: Codex when it is signed in, so widgets placed
     * before providers existed keep showing Codex, otherwise the first signed-in provider in tab
     * order, otherwise Codex.
     */
    public static UsageProvider fallback(List<UsageProvider> order,
            Collection<UsageProvider> signedIn) {
        if (signedIn == null || signedIn.isEmpty() || signedIn.contains(UsageProvider.CODEX)) {
            return UsageProvider.CODEX;
        }
        if (order != null) {
            for (UsageProvider provider : order) {
                if (signedIn.contains(provider)) return provider;
            }
        }
        return UsageProvider.CODEX;
    }

    /**
     * Usage meters to show for one provider of a multi-provider widget: the widget's visible
     * five-hour/weekly meters that this provider actually reports, up to {@code max}. A provider
     * that reports neither (signed out, or no data yet) gets the first visible meter so its row
     * still shows a placeholder.
     */
    public static List<String> metersFor(List<String> visible, boolean hasFiveHour,
            boolean hasLongWindow, int max) {
        List<String> usage = new ArrayList<>();
        if (visible != null) {
            for (String key : visible) {
                if ((WidgetMeters.FIVE_HOUR.equals(key) || WidgetMeters.WEEKLY.equals(key))
                        && !usage.contains(key)) {
                    usage.add(key);
                }
            }
        }
        if (usage.isEmpty()) {
            usage.add(WidgetMeters.FIVE_HOUR);
            usage.add(WidgetMeters.WEEKLY);
        }
        List<String> reported = new ArrayList<>();
        for (String key : usage) {
            boolean has = WidgetMeters.FIVE_HOUR.equals(key) ? hasFiveHour : hasLongWindow;
            if (has) reported.add(key);
        }
        if (reported.isEmpty()) {
            // Cursor has no five-hour window: fall back to the long window before a placeholder.
            if (hasLongWindow) {
                reported.add(WidgetMeters.WEEKLY);
            } else if (hasFiveHour) {
                reported.add(WidgetMeters.FIVE_HOUR);
            } else {
                reported.add(usage.get(0));
            }
        }
        return WidgetMeters.cap(reported, Math.max(1, max));
    }

    /**
     * Meters per provider when {@code providers} share {@code slots} rows: two each when every
     * provider fits twice, otherwise one.
     */
    public static int metersPerProvider(int providers, int slots) {
        return providers > 0 && slots >= providers * 2 ? 2 : 1;
    }

    /** Six-letter tag for lock-screen labels, where space is tight. */
    public static String shortTag(UsageProvider provider) {
        switch (provider) {
            case CLAUDE_CODE:
                return "CLAUDE";
            case CURSOR:
                return "CURSOR";
            case CODEX:
            default:
                return "CODEX";
        }
    }

    /** "Codex", "Codex + Claude Code", "Codex + 2 more". */
    public static String summary(List<UsageProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return UsageProvider.DEFAULT.displayName;
        }
        if (providers.size() == 1) {
            return providers.get(0).displayName;
        }
        if (providers.size() == 2) {
            return providers.get(0).displayName + " + " + providers.get(1).displayName;
        }
        return providers.get(0).displayName + " + " + (providers.size() - 1) + " more";
    }
}
