package dev.bennett.codexmeter;

import dev.bennett.codexmeter.wear.WearProvidersState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Self-tests for the multi-provider watch payload and the meters watch surfaces pick for
 * providers that are not shaped like Codex.
 */
public final class WatchProvidersSelfTest {
    private static final long NOW = 1_790_000_000_000L;

    public static void main(String[] args) throws Exception {
        testCodexMetersUnchanged();
        testOtherShapes();
        testNextReset();
        testProvidersRoundTrip();
        testResolveOrder();
        testSignedOutDropsUsage();
        System.out.println("Watch provider payload and per-provider watch meters verified.");
    }

    private static void testCodexMetersUnchanged() {
        UsageSnapshot codex = new UsageSnapshot("pro", true, false,
                window(38, TimeUnit.HOURS.toSeconds(5), 90),
                window(59, TimeUnit.DAYS.toSeconds(7), TimeUnit.DAYS.toMinutes(3)), NOW);
        WatchMeters.Meter first = WatchMeters.primary(codex, NOW);
        WatchMeters.Meter second = WatchMeters.secondary(codex, NOW);
        check("5h".equals(first.shortLabel) && "5-hour".equals(first.label),
                "Codex first meter stays 5-hour");
        check("Week".equals(second.shortLabel) && "Weekly".equals(second.label),
                "Codex second meter stays weekly");
        check(!first.longCadence() && second.longCadence(), "dial glyphs follow cadence");
        check("62·41".equals(WatchMeters.pairShortText(codex, NOW)),
                "short pair keeps the old 62·41 complication text");
        check("5h 62% · Week 41%".equals(WatchMeters.pairLongText(codex, NOW)),
                "long pair names both windows");
        check(41 == WatchMeters.constrainedRemaining(codex, NOW), "ranged value is the tighter");
    }

    private static void testOtherShapes() {
        UsageSnapshot daily = new UsageSnapshot("", true, false,
                window(10, TimeUnit.HOURS.toSeconds(24), 300), null,
                window(50, TimeUnit.DAYS.toSeconds(30), TimeUnit.DAYS.toMinutes(9)),
                Collections.<UsageLimit>emptyList(), null, -1, NOW);
        WatchMeters.Meter first = WatchMeters.primary(daily, NOW);
        WatchMeters.Meter second = WatchMeters.secondary(daily, NOW);
        check("Day".equals(first.shortLabel) && "Daily".equals(first.label),
                "a 24-hour window is labelled Day, not 5h");
        check("Month".equals(second.shortLabel), "monthly window fills the second slot");

        List<UsageLimit> limits = new ArrayList<>();
        limits.add(new UsageLimit("credits", "Credits", "", true, false,
                window(25, 0L, 0L), null));
        limits.add(new UsageLimit("premium", "Premium requests", "", true, false,
                window(80, TimeUnit.DAYS.toSeconds(30), TimeUnit.DAYS.toMinutes(4)), null));
        UsageSnapshot named = new UsageSnapshot("", true, false, null, null, null, limits,
                null, -1, NOW);
        check("Credits".equals(WatchMeters.primary(named, NOW).label),
                "named limits are used when no windows exist");
        check("Premium req…".equals(WatchMeters.secondary(named, NOW).shortLabel),
                "long limit names are clipped for watch slots");
        check(WatchMeters.secondary(named, NOW).longCadence(), "30-day limit uses calendar");

        UsageSnapshot hourly = new UsageSnapshot("", true, false,
                window(5, TimeUnit.HOURS.toSeconds(1), 20), null, NOW);
        check("1h".equals(WatchMeters.primary(hourly, NOW).shortLabel), "1-hour label");
        check(WatchMeters.secondary(hourly, NOW) == null, "single meter has no second");
        check("1h 95%".equals(WatchMeters.pairLongText(hourly, NOW)), "single long text");
        check("95".equals(WatchMeters.pairShortText(hourly, NOW)), "single short text");
        check(WatchMeters.primary(null, NOW) == null, "no snapshot, no meter");
        check("No usage yet".equals(WatchMeters.pairLongText(null, NOW)), "empty long text");
    }

    private static void testNextReset() {
        List<UsageLimit> limits = new ArrayList<>();
        limits.add(new UsageLimit("pool", "Pool", "", true, false,
                window(10, TimeUnit.HOURS.toSeconds(2), 30), null));
        UsageSnapshot snapshot = new UsageSnapshot("", true, false, null,
                window(40, TimeUnit.DAYS.toSeconds(7), TimeUnit.DAYS.toMinutes(2)), null,
                limits, null, -1, NOW);
        check("Pool".equals(WatchMeters.nextReset(snapshot, NOW).label),
                "next reset considers named limits too");
        UsageSnapshot expired = new UsageSnapshot("", true, false,
                window(10, TimeUnit.HOURS.toSeconds(5), -5), null, NOW);
        check(WatchMeters.primary(expired, NOW) == null, "windows past their reset drop out");
    }

    private static void testProvidersRoundTrip() throws Exception {
        List<WearProvidersState.Entry> entries = new ArrayList<>();
        entries.add(new WearProvidersState.Entry("codex", "Codex", true,
                new UsageSnapshot("plus", true, false,
                        window(20, TimeUnit.HOURS.toSeconds(5), 60), null, NOW), ""));
        entries.add(new WearProvidersState.Entry("Claude_Code", "Claude Code", true, null,
                "Rate limited"));
        entries.add(new WearProvidersState.Entry("claude_code", "Duplicate", true, null, ""));
        WearProvidersState state = new WearProvidersState(entries, "claude_code", NOW);
        check(state.providers.size() == 2, "duplicate ids are dropped");
        WearProvidersState back = WearProvidersState.fromJson(
                new JSONObject(state.toJson().toString()));
        check(back.providers.size() == 2 && back.updatedAtMillis == NOW, "round trip keeps list");
        check("claude_code".equals(back.providers.get(1).id), "ids are normalized");
        check("Rate limited".equals(back.find("claude_code").lastError), "errors travel");
        check(back.find("codex").snapshot.fiveHour.usedPercent == 20, "usage travels");
        check(!state.toJson().toString().contains("token"), "no credentials in the payload");
        check(WearProvidersState.fromJson(null) == null, "null json");
    }

    private static void testResolveOrder() {
        List<WearProvidersState.Entry> entries = new ArrayList<>();
        entries.add(new WearProvidersState.Entry("codex", "Codex", false, null, ""));
        entries.add(new WearProvidersState.Entry("cursor", "Cursor", true, null, ""));
        entries.add(new WearProvidersState.Entry("copilot", "Copilot", true, null, ""));
        WearProvidersState state = new WearProvidersState(entries, "copilot", NOW);
        check("cursor".equals(state.resolve("cursor").id), "the watch's own pick wins");
        check("copilot".equals(state.resolve("gone").id), "then the phone's selected tab");
        WearProvidersState noPhonePick = new WearProvidersState(entries, "", NOW);
        check("cursor".equals(noPhonePick.resolve("").id), "then the first signed-in provider");
        check(new WearProvidersState(null, "", NOW).resolve("codex") == null, "empty list");
    }

    private static void testSignedOutDropsUsage() throws Exception {
        WearProvidersState.Entry entry = new WearProvidersState.Entry("kimi", "", false,
                new UsageSnapshot("", true, false, window(1, 3600, 10), null, NOW), "");
        check(entry.snapshot == null, "signed-out providers carry no usage");
        check("kimi".equals(entry.displayName), "blank names fall back to the id");
    }

    private static UsageWindow window(int used, long windowSeconds, long resetInMinutes) {
        long resetAt = NOW / 1000L + resetInMinutes * 60L;
        return new UsageWindow(used, windowSeconds, Math.max(0L, resetInMinutes * 60L),
                resetInMinutes == 0L ? 0L : resetAt);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("Failed: " + label);
    }
}
