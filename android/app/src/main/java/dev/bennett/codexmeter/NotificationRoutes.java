package dev.bennett.codexmeter;

import java.util.Locale;

/**
 * Pure-Java routing for notification taps, so every notification opens the provider it is
 * about (Claude Code notification -> Claude Code usage, Cursor -> Cursor, and so on).
 *
 * <p>Each provider gets its own {@code codexmeter://usage/<provider>} data URI and its own
 * PendingIntent request code. {@code PendingIntent.filterEquals} ignores extras, so with one
 * shared request code and no data URI every provider would share (and overwrite) a single
 * PendingIntent; the URI and request code keep them distinct. {@link MainActivity} reads the
 * provider from the extra or, as a fallback, from the URI, on both cold start
 * ({@code onCreate}) and warm start ({@code onNewIntent}).
 */
public final class NotificationRoutes {
    public static final String SCHEME = "codexmeter";
    public static final String HOST_USAGE = "usage";
    public static final String ACTION_OPEN_PROVIDER = "dev.bennett.codexmeter.action.OPEN_PROVIDER";
    /** Request-code stride per surface; leaves room for every provider in {@link UsageProvider}. */
    static final int STRIDE = 64;

    private NotificationRoutes() {
    }

    /** {@code codexmeter://usage/claude_code?from=now_bar}. */
    public static String uri(UsageProvider provider, String surface) {
        UsageProvider target = provider == null ? UsageProvider.DEFAULT : provider;
        String from = surface == null ? "" : surface.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
        return SCHEME + "://" + HOST_USAGE + "/" + target.id
                + (from.isEmpty() ? "" : "?from=" + from);
    }

    /** A request code unique per (surface base, provider). */
    public static int requestCode(int base, UsageProvider provider) {
        UsageProvider target = provider == null ? UsageProvider.DEFAULT : provider;
        return base * STRIDE + target.ordinal();
    }

    /**
     * The provider a launch intent asks for: the explicit extra wins, then a
     * {@code codexmeter://usage/<id>} URI. Returns null when the intent names no provider, so
     * ordinary launcher starts keep the last-selected provider.
     */
    public static UsageProvider requestedProvider(String extra, String scheme, String host,
            String path) {
        UsageProvider fromExtra = known(extra);
        if (fromExtra != null) return fromExtra;
        if (!SCHEME.equalsIgnoreCase(scheme == null ? "" : scheme)
                || !HOST_USAGE.equalsIgnoreCase(host == null ? "" : host)) {
            return null;
        }
        String id = path == null ? "" : path.trim();
        while (id.startsWith("/")) id = id.substring(1);
        int slash = id.indexOf('/');
        if (slash >= 0) id = id.substring(0, slash);
        return known(id);
    }

    /** True for URIs this class builds, so the activity can clear them after routing. */
    public static boolean isUsageUri(String scheme, String host) {
        return SCHEME.equalsIgnoreCase(scheme == null ? "" : scheme)
                && HOST_USAGE.equalsIgnoreCase(host == null ? "" : host);
    }

    /** Unlike {@link UsageProvider#fromId}, unknown ids return null instead of the default. */
    static UsageProvider known(String id) {
        if (id == null) return null;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        for (UsageProvider provider : UsageProvider.values()) {
            if (provider.id.equals(normalized)) return provider;
        }
        return null;
    }
}
