package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One line of a grouped list on the Priority tab ({@link PriorityPage}): what it is on the left,
 * the short fact on the right. Pure Java, so the self-tests can check the dismiss model every
 * design shares.
 *
 * <p>The dismiss model: rows of Recent activity (a kept event) and New models (a newly listed
 * model) can be dismissed, and nothing else can. In every design a dismissible row is two lines
 * ({@link #lines}), swipes away or opens a menu on touch and hold with Dismiss, plus Dismiss all
 * when its list has more than one, and its list ends with the same hint ({@link #footnote}).
 */
final class PriorityNote {
    /** Under every list whose rows can be dismissed: how to clear them. */
    static final String DISMISS_HINT = "Swipe a row left, or touch and hold it, to dismiss it.";

    final UsageProvider provider;
    final String title;
    final String value;
    /** The kept event behind it, for dismissing ({@link ProviderAlerts#dismiss}); null when it can't be. */
    final String eventKey;
    /** The newly listed model behind it ({@link ModelDiscoveryStore#dismiss}); null for other notes. */
    final ModelDiscovery.Entry model;

    PriorityNote(UsageProvider provider, String title, String value) {
        this(provider, title, value, null, null);
    }

    PriorityNote(UsageProvider provider, String title, String value, String eventKey) {
        this(provider, title, value, eventKey, null);
    }

    PriorityNote(UsageProvider provider, String title, String value, String eventKey,
            ModelDiscovery.Entry model) {
        this.provider = provider;
        this.title = title == null ? "" : title;
        this.value = value == null ? "" : value;
        this.eventKey = eventKey;
        this.model = model;
    }

    boolean dismissible() {
        return eventKey != null || model != null;
    }

    /**
     * The row's two lines, the same in every design: what happened, then its short fact. A kept
     * event reads "Refilled early" over "Claude Code · 32m ago"; other notes keep their title
     * over their value.
     */
    String[] lines() {
        String prefix = provider == null ? null : provider.displayName + " · ";
        if (eventKey != null && prefix != null && title.startsWith(prefix)) {
            return new String[] {title.substring(prefix.length()), prefix + value};
        }
        return new String[] {title, value};
    }

    /** How many rows of a list can be dismissed. */
    static int dismissible(List<PriorityNote> notes) {
        int n = 0;
        for (PriorityNote note : notes) {
            if (note.dismissible()) {
                n++;
            }
        }
        return n;
    }

    /** Dismiss all is offered only when a list has more than one row to dismiss. */
    static boolean offersDismissAll(List<PriorityNote> notes) {
        return dismissible(notes) > 1;
    }

    /**
     * The footnote under a list: the list's own note (New models says where they come from),
     * then, when any row can be dismissed, the one hint every dismissible list shares. Null when
     * there is neither.
     */
    static String footnote(List<PriorityNote> notes, String own) {
        boolean hint = dismissible(notes) > 0;
        if (own == null || own.isEmpty()) {
            return hint ? DISMISS_HINT : null;
        }
        return hint ? own + "\n" + DISMISS_HINT : own;
    }

    /** New models as dismissible rows: the model's name (and stage), then who it was matched for. */
    static List<PriorityNote> fromModels(List<ModelDiscovery.Entry> items) {
        List<PriorityNote> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (ModelDiscovery.Entry e : items) {
            String stage = e.stage == null ? "" : e.stage;
            String name = e.name + (stage.isEmpty() ? "" : " (" + stage + ")");
            String value = e.matchedBy == null ? e.source.newOn() : "For " + e.matchedBy.displayName;
            out.add(new PriorityNote(e.matchedBy, name, value, null, e));
        }
        return out;
    }

    /** What dismissing some rows clears: kept events by key, and models. Nothing else. */
    static final class Dismissal {
        final List<String> eventKeys = new ArrayList<>();
        final List<ModelDiscovery.Entry> models = new ArrayList<>();
    }

    static Dismissal toDismiss(List<PriorityNote> notes) {
        Dismissal d = new Dismissal();
        for (PriorityNote n : notes == null ? Collections.<PriorityNote>emptyList() : notes) {
            if (n == null) {
                continue;
            }
            if (n.eventKey != null) {
                d.eventKeys.add(n.eventKey);
            }
            if (n.model != null) {
                d.models.add(n.model);
            }
        }
        return d;
    }
}
