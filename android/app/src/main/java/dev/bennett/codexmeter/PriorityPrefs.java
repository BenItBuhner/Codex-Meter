package dev.bennett.codexmeter;

import android.content.Context;
import java.util.List;

/** The Priority view's switches, and the lines both designs show. */
final class PriorityPrefs {
    private PriorityPrefs() {}

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("priority_summary", Context.MODE_PRIVATE);
    }

    /** The Priority card itself: on by default; it only appears when something needs attention. */
    static boolean isViewEnabled(Context context) {
        return prefs(context).getBoolean("view", true);
    }

    static void setViewEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean("view", on).apply();
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
