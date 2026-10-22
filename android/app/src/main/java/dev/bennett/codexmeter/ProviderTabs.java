package dev.bennett.codexmeter;

/**
 * How the dashboard's bottom bar fits any number of providers. It follows One UI's own
 * BottomTabLayout rule rather than inventing a cap: up to four providers each get a tab; with
 * five or more, the first three get tabs and the rest sit in the stock "More" grid. The order
 * comes from Settings → Providers, so the user decides which three stay up front.
 */
public final class ProviderTabs {
    /** Menu slots in {@code res/menu/provider_tabs.xml}; one per provider. */
    public static final int SLOT_COUNT = 24;
    /** One UI shows four tabs side by side, but never five. */
    public static final int MAX_TABS_WITHOUT_MORE = 4;
    /** With an overflow, One UI keeps three tabs and adds "More". */
    public static final int TABS_BEFORE_MORE = 3;

    private ProviderTabs() {
    }

    /** Whether the bar appears at all: a single provider needs no switcher. */
    public static boolean showsBar(int enabled) {
        return enabled > 1;
    }

    /** Whether the stock "More" tab appears. */
    public static boolean showsMore(int enabled) {
        return enabled > MAX_TABS_WITHOUT_MORE;
    }

    /** Providers with a tab of their own. */
    public static int ownTabs(int enabled) {
        if (!showsBar(enabled)) return 0;
        return showsMore(enabled) ? TABS_BEFORE_MORE : enabled;
    }

    /** Providers reached through "More". */
    public static int inMore(int enabled) {
        return showsMore(enabled) ? enabled - TABS_BEFORE_MORE : 0;
    }
}
