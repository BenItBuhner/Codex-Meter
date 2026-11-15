package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Priority's one dismiss model (v3): Recent activity and New models rows dismiss the same way in
 * every design, one row at a time or all of one list, with the same two lines and the same hint.
 */
public final class PriorityNoteSelfTest {
    public static void main(String[] args) {
        onlyEventsAndModelsAreDismissible();
        eventRowsSplitIntoWhatThenWhereAndWhen();
        modelRowsNameTheModelAndWhoItIsFor();
        dismissAllOnlyForMoreThanOne();
        dismissClearsOnlyThatList();
        footnoteAlwaysEndsWithTheSameHint();
        System.out.println("Priority dismiss model: rows, lines, Dismiss all and hint verified.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static ModelDiscovery.Entry model(String id, String name, String stage, UsageProvider forProvider) {
        ModelDiscovery.Entry e = new ModelDiscovery.Entry(ModelDiscovery.Source.OPENROUTER, id);
        e.name = name;
        e.stage = stage;
        e.matchedBy = forProvider;
        return e;
    }

    private static PriorityNote event(String line, String ago, String key) {
        return new PriorityNote(UsageProvider.CLAUDE_CODE, UsageProvider.CLAUDE_CODE.displayName + " · " + line,
                ago, key);
    }

    private static void onlyEventsAndModelsAreDismissible() {
        check(event("Refilled early", "32m ago", "k1").dismissible(), "a kept event can be dismissed");
        check(PriorityNote.fromModels(Collections.singletonList(
                model("anthropic/fable-5.1", "Claude Fable 5.1", "", UsageProvider.CLAUDE_CODE))).get(0).dismissible(),
                "a new model can be dismissed");
        check(!new PriorityNote(UsageProvider.CODEX, "Codex · Monthly limit", "Renews in 2d").dismissible(),
                "Coming up rows can't be dismissed (they clear themselves)");
        check(!new PriorityNote(UsageProvider.CODEX, "Codex · 2 reset credits", "Ready to use").dismissible(),
                "Saved resets you can use can't be dismissed");
    }

    private static void eventRowsSplitIntoWhatThenWhereAndWhen() {
        String[] lines = event("Fable reset used", "32m ago", "k").lines();
        check("Fable reset used".equals(lines[0]), "first line is what happened: " + lines[0]);
        check("Claude Code · 32m ago".equals(lines[1]), "second line is provider and when: " + lines[1]);
        // a note that doesn't start with its provider keeps title over value
        String[] other = new PriorityNote(UsageProvider.CURSOR, "Something", "now", "k2").lines();
        check("Something".equals(other[0]) && "now".equals(other[1]), "other notes keep title over value");
    }

    private static void modelRowsNameTheModelAndWhoItIsFor() {
        List<PriorityNote> rows = PriorityNote.fromModels(Arrays.asList(
                model("a/1", "Claude Fable 5.1", "", UsageProvider.CLAUDE_CODE),
                model("b/2", "GPT-5.6 Codex", "preview", UsageProvider.CODEX),
                model("c/3", "Some model", "", null)));
        check(rows.size() == 3, "one row per model");
        check("Claude Fable 5.1".equals(rows.get(0).lines()[0]), "model name first");
        check("For Claude Code".equals(rows.get(0).lines()[1]), "who it is for second: " + rows.get(0).lines()[1]);
        check("GPT-5.6 Codex (preview)".equals(rows.get(1).title), "stage in brackets: " + rows.get(1).title);
        check("New on OpenRouter".equals(rows.get(2).value), "unmatched models name their catalog: " + rows.get(2).value);
        check(PriorityNote.fromModels(null).isEmpty(), "no models, no rows");
    }

    private static void dismissAllOnlyForMoreThanOne() {
        check(!PriorityNote.offersDismissAll(Collections.singletonList(event("a", "1m ago", "k"))),
                "one row: Dismiss only");
        check(PriorityNote.offersDismissAll(Arrays.asList(event("a", "1m ago", "k"), event("b", "2m ago", "j"))),
                "two rows: Dismiss all too");
        List<PriorityNote> mixed = new ArrayList<>();
        mixed.add(event("a", "1m ago", "k"));
        mixed.add(new PriorityNote(UsageProvider.CODEX, "x", "y"));
        check(!PriorityNote.offersDismissAll(mixed), "rows that can't be dismissed don't count");
    }

    private static void dismissClearsOnlyThatList() {
        ModelDiscovery.Entry m1 = model("a/1", "A", "", UsageProvider.CODEX);
        ModelDiscovery.Entry m2 = model("a/2", "B", "", UsageProvider.CODEX);
        PriorityNote.Dismissal models = PriorityNote.toDismiss(PriorityNote.fromModels(Arrays.asList(m1, m2)));
        check(models.eventKeys.isEmpty(), "dismissing New models leaves Recent activity alone");
        check(models.models.size() == 2 && models.models.contains(m1) && models.models.contains(m2),
                "Dismiss all on New models clears both models");
        PriorityNote.Dismissal one = PriorityNote.toDismiss(Collections.singletonList(event("a", "1m ago", "k1")));
        check(one.eventKeys.equals(Collections.singletonList("k1")) && one.models.isEmpty(),
                "dismissing one event clears just that event");
        check(PriorityNote.toDismiss(null).eventKeys.isEmpty(), "nothing to dismiss is safe");
    }

    private static void footnoteAlwaysEndsWithTheSameHint() {
        List<PriorityNote> events = Collections.singletonList(event("a", "1m ago", "k"));
        check(PriorityNote.DISMISS_HINT.equals(PriorityNote.footnote(events, null)), "hint alone under Recent activity");
        String models = PriorityNote.footnote(PriorityNote.fromModels(Collections.singletonList(
                model("a/1", "A", "", UsageProvider.CODEX))), "Shown for Codex.");
        check(models.startsWith("Shown for Codex.") && models.endsWith(PriorityNote.DISMISS_HINT),
                "New models keeps its note, then the same hint: " + models);
        check(PriorityNote.footnote(Collections.singletonList(new PriorityNote(UsageProvider.CODEX, "x", "y")),
                null) == null, "no hint under lists that can't be dismissed");
    }
}
