package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Surprise refills and new saved resets for every provider, and how saved resets are named. */
public final class ProviderEventsSelfTest {
    private static final long NOW = 1_794_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    public static void main(String[] args) throws Exception {
        refillBeforeTheScheduledResetIsASurprise();
        ordinaryResetAndUnknownResetAreNot();
        modelLimitRefillIsNamed();
        fableResetsAreNamedHoweverTheServerMarksThem();
        allModelResetsKeepTheirNames();
        modelOnlyResetDoesNotCountAsAWeeklyReset();
        savedResetsAreCountedByKind();
        newGrantsAndTopUpsAreAnnounced();
        resetCreditCountRise();
        eventsAreKeptForADay();
        monthlyLimitRenewingSoon();
        usedResetsAreFound();
        aFableResetExplainsTheFableRefill();
        aUseFoundLaterFoldsInTheEarlierSurprise();
        eventsCanBeDismissed();
        resetKindsToChooseFrom();
        System.out.println("Provider events: surprise refills, new and used saved resets, reset choices, dismissing and renewals verified.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static UsageWindow weekly(int used, long resetAtMillis) {
        return new UsageWindow(used, 7 * 24 * 3600L, 0L, resetAtMillis / 1000L);
    }

    private static UsageSnapshot snapshot(UsageWindow fiveHour, UsageWindow weekly, List<UsageLimit> extra, long at) {
        return new UsageSnapshot("", true, false, fiveHour, weekly, extra, null, -1, at);
    }

    private static void refillBeforeTheScheduledResetIsASurprise() {
        UsageSnapshot before = snapshot(null, weekly(80, NOW + 3 * DAY), Collections.<UsageLimit>emptyList(), NOW - HOUR);
        UsageSnapshot after = snapshot(null, weekly(0, NOW + 7 * DAY), Collections.<UsageLimit>emptyList(), NOW);
        List<String> labels = ProviderEvents.refilledEarly(before, after, "5-hour");
        check(labels.equals(Collections.singletonList("Weekly")), "weekly refilled early: " + labels);
        ProviderEvents.Event e = ProviderEvents.surpriseRefill(UsageProvider.CLAUDE_CODE, labels, NOW);
        check(e.title.equals("Surprise Claude Code refill"), e.title);
        check(e.detail.equals("Your weekly limit jumped back to 100% before its scheduled reset."), e.detail);
        check(e.line.equals("Weekly refilled early"), e.line);
        check(ProviderEvents.surpriseRefill(UsageProvider.CURSOR, new ArrayList<String>(), NOW) == null,
                "nothing refilled, nothing said");
    }

    private static void ordinaryResetAndUnknownResetAreNot() {
        UsageSnapshot before = snapshot(null, weekly(80, NOW - HOUR), Collections.<UsageLimit>emptyList(), NOW - 2 * HOUR);
        UsageSnapshot after = snapshot(null, weekly(0, NOW + 7 * DAY), Collections.<UsageLimit>emptyList(), NOW);
        check(ProviderEvents.refilledEarly(before, after, "").isEmpty(), "a reset on schedule is not a surprise");
        UsageSnapshot unknown = snapshot(null, weekly(80, 0L), Collections.<UsageLimit>emptyList(), NOW - HOUR);
        check(ProviderEvents.refilledEarly(unknown, after, "").isEmpty(), "no reset time, no claim");
        check(ProviderEvents.refilledEarly(null, after, "").isEmpty(), "first refresh says nothing");
    }

    private static void modelLimitRefillIsNamed() {
        UsageLimit fableBefore = new UsageLimit("claude_weekly_scoped_fable", "Fable only", "", true, true,
                weekly(100, NOW + 2 * DAY), null);
        UsageLimit fableAfter = new UsageLimit("claude_weekly_scoped_fable", "Fable only", "", true, false,
                weekly(0, NOW + 7 * DAY), null);
        UsageSnapshot before = snapshot(null, weekly(40, NOW + 2 * DAY), Collections.singletonList(fableBefore), NOW - HOUR);
        UsageSnapshot after = snapshot(null, weekly(40, NOW + 2 * DAY), Collections.singletonList(fableAfter), NOW);
        List<String> labels = ProviderEvents.refilledEarly(before, after, "");
        check(labels.equals(Collections.singletonList("Fable only")), "the Fable limit refilled: " + labels);
        check(ProviderEvents.surpriseRefill(UsageProvider.CLAUDE_CODE, labels, NOW).detail
                .startsWith("Your Fable only limit jumped back"), "named as the dashboard names it");
        ProviderEvents.Event both = ProviderEvents.surpriseRefill(UsageProvider.CLAUDE_CODE,
                Arrays.asList("Weekly", "Fable only"), NOW);
        check(both.detail.startsWith("Your weekly and Fable only limits jumped back"), both.detail);
    }

    private static ClaudeSavedResets saved(String... grants) throws Exception {
        return ClaudeSavedResets.fromApiJson(new JSONObject("{\"eligible\":true,\"at_limit\":false,\"grants\":["
                + String.join(",", grants) + "]}"), NOW);
    }

    private static String grant(String id, int left, String extra) {
        return "{\"id\":\"" + id + "\",\"resets_total\":" + left + ",\"resets_left\":" + left
                + ",\"ends_at\":\"2071-01-01T00:00:00Z\",\"usable_now\":true" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static String title(String extra) throws Exception {
        return saved(grant("g_1", 1, extra)).grants.get(0).title();
    }

    private static void fableResetsAreNamedHoweverTheServerMarksThem() throws Exception {
        check(title("\"type\":\"full\",\"scope\":{\"model\":{\"id\":null,\"display_name\":\"Fable\"},\"surface\":null}")
                .equals("Fable reset"), "a scope like the usage rows'");
        check(title("\"type\":\"full\",\"model\":\"fable\"").equals("Fable reset"), "a model field");
        check(title("\"type\":\"full\",\"clears\":[\"seven_day_fable\"]").equals("Fable reset"), "a model-only clears list");
        check(title("\"type\":\"weekly\",\"clears\":[\"weekly_scoped_fable\"]").equals("Fable reset"), "the scoped limit id");
        check(title("\"type\":\"fable\",\"label\":\"Limit reset\"").equals("Fable reset"), "an unknown type");
        check(title("\"type\":\"full\",\"label\":\"Fable reset\"").equals("Fable reset"), "the label alone");
        ClaudeSavedResets.Grant g = saved(grant("g_1", 3, "\"type\":\"full\",\"label\":\"Fable reset\"")).grants.get(0);
        check(g.modelOnly() && g.clearsLabel().equals("Fable limit"), "says what it refills");
        ClaudeSavedResets kept = ClaudeSavedResets.fromJson(saved(grant("g_1", 3, "\"model\":\"fable\"")).toJson());
        check(kept.grants.get(0).title().equals("Fable reset"), "the name survives being saved");
        check(kept.toResetCreditsSnapshot(NOW).credits.get(0).title.equals("Fable reset"), "and reaches the reset screen");
    }

    private static void allModelResetsKeepTheirNames() throws Exception {
        check(title("\"type\":\"full\",\"label\":\"Full reset\"").equals("Full reset"), "full");
        check(title("\"type\":\"full\",\"clears\":[\"five_hour\",\"seven_day\"]").equals("Full reset"), "full by clears");
        check(title("\"type\":\"session\",\"label\":\"Limit reset\",\"clears\":[\"five_hour\"]").equals("Session reset"), "session");
        check(title("\"type\":\"weekly\",\"clears\":[\"seven_day\"]").equals("Weekly reset"), "weekly");
        check(title("\"type\":\"full\",\"clears\":[\"seven_day\",\"seven_day_fable\"]").equals("Full reset"),
                "all models plus a model is still a full reset");
        check(title("\"type\":\"full\",\"clears\":[\"seven_day_opus\",\"seven_day_sonnet\"]").equals("Full reset"),
                "two models is not one model's reset");
        check(title("\"label\":\"Limit reset\"").equals("Limit reset"), "no type falls back to the label");
        check(title("\"scope\":{\"model\":null,\"surface\":null},\"type\":\"full\"").equals("Full reset"), "an empty scope");
    }

    private static void modelOnlyResetDoesNotCountAsAWeeklyReset() throws Exception {
        ClaudeSavedResets fable = saved(grant("g_1", 1, "\"type\":\"model\",\"clears\":[\"seven_day_fable\"]"));
        check(fable.usableGrantFor("weekly", NOW) == null, "a Fable reset is not spent on the all-models weekly limit");
        check(fable.usableGrant(NOW) != null, "it can still be used by hand");
        ClaudeSavedResets full = saved(grant("g_1", 1, "\"type\":\"full\""));
        check(full.usableGrantFor("weekly", NOW) != null, "a full reset still is");
    }

    private static void savedResetsAreCountedByKind() throws Exception {
        ClaudeSavedResets s = saved(grant("g_1", 3, "\"model\":\"fable\""), grant("g_2", 1, "\"type\":\"full\""));
        check(s.inventoryLabel(NOW).equals("3 Fable resets and 1 Full reset"), s.inventoryLabel(NOW));
        check(saved(grant("g_1", 3, "\"model\":\"fable\"")).availableTitle(NOW).equals("3 Fable resets available"), "title");
        check(saved().availableTitle(NOW).equals("No resets available"), "none");
    }

    private static void newGrantsAndTopUpsAreAnnounced() throws Exception {
        ClaudeSavedResets before = saved(grant("g_1", 1, "\"type\":\"full\""));
        ClaudeSavedResets after = saved(grant("g_1", 1, "\"type\":\"full\""), grant("g_2", 3, "\"model\":\"fable\""));
        Map<String, Integer> added = ProviderEvents.addedSavedResets(before, after, NOW);
        check(added.size() == 1 && added.get("Fable reset") == 3, "three new Fable resets: " + added);
        ProviderEvents.Event e = ProviderEvents.newSavedResets(UsageProvider.CLAUDE_CODE, added, 4, "Mar 9", NOW);
        check(e.title.equals("Claude Code: 3 Fable resets added"), e.title);
        check(e.detail.equals("You now have 4 saved resets. The soonest expires Mar 9."), e.detail);
        check(e.line.equals("3 Fable resets added"), e.line);
        check(ProviderEvents.addedSavedResets(after, after, NOW).isEmpty(), "nothing new, nothing said");
        check(ProviderEvents.addedSavedResets(after, before, NOW).isEmpty(), "a spent reset is not news");
        ClaudeSavedResets more = saved(grant("g_1", 2, "\"type\":\"full\""));
        check(ProviderEvents.addedSavedResets(before, more, NOW).get("Full reset") == 1, "a grant that grew");
        check(ProviderEvents.newSavedResets(UsageProvider.CLAUDE_CODE,
                ProviderEvents.addedSavedResets(before, more, NOW), 2, "", NOW).detail
                .equals("You now have 2 saved resets."), "no expiry, no expiry sentence");
    }

    private static void resetCreditCountRise() {
        ProviderEvents.Event e = ProviderEvents.newResetCredits(UsageProvider.CODEX, 1, 3, NOW);
        check(e.title.equals("Codex: 2 reset credits added") && e.detail.equals("You now have 3."), e.title);
        check(ProviderEvents.newResetCredits(UsageProvider.CODEX, 3, 3, NOW) == null, "same count");
        check(ProviderEvents.newResetCredits(UsageProvider.CODEX, 3, 2, NOW) == null, "a spent credit");
    }

    private static void eventsAreKeptForADay() {
        ProviderEvents.Event old = new ProviderEvents.Event(ProviderEvents.NEW_RESETS, "codex", "a", "A", "", NOW - 25 * HOUR);
        ProviderEvents.Event recent = new ProviderEvents.Event(ProviderEvents.NEW_RESETS, "codex", "b", "B", "", NOW - 3 * HOUR);
        ProviderEvents.Event fresh = new ProviderEvents.Event(ProviderEvents.SURPRISE_REFILL, "cursor", "c", "C", "d", NOW);
        List<ProviderEvents.Event> kept = ProviderEvents.remember(Arrays.asList(old, recent), fresh, NOW);
        check(kept.size() == 2 && kept.get(0).title.equals("C") && kept.get(1).title.equals("B"), "newest first, old dropped");
        List<ProviderEvents.Event> back = ProviderEvents.fromJson(ProviderEvents.toJson(kept));
        check(back.size() == 2 && back.get(0).detail.equals("d") && back.get(0).line.equals("c")
                && back.get(0).at == NOW, "round trip");
        check(ProviderEvents.fromJson("not json").isEmpty(), "garbage is an empty list");
        check(ProviderEvents.ago(NOW - 3 * HOUR, NOW).equals("3h ago"), "hours");
        check(ProviderEvents.ago(NOW - 30_000L, NOW).equals("Just now"), "just now");
        check(ProviderEvents.ago(NOW - 12 * 60_000L, NOW).equals("12m ago"), "minutes");
    }

    private static void monthlyLimitRenewingSoon() {
        UsageWindow soon = new UsageWindow(60, 30 * 24 * 3600L, 0L, (NOW + 2 * DAY) / 1000L);
        UsageWindow later = new UsageWindow(60, 30 * 24 * 3600L, 0L, (NOW + 10 * DAY) / 1000L);
        check(ProviderEvents.renewsSoon(soon, NOW, NOW), "two days out is soon");
        check(!ProviderEvents.renewsSoon(later, NOW, NOW), "ten days out is not");
        check(!ProviderEvents.renewsSoon(null, NOW, NOW), "no limit");
        check(ProviderEvents.duration(2 * DAY + 4 * HOUR).equals("2d 4h"), "days");
        check(ProviderEvents.duration(5 * HOUR + 12 * 60_000L).equals("5h 12m"), "hours");
    }

    private static UsageLimit fable(int used, long resetAt) {
        return new UsageLimit("claude_weekly_scoped_fable", "Fable only", "", true, used >= 100,
                weekly(used, resetAt), null);
    }

    private static void usedResetsAreFound() throws Exception {
        ClaudeSavedResets before = saved(grant("g_1", 2, "\"type\":\"full\""), grant("g_2", 1, "\"model\":\"fable\""));
        ClaudeSavedResets fableGone = saved(grant("g_1", 2, "\"type\":\"full\""));
        List<ProviderEvents.Use> used = ProviderEvents.usedSavedResets(before, fableGone, NOW);
        check(used.size() == 1 && used.get(0).title.equals("Fable reset") && used.get(0).scope.equals("Fable"),
                "a Fable grant gone before it expired was used: " + used.size());
        ClaudeSavedResets oneFewer = saved(grant("g_1", 1, "\"type\":\"full\""), grant("g_2", 1, "\"model\":\"fable\""));
        used = ProviderEvents.usedSavedResets(before, oneFewer, NOW);
        check(used.size() == 1 && used.get(0).title.equals("Full reset") && used.get(0).fiveHour && used.get(0).weekly,
                "a grant holding one fewer was used");
        check(ProviderEvents.usedSavedResets(before, before, NOW).isEmpty(), "nothing changed, nothing used");
        check(ProviderEvents.usedSavedResets(null, before, NOW).isEmpty(), "first list says nothing");
        ClaudeSavedResets closed = ClaudeSavedResets.fromApiJson(new JSONObject("{\"eligible\":false,\"grants\":[]}"), NOW);
        check(ProviderEvents.usedSavedResets(before, closed, NOW).isEmpty(), "an account no longer offered resets used none");
        ProviderEvents.Event e = ProviderEvents.resetUsed(UsageProvider.CLAUDE_CODE,
                ProviderEvents.usedSavedResets(before, fableGone, NOW), null, NOW);
        check(e.line.equals("Fable reset used"), e.line);
        check(e.detail.equals("It refills your Fable limit only."), e.detail);
        check(e.kind.equals(ProviderEvents.RESET_USED), "kind");
    }

    private static void aFableResetExplainsTheFableRefill() {
        UsageSnapshot before = snapshot(null, weekly(40, NOW + 2 * DAY), Collections.singletonList(fable(100, NOW + 2 * DAY)), NOW - HOUR);
        UsageSnapshot after = snapshot(null, weekly(40, NOW + 2 * DAY), Collections.singletonList(fable(0, NOW + 7 * DAY)), NOW);
        List<String> labels = ProviderEvents.refilledEarly(before, after, "5-hour");
        ProviderEvents.Use fableUse = new ProviderEvents.Use("Fable reset", "Fable", false, false, "Fable limit", NOW - 20 * 60_000L);
        List<String> rest = new ArrayList<>();
        List<ProviderEvents.Use> by = new ArrayList<>();
        List<String> explained = ProviderEvents.explainedByUses(labels, "5-hour", Collections.singletonList(fableUse), NOW, rest, by);
        check(explained.equals(Collections.singletonList("Fable only")) && rest.isEmpty() && by.size() == 1,
                "the Fable refill is the Fable reset's doing: " + explained + rest);
        ProviderEvents.Event e = ProviderEvents.resetUsed(UsageProvider.CLAUDE_CODE, by, explained, NOW);
        check(e.line.equals("Fable reset used · Fable only refilled"), e.line);
        // a Fable reset does not explain a weekly refill
        explained = ProviderEvents.explainedByUses(Arrays.asList("Weekly"), "5-hour", Collections.singletonList(fableUse), NOW, rest, null);
        check(explained.isEmpty() && rest.contains("Weekly"), "a Fable reset leaves the weekly limit a surprise");
        // a full reset explains the 5-hour, weekly and model limits
        ProviderEvents.Use full = new ProviderEvents.Use("Full reset", "", true, true, "5-hour and weekly limits", NOW - HOUR);
        List<String> rest2 = new ArrayList<>();
        explained = ProviderEvents.explainedByUses(Arrays.asList("5-hour", "Weekly", "Fable only"), "5-hour",
                Collections.singletonList(full), NOW, rest2, null);
        check(explained.size() == 3 && rest2.isEmpty(), "a full reset explains them all: " + explained);
        // a session reset explains only the 5-hour limit
        ProviderEvents.Use session = new ProviderEvents.Use("Session reset", "", true, false, "5-hour limit", NOW - HOUR);
        List<String> rest3 = new ArrayList<>();
        explained = ProviderEvents.explainedByUses(Arrays.asList("5-hour", "Weekly"), "5-hour",
                Collections.singletonList(session), NOW, rest3, null);
        check(explained.equals(Collections.singletonList("5-hour")) && rest3.equals(Collections.singletonList("Weekly")),
                "a session reset explains the 5-hour limit only");
        // a use from long ago explains nothing
        ProviderEvents.Use old = new ProviderEvents.Use("Fable reset", "Fable", false, false, "Fable limit", NOW - 7 * HOUR);
        check(ProviderEvents.explainedByUses(labels, "5-hour", Collections.singletonList(old), NOW, new ArrayList<String>(), null)
                .isEmpty(), "too old to explain it");
    }

    private static void aUseFoundLaterFoldsInTheEarlierSurprise() {
        ProviderEvents.Event surprise = ProviderEvents.surpriseRefill(UsageProvider.CLAUDE_CODE,
                Collections.singletonList("Fable only"), NOW - 30 * 60_000L);
        ProviderEvents.Event other = ProviderEvents.surpriseRefill(UsageProvider.CURSOR,
                Collections.singletonList("Monthly"), NOW - 40 * 60_000L);
        List<ProviderEvents.Use> uses = Collections.singletonList(
                new ProviderEvents.Use("Fable reset", "Fable", false, false, "Fable limit", NOW));
        ProviderEvents.Event use = ProviderEvents.resetUsed(UsageProvider.CLAUDE_CODE, uses, null, NOW);
        List<ProviderEvents.Event> out = ProviderEvents.explainEarlierRefills(Arrays.asList(surprise, other), use, uses, NOW);
        check(out.size() == 2, "the surprise is folded into the use: " + out.size());
        check(out.get(0).line.equals("Fable reset used · Fable only refilled"), out.get(0).line);
        check(out.get(1).provider.equals("cursor"), "another provider's refill stays");
        // stored events keep their labels
        ProviderEvents.Event back = ProviderEvents.fromJson(ProviderEvents.toJson(Collections.singletonList(surprise))).get(0);
        check(back.labels.equals(Collections.singletonList("Fable only")), "labels survive being saved");
    }

    private static void eventsCanBeDismissed() {
        ProviderEvents.Event a = new ProviderEvents.Event(ProviderEvents.SURPRISE_REFILL, "claude_code", "Fable only refilled early",
                "A", "", NOW - HOUR);
        ProviderEvents.Event b = new ProviderEvents.Event(ProviderEvents.NEW_RESETS, "claude_code", "1 Weekly reset added",
                "B", "", NOW - 9 * HOUR);
        List<ProviderEvents.Event> left = ProviderEvents.without(Arrays.asList(a, b), a.key());
        check(left.size() == 1 && left.get(0).title.equals("B"), "dismissing removes that one only");
        check(!a.key().equals(b.key()), "keys differ");
    }

    private static void resetKindsToChooseFrom() throws Exception {
        ClaudeSavedResets s = ClaudeSavedResets.fromApiJson(new JSONObject("{\"eligible\":true,\"grants\":["
                + grant("g_1", 2, "\"type\":\"full\"") + ","
                + "{\"id\":\"g_2\",\"resets_total\":1,\"resets_left\":1,\"ends_at\":\"2070-01-01T00:00:00Z\",\"usable_now\":false,"
                + "\"unavailable_reason\":\"needs_limit\",\"model\":\"fable\"},"
                + grant("g_3", 1, "\"type\":\"full\"") + "],\"next_grant_id\":\"g_3\"}"), NOW);
        List<ClaudeSavedResets.Choice> choices = s.choices(NOW);
        check(choices.size() == 2, "two kinds: " + choices.size());
        ClaudeSavedResets.Choice full = choices.get(0);
        check(full.title.equals("Full reset") && full.count == 3 && full.usableNow, "full resets together, first");
        check(full.grantId.equals("g_3"), "the server's next grant is the one spent: " + full.grantId);
        check(full.refills.equals("Refills your 5-hour and weekly limits."), full.refills);
        check(full.countedTitle().equals("3 Full resets"), full.countedTitle());
        ClaudeSavedResets.Choice fable = choices.get(1);
        check(fable.title.equals("Fable reset") && !fable.usableNow && fable.held.equals("Works once you hit your Fable limit"),
                "a held Fable reset says why: " + fable.held);
        check(fable.refills.startsWith("Refills only your Fable limit."), fable.refills);
        check(ClaudeSavedResets.choice(choices, fable.key) == fable, "picking a kind");
        check(ClaudeSavedResets.choice(choices, "gone") == full, "an unknown pick falls back to the usable one");
        check(saved().choices(NOW).isEmpty(), "none");
    }
}
