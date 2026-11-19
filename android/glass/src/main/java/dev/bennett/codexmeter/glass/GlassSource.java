package dev.bennett.codexmeter.glass;

import java.util.List;

/**
 * The app behind the Liquid Glass screens: what they show and what their controls do. Every call
 * comes on the thread the screens draw on, and the source calls the listener from
 * {@link #subscribe} on that thread too.
 */
public interface GlassSource {
    /** Calls {@code onChange} whenever anything shown may have changed; returns the unsubscribe. */
    Runnable subscribe(Runnable onChange);

    // ------------------------------------------------------------------ dashboard

    /** The provider tabs, in order. Fewer than two hides the tab bar. */
    List<GlassModel.Tab> tabs();

    String selectedTab();

    void selectTab(String id);

    GlassModel.Dashboard dashboard();

    boolean refreshing();

    void refresh();

    /** The dashboard bar's buttons, right to left of the title: labels, icons, and what each does. */
    List<MenuEntry> menu();

    final class MenuEntry {
        public final String label;
        public final String icon;
        public final boolean destructive;
        public final Runnable onSelect;

        /**
         * A filter menu instead of a plain button: the switches it opens with (checked = shown).
         * Picking one flips it and keeps the menu open. Null for a plain button.
         */
        public final List<Toggle> toggles;
        /** Actions under the switches, after a separator (Rearrange cards). */
        public final List<MenuEntry> actions;

        public MenuEntry(String label, String icon, boolean destructive, Runnable onSelect) {
            this(label, icon, destructive, onSelect, null, null);
        }

        public MenuEntry(String label, String icon, List<Toggle> toggles, List<MenuEntry> actions) {
            this(label, icon, false, null, toggles, actions);
        }

        private MenuEntry(String label, String icon, boolean destructive, Runnable onSelect, List<Toggle> toggles,
                List<MenuEntry> actions) {
            this.label = label;
            this.icon = icon;
            this.destructive = destructive;
            this.onSelect = onSelect;
            this.toggles = toggles;
            this.actions = actions;
        }
    }

    /** One switch in a bar's filter menu: what it shows, whether it is on, and how to flip it. */
    final class Toggle {
        public final String label;
        public final boolean on;
        public final Runnable flip;

        public Toggle(String label, boolean on, Runnable flip) {
            this.label = label;
            this.on = on;
            this.flip = flip;
        }
    }

    // ------------------------------------------------------------------ settings

    GlassModel.Page page(String id);

    void toggle(String page, String key, boolean on);

    void choose(String page, String key, int index);

    void press(String page, String key);

    /** Leaving Settings (its back button on the first page). */
    void closeSettings();

    /**
     * A settings page the app asked to open (from a row's own code, or Settings opened at a
     * page), or null. Taking it clears it.
     */
    Nav takeNav();

    /** True when {@code page} opens with its lists already in edit mode (Rearrange cards). */
    default boolean editsOnOpen(String page) {
        return false;
    }

    /** The settings page now on top (to reopen it after the activity is rebuilt). */
    default void shown(String page) {
    }

    final class Nav {
        /** start again from the first page */
        public final boolean reset;
        /** the page to open on top, or null */
        public final String page;
        /** with reset: the page alone, without the first page under it (back leaves Settings) */
        public final boolean alone;
        /** close the page on top instead */
        public final boolean pop;

        public Nav(boolean reset, String page) {
            this(reset, page, false, false);
        }

        public Nav(boolean reset, String page, boolean alone, boolean pop) {
            this.reset = reset;
            this.page = page;
            this.alone = alone;
            this.pop = pop;
        }
    }

    // ------------------------------------------------------------------ dialogs

    /** The alert or sheet to show over {@code screen} ("dashboard" or "settings"), or null. */
    GlassModel.Dialog dialog(String screen);
}
