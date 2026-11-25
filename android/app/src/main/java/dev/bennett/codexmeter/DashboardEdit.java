package dev.bennett.codexmeter;

import android.content.Context;
import dev.bennett.codexmeter.glass.GlassModel;
import java.util.ArrayList;
import java.util.List;

/**
 * A dashboard tab's Edit mode, the same in Liquid Glass and Material 3 Expressive: the selected
 * tab's sections (Priority's) or cards (a provider's), each with a switch to show or hide it, in
 * the order the tab shows them, with a mover to change that order. Changes save and redraw the
 * page at once, so Done only goes back to it.
 */
final class DashboardEdit {
    private DashboardEdit() {
    }

    /** The selected tab's list, or null when it has nothing to edit (signed out everywhere). */
    static GlassModel.Section sections(MainActivity main) {
        return main.onPriorityTab() ? prioritySections(main) : providerSections(main, main.glassProvider());
    }

    private static GlassModel.Section prioritySections(MainActivity main) {
        Context activity = main;
        PriorityPage page = PriorityPage.build(main);
        if (!page.signedInAnywhere()) {
            return null;
        }
        GlassModel.Section section = new GlassModel.Section(null);
        section.header = "Choose what Priority shows, and drag the handles to change the order.";
        section.footer = "A section shows only while it has something in it.";
        List<String> order = PriorityPrefs.sectionOrder(activity);
        for (String key : order) {
            GlassModel.Row row = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, key);
            row.title = PriorityPage.label(key);
            Integer size = page.sizes.get(key);
            int n = size == null ? 0 : size;
            row.summary = n == 0 ? "Nothing right now" : n == 1 ? "1 item now" : n + " items now";
            row.checked = PriorityPrefs.isSectionShown(activity, key);
            row.onToggle = on -> {
                DiagnosticLog.info(activity, "user", "priority_section " + key + " " + on);
                PriorityPrefs.setSectionShown(activity, key, on);
                main.rebuild();
            };
            section.rows.add(row);
        }
        section.onMove = (from, to) -> {
            List<String> moved = new ArrayList<>(PriorityPrefs.sectionOrder(activity));
            moved.add(to, moved.remove(from));
            DiagnosticLog.info(activity, "user", "priority_section_moved " + from + " " + to);
            PriorityPrefs.setSectionOrder(activity, moved);
            main.rebuild();
        };
        return section;
    }

    private static GlassModel.Section providerSections(MainActivity main, UsageProvider provider) {
        Context activity = main;
        List<String[]> cards = GlassScreens.Reorder.cards(activity, provider);
        if (cards.isEmpty()) {
            return null;
        }
        GlassModel.Section section = new GlassModel.Section(null);
        section.header = "Choose the cards " + provider.displayName
                + " shows, and drag the handles to change the order.";
        section.footer = "Cards appear only while " + provider.vendorName
                + " reports data for them, and new ones show up here automatically.";
        for (String[] card : cards) {
            GlassModel.Row row = new GlassModel.Row(GlassModel.Row.Kind.SWITCH, card[0]);
            row.title = card[1];
            row.summary = card[2];
            row.checked = ProviderPreferences.isDashboardSectionVisible(activity, provider, card[0]);
            row.onToggle = on -> {
                DiagnosticLog.info(activity, "user", "dashboard_card " + provider.id + " " + card[0] + " " + on);
                ProviderPreferences.setDashboardSectionVisible(activity, provider, card[0], on);
                main.rebuild();
            };
            section.rows.add(row);
        }
        section.onMove = (from, to) -> {
            List<String> order = new ArrayList<>();
            for (String[] card : GlassScreens.Reorder.cards(activity, provider)) {
                order.add(card[0]);
            }
            order.add(to, order.remove(from));
            DiagnosticLog.info(activity, "user", "dashboard_card_moved " + provider.id + " " + from + " " + to);
            ProviderPreferences.setDashboardOrder(activity, provider, order);
            main.rebuild();
        };
        return section;
    }
}
