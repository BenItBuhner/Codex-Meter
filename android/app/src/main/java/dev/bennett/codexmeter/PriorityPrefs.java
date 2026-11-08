package dev.bennett.codexmeter;

import android.content.Context;
import java.util.List;

/** The Priority view's switches, and the lines both designs show. */
final class PriorityPrefs {
    private PriorityPrefs() {}

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("priority_summary", Context.MODE_PRIVATE);
    }

    /**
     * The Priority tab, ahead of the provider tabs in every design: on by default. Off removes the
     * tab (and the New models list, which lives on it).
     */
    static boolean isViewEnabled(Context context) {
        return prefs(context).getBoolean("view", true);
    }

    static void setViewEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean("view", on).apply();
    }

    /** The Priority tab was the one showing when the app was last left, so it opens there again. */
    static boolean isTabSelected(Context context) {
        return prefs(context).getBoolean("tab_selected", false);
    }

    static void setTabSelected(Context context, boolean selected) {
        prefs(context).edit().putBoolean("tab_selected", selected).apply();
    }

    /** Greeting (added here, never sent) plus the written or the local summary. */
    static String summaryLine(Context context, List<PriorityView.Item> items, PriorityView.Prose prose) {
        String name = PrioritySummary.name(context);
        if (prose == null) {
            return PriorityView.localSummary(items, name);
        }
        return PriorityView.greeting(name) + prose.summary;
    }

    static String tipLine(List<PriorityView.Item> items, PriorityView.Prose prose) {
        if (prose != null && !prose.tip.isEmpty()) {
            return prose.tip + " (Summary written by an OpenCode free model from the items above.)";
        }
        if (prose != null) {
            return "Summary written by an OpenCode free model from the items above.";
        }
        for (PriorityView.Item item : items) {
            if (item.kind == PriorityView.Kind.TIP) {
                return item.title;
            }
        }
        return "";
    }
}
