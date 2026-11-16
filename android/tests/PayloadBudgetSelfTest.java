package dev.bennett.codexmeter;

import dev.bennett.codexmeter.wear.WearProvidersState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wire budgets. The GitHub release listing (a real 30-release page, uploader profiles removed) must
 * compact to a small, fixed shape; the phone-to-watch providers item must fit the Data Layer's 100 KB
 * limit at its worst case; and the plan values must match the fixture every platform reads.
 */
public final class PayloadBudgetSelfTest {
    /** Compacted release cache ceiling. Measured 56,348 bytes on the fixture; fails past +10%. */
    private static final int RELEASE_CACHE_CEILING = 62_000;
    /** Wear Data Layer item ceiling (Android rejects items over 100 KB). */
    private static final int WEAR_ITEM_CEILING = 100 * 1024;
    private static final Set<String> RELEASE_KEYS = new HashSet<>(Arrays.asList(
            "tag_name", "name", "body", "draft", "prerelease", "published_at", "html_url", "assets"));
    private static final long NOW = 1_790_000_000_000L;

    public static void main(String[] args) throws Exception {
        String dir = System.getProperty("codex.fixtures");
        releaseCacheBudget(read(dir, "github-releases.json"));
        wearProvidersBudget();
        planPricingParity(new JSONObject(read(dir, "plan-pricing.json")));
        System.out.println("Payload budgets: release cache " + lastReleaseBytes + "/" + RELEASE_CACHE_CEILING
                + " B, Wear item " + lastWearBytes + "/" + WEAR_ITEM_CEILING + " B, plan values match fixture.");

    }

    private static int lastReleaseBytes;
    private static int lastWearBytes;

    private static void releaseCacheBudget(String raw) throws Exception {
        String compact = GitHubReleaseParser.compact(raw);
        int bytes = compact.getBytes(StandardCharsets.UTF_8).length;
        lastReleaseBytes = bytes;
        report("release_cache", bytes, RELEASE_CACHE_CEILING);
        check(bytes <= RELEASE_CACHE_CEILING, "release cache " + bytes + " B exceeds " + RELEASE_CACHE_CEILING);
        JSONArray releases = new JSONArray(compact);
        check(releases.length() == GitHubReleaseParser.MAX_RELEASES, "one page of releases kept");
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            Set<String> keys = new HashSet<>();
            for (Iterator<String> it = release.keys(); it.hasNext(); ) keys.add(it.next());
            check(keys.equals(RELEASE_KEYS), "release shape is fixed: " + keys);
            check(release.getString("body").length() <= 6000, "notes capped");
            JSONArray assets = release.getJSONArray("assets");
            check(assets.length() <= 2, "only the phone APK and SHA256SUMS.txt survive");
        }
        List<GitHubRelease> parsed = GitHubReleaseParser.parse(compact);
        check("2.9.0-alpha.29".equals(parsed.get(0).version), "newest release first");
        GitHubRelease alpha = UpdateChannel.selectUpdate(parsed, "2.9.0-alpha.28", UpdateChannel.ALPHA);
        check(alpha != null && "2.9.0-alpha.29".equals(alpha.version), "alpha channel offers alpha.29");
        check(UpdateChannel.selectUpdate(parsed, "2.9.0-alpha.29", UpdateChannel.ALPHA) == null,
                "up to date on alpha.29");
        GitHubRelease stable = UpdateChannel.selectUpdate(parsed, "2.0.0", UpdateChannel.STABLE);
        check(stable == null || !stable.prerelease, "stable channel never offers a prerelease");
    }

    private static void wearProvidersBudget() throws Exception {
        UsageSnapshot largest = largestSnapshot();
        List<WearProvidersState.Entry> entries = new ArrayList<>();
        for (int p = 0; p < WearProvidersState.MAX_PROVIDERS + 4; p++) {
            entries.add(new WearProvidersState.Entry("provider" + p, "Provider " + p, true,
                    largest, repeat('e', 400)));
        }
        // The selected tab is the last one, so it only keeps its named limits if it is served first.
        String selected = "provider" + (WearProvidersState.MAX_PROVIDERS - 1);
        WearProvidersState state = new WearProvidersState(entries, selected, NOW);
        String wire = state.toJson().toString();
        int bytes = wire.getBytes(StandardCharsets.UTF_8).length;
        lastWearBytes = bytes;
        report("wear_providers_item", bytes, WEAR_ITEM_CEILING);
        check(bytes <= WEAR_ITEM_CEILING, "Wear providers item " + bytes + " B exceeds " + WEAR_ITEM_CEILING);

        WearProvidersState received = WearProvidersState.fromJson(new JSONObject(wire));
        check(received.providers.size() == WearProvidersState.MAX_PROVIDERS, "every provider reaches the watch");
        for (WearProvidersState.Entry entry : received.providers) {
            check(entry.snapshot != null && entry.snapshot.fiveHour != null && entry.snapshot.weekly != null,
                    entry.id + " keeps its main windows");
        }
        int full = largest.additionalLimits.size();
        check(received.find(selected).snapshot.additionalLimits.size() == full,
                "the selected tab keeps all " + full + " named limits");
        check(received.find("provider0").snapshot.additionalLimits.size() > 0, "the first tab keeps some named limits");
        int trimmed = received.find("provider" + (WearProvidersState.MAX_PROVIDERS - 2)).snapshot.additionalLimits.size();
        check(trimmed < full, "named limits are trimmed once the item is full");

        // A normal day (a few providers with a handful of limits each) is never trimmed.
        List<WearProvidersState.Entry> few = new ArrayList<>();
        for (int p = 0; p < 6; p++) {
            few.add(new WearProvidersState.Entry("p" + p, "P" + p, true, largest, ""));
        }
        WearProvidersState small = WearProvidersState.fromJson(
                new JSONObject(new WearProvidersState(few, "p0", NOW).toJson().toString()));
        for (WearProvidersState.Entry entry : small.providers) {
            check(entry.snapshot.additionalLimits.size() == full, entry.id + " is not trimmed when there is room");
        }
    }

    /** A snapshot whose JSON sits just under the per-provider cap, so the worst case is measured. */
    private static UsageSnapshot largestSnapshot() throws Exception {
        List<UsageLimit> limits = new ArrayList<>();
        UsageSnapshot best = null;
        for (int i = 0; i < 200; i++) {
            limits.add(new UsageLimit("limit" + i, "Premium model pool " + i, "", true, false,
                    window(40, TimeUnit.DAYS.toSeconds(7), 600), null));
            UsageSnapshot s = new UsageSnapshot("pro", true, false,
                    window(10, TimeUnit.HOURS.toSeconds(5), 90), window(20, TimeUnit.DAYS.toSeconds(7), 900),
                    null, new ArrayList<>(limits), null, -1, NOW);
            if (s.toJson().toString().length() > 5_900) break;
            best = s;
        }
        return best;
    }

    private static void planPricingParity(JSONObject fixture) {
        JSONObject codex = fixture.getJSONObject("codex");
        for (String plan : codex.keySet()) expect("codex " + plan, PlanPricing.forPlan(plan), codex.get(plan));
        JSONObject claude = fixture.getJSONObject("claude");
        for (String plan : claude.keySet()) expect("claude " + plan, PlanPricing.forClaudePlan(plan), claude.get(plan));
    }

    private static void expect(String label, PlanPricing pricing, Object want) {
        if ("none".equals(want)) {
            check(pricing == null, label + " has no card");
        } else if ("unknown".equals(want)) {
            check(pricing != null && !pricing.hasValue(), label + " shows unknown");
        } else {
            double value = ((Number) want).doubleValue();
            check(pricing != null && Math.abs(pricing.monthlyValueUsd - value) < 0.5,
                    label + " is $" + value + ", got " + (pricing == null ? "no card" : pricing.monthlyValueUsd));
        }
    }

    private static UsageWindow window(int used, long windowSeconds, long resetInMinutes) {
        return new UsageWindow(used, windowSeconds, resetInMinutes * 60L, NOW / 1000L + resetInMinutes * 60L);
    }

    /** Read by .github/scripts/payload.sh for the PR's payload table; printed before the check can fail. */
    private static void report(String name, int bytes, int ceiling) {
        System.out.println("PAYLOAD " + name + " " + bytes + " " + ceiling);
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String read(String dir, String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(dir, name)), StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
