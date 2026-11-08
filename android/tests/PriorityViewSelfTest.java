package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Priority view: fixed order, local numbers only, closed schema for model prose. */
public final class PriorityViewSelfTest {
    private static final long NOW = 1_794_000_000_000L;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        orderIsOutageThenPerformanceThenLimitsThenMetersThenTip();
        unknownStatusIsNotAnIncident();
        nothingUrgentMeansNoCard();
        factsCarryNoNumbersOrNames();
        proseIsValidatedAgainstTheClosedSchema();
        greetingIsLocal();
        summaryTtl();
        usedUpLimitsAreNotWatched();
        System.out.println("Priority view: order, local facts, closed prose schema and TTL verified.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** A weekly window {@code used}% used, {@code hoursIn} hours into a 168-hour week. */
    private static UsageWindow weekly(int used, long hoursIn) {
        long resetAt = (NOW + (168 - hoursIn) * HOUR) / 1000L;
        return new UsageWindow(used, 168 * 3600L, 0L, resetAt);
    }

    private static UsageSnapshot snapshot(UsageWindow fiveHour, UsageWindow weekly) {
        return new UsageSnapshot("pro", true, false, fiveHour, weekly, 0, NOW);
    }

    private static ProviderStatus status(ProviderStatus.State state, String component) {
        return new ProviderStatus("x", state, Collections.singletonList(
                new ProviderStatus.Issue(component, state, "Elevated errors", "", NOW)), "https://status.example", NOW);
    }

    private static void orderIsOutageThenPerformanceThenLimitsThenMetersThenTip() {
        List<PriorityView.ProviderFacts> facts = new ArrayList<>();
        facts.add(new PriorityView.ProviderFacts("Codex", null, null, snapshot(null, weekly(90, 24))));
        facts.add(new PriorityView.ProviderFacts("Claude Code", status(ProviderStatus.State.DEGRADED, "Claude Code"),
                null, snapshot(null, weekly(100, 100))));
        facts.add(new PriorityView.ProviderFacts("Grok", status(ProviderStatus.State.PARTIAL_OUTAGE, "Grok Bot"), null,
                snapshot(null, weekly(10, 100))));
        List<PriorityView.Item> items = PriorityView.build(facts, NOW, UsagePace.BALANCED);
        List<PriorityView.Kind> kinds = new ArrayList<>();
        for (PriorityView.Item i : items) {
            kinds.add(i.kind);
        }
        check(kinds.get(0) == PriorityView.Kind.DOWNTIME, "outage first: " + kinds);
        check(items.get(0).title.startsWith("Grok Bot"), "names the component, not all of Grok: " + items.get(0).title);
        check(kinds.get(1) == PriorityView.Kind.PERFORMANCE_WARNING, "performance second: " + kinds);
        check(items.get(2).exhausted && items.get(2).provider.equals("Claude Code"), "used-up limit next");
        check(items.get(3).runsOutEarly && items.get(3).provider.equals("Codex"), "running out early next");
        check(items.get(3).detail.contains("before it resets in"), "pace compared with reset, not with habit: "
                + items.get(3).detail);
        check(!items.get(3).detail.contains("usual"), "never 'faster than usual'");
        check(kinds.get(4) == PriorityView.Kind.USAGE_METER && !items.get(4).urgent, "plain meter after urgent ones");
        check(kinds.get(kinds.size() - 1) == PriorityView.Kind.TIP, "tip last");
        check(PriorityView.needsAttention(items), "needs attention");
        String summary = PriorityView.localSummary(items, "");
        check(summary.equals("Needs attention: an official incident, a performance warning and 2 limits to watch."),
                summary);
    }

    private static void unknownStatusIsNotAnIncident() {
        List<PriorityView.Item> items = PriorityView.build(Collections.singletonList(
                new PriorityView.ProviderFacts("Cursor", ProviderStatus.unknown("cursor", "", NOW), null, null)),
                NOW, UsagePace.BALANCED);
        check(items.size() == 1 && items.get(0).kind == PriorityView.Kind.TIP, "unknown adds nothing");
        check(!PriorityView.needsAttention(items), "nothing to show");
    }

    private static void nothingUrgentMeansNoCard() {
        List<PriorityView.Item> items = PriorityView.build(Collections.singletonList(
                new PriorityView.ProviderFacts("Codex", null, null, snapshot(null, weekly(5, 100)))), NOW, UsagePace.BALANCED);
        check(!PriorityView.needsAttention(items), "a calm meter is not a priority");
    }

    private static void factsCarryNoNumbersOrNames() {
        List<PriorityView.ProviderFacts> facts = Arrays.asList(
                new PriorityView.ProviderFacts("Codex", null, null, snapshot(null, weekly(90, 24))),
                new PriorityView.ProviderFacts("Grok", status(ProviderStatus.State.MAJOR_OUTAGE, "grok.com"), null, null));
        List<PriorityView.Item> items = PriorityView.build(facts, NOW, UsagePace.BALANCED);
        String json = PriorityView.factsForModel(items).toString();
        for (char c : json.toCharArray()) {
            check(!Character.isDigit(c), "no numbers sent: " + json);
        }
        check(!json.contains("Elevated errors"), "incident text stays local: " + json);
        check(json.contains("\"type\":\"downtime\"") && json.contains("\"provider\":\"Grok\""), json);
        String withName = PriorityView.localSummary(items, "Sam");
        check(withName.startsWith("Hi Sam. "), withName);
        check(!json.contains("Sam"), "name never in facts");
    }

    private static void proseIsValidatedAgainstTheClosedSchema() {
        PriorityView.Prose ok = PriorityView.validateProse(
                "{\"summary\":\"Grok has an incident and Codex may run short before its reset.\",\"tip\":\"Check the status page.\"}");
        check(ok != null && ok.summary.startsWith("Grok"), "valid prose accepted");
        check(PriorityView.validateProse("```json\n{\"summary\":\"All calm.\",\"tip\":\"\"}\n```") != null, "fenced JSON ok");
        check(PriorityView.validateProse("{\"summary\":\"Codex has 12% left.\"}") == null, "digits rejected");
        check(PriorityView.validateProse("{\"summary\":\"Fine.\",\"meters\":[]}") == null, "extra keys rejected");
        check(PriorityView.validateProse("{\"summary\":\"<b>Fine</b>\"}") == null, "markup rejected");
        check(PriorityView.validateProse("{\"summary\":\"See https://example.com\"}") == null, "links rejected");
        check(PriorityView.validateProse("{\"summary\":\"\"}") == null, "empty rejected");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longText.append('a');
        }
        check(PriorityView.validateProse("{\"summary\":\"" + longText + "\"}") == null, "too long rejected");
        check(PriorityView.validateProse("not json") == null, "garbage rejected");
    }

    private static void greetingIsLocal() {
        check(PriorityView.greeting("  ").isEmpty(), "no name, no greeting");
        check(PriorityView.greeting("Ben").equals("Hi Ben. "), "greeting");
    }

    private static void summaryTtl() throws Exception {
        check(PrioritySummaryRules.isDue(NOW, 0L, false), "first time is due");
        check(!PrioritySummaryRules.isDue(NOW, NOW - HOUR, true), "changed facts wait six hours");
        check(PrioritySummaryRules.isDue(NOW, NOW - 7 * HOUR, true), "changed facts after six hours");
        check(!PrioritySummaryRules.isDue(NOW, NOW - 7 * HOUR, false), "same facts wait twelve hours");
        check(PrioritySummaryRules.isDue(NOW, NOW - 13 * HOUR, false), "same facts after twelve hours");
        JSONObject body = PrioritySummaryRules.requestBody(new JSONObject().put("cards", new org.json.JSONArray()), "big-pickle");
        check(body.getInt("max_tokens") <= 120, "bounded");
        check(body.getJSONArray("messages").length() == 2, "fixed instructions plus facts only");
        check(body.getJSONArray("messages").getJSONObject(0).getString("content").equals(PriorityView.INSTRUCTIONS), "fixed");
    }

    private static void usedUpLimitsAreNotWatched() {
        check(PriorityView.worthWatching(12, true), "running out early with some left is watched");
        check(!PriorityView.worthWatching(0, true), "a used-up limit is not watched");
        check(!PriorityView.worthWatching(0, false), "a used-up limit is not watched");
        check(!PriorityView.worthWatching(80, false), "a limit on pace is not watched");
    }
}
