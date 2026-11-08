package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** New-model discovery: allowlist, safeguards, card and notification surfaces, cadence. */
public final class ModelDiscoverySelfTest {
    private static final long NOW = 1_794_000_000_000L;
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long MIN = TimeUnit.MINUTES.toMillis(1);
    private static final ModelDiscovery.Source OR = ModelDiscovery.Source.OPENROUTER;

    public static void main(String[] args) {
        allowlist();
        parsing();
        firstCatalogIsSilent();
        secondSightingConfirms();
        partialFetchChangesNothing();
        notLaunches();
        cardSurface();
        notificationSurface();
        cadence();
        roundTrip();
        System.out.println("Model discovery: allowlist, baselines, safeguards, card, digest and cadence verified.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static ModelDiscovery.Model m(String id) {
        return new ModelDiscovery.Model(id, id.substring(id.indexOf('/') + 1), "", 0L, "");
    }

    private static List<ModelDiscovery.Model> list(String... ids) {
        List<ModelDiscovery.Model> out = new ArrayList<>();
        for (String id : ids) {
            out.add(m(id));
        }
        return out;
    }

    private static List<ModelDiscovery.Scope> allow(UsageProvider... providers) {
        return ModelDiscovery.allowlist(Arrays.asList(providers));
    }

    private static void allowlist() {
        List<ModelDiscovery.Scope> codex = allow(UsageProvider.CODEX);
        check(ModelDiscovery.relevant(codex, OR, "openai/gpt-6"), "Codex unlocks OpenAI");
        check(!ModelDiscovery.relevant(codex, OR, "anthropic/claude-x"), "Codex doesn't unlock Anthropic");
        check(!ModelDiscovery.relevant(codex, OR, "openai-community/gpt"), "author match is exact");
        check(ModelDiscovery.relevant(allow(UsageProvider.CLAUDE_CODE), OR, "anthropic/claude-x"), "Claude");
        check(ModelDiscovery.relevant(allow(UsageProvider.GROK), OR, "x-ai/grok-5"), "Grok uses x-ai");
        check(ModelDiscovery.relevant(allow(UsageProvider.OPENROUTER), OR, "qwen/anything"), "OpenRouter: all");
        check(ModelDiscovery.relevant(allow(UsageProvider.KIMI), OR, "moonshotai/kimi-k3"), "Kimi");
        check(ModelDiscovery.relevant(allow(UsageProvider.ZAI), OR, "z-ai/glm-6"), "Z.ai");
        check(ModelDiscovery.relevant(allow(UsageProvider.MINIMAX), OR, "minimax/m3"), "MiniMax");
        check(!ModelDiscovery.relevant(allow(UsageProvider.KIMI), OR, "z-ai/glm-6"), "own family only");
        List<ModelDiscovery.Scope> go = allow(UsageProvider.OPENCODE_GO);
        check(ModelDiscovery.relevant(go, ModelDiscovery.Source.OPENCODE_GO, "glm-5.3"), "OpenCode Go catalog");
        check(!ModelDiscovery.relevant(go, OR, "openai/gpt-6"), "OpenCode Go doesn't unlock OpenRouter");
        check(ModelDiscovery.sourcesNeeded(allow(UsageProvider.KILO)).equals(EnumSet.of(ModelDiscovery.Source.KILO)),
                "Kilo fetches only its own catalog");
        for (UsageProvider p : new UsageProvider[] {UsageProvider.CURSOR, UsageProvider.ANTIGRAVITY,
                UsageProvider.COPILOT, UsageProvider.FACTORY, UsageProvider.DEVIN, UsageProvider.WINDSURF,
                UsageProvider.AUGMENT, UsageProvider.CLINE, UsageProvider.KIRO, UsageProvider.AMP,
                UsageProvider.CODEBUFF, UsageProvider.WARP}) {
            check(ModelDiscovery.scopesFor(p).isEmpty(), p + " unlocks nothing");
        }
        check(allow(UsageProvider.CURSOR, UsageProvider.ANTIGRAVITY).isEmpty(), "nothing mapped → nothing");
        check(ModelDiscovery.allowlist(Collections.<UsageProvider>emptyList()).isEmpty(), "none connected");
        List<ModelDiscovery.Scope> both = allow(UsageProvider.CODEX, UsageProvider.CLAUDE_CODE, UsageProvider.CODEX);
        check(both.size() == 2, "union without duplicates");
        check(ModelDiscovery.relevant(both, OR, "openai/a") && ModelDiscovery.relevant(both, OR, "anthropic/b"),
                "union covers both");
    }

    private static void parsing() {
        String body = "{\"data\":[{\"id\":\"openai/gpt-6\",\"canonical_slug\":\"openai/gpt-6-20261101\","
                + "\"name\":\"OpenAI: GPT-6\",\"created\":1794000000},{\"id\":\"\"},{\"id\":\"x-ai/grok-5\"}],"
                + "\"total_count\":2,\"links\":{\"next\":\"/api/v1/models?cursor=abc\"}}";
        ModelDiscovery.Page page = ModelDiscovery.parse(OR, body);
        check(page.models.size() == 2, "two models, empty id skipped");
        check(page.models.get(0).name.equals("GPT-6"), "author prefix dropped");
        check(page.models.get(0).canonical.equals("openai/gpt-6-20261101"), "canonical slug kept");
        check(page.total == 2, "total");
        check("https://openrouter.ai/api/v1/models?cursor=abc".equals(ModelDiscovery.nextUrl(OR, page.next)),
                "relative next link resolved on the same host");
        check(ModelDiscovery.nextUrl(OR, "https://evil.example/models") == null, "other hosts refused");
        check(ModelDiscovery.nextUrl(OR, "//evil.example/x") == null, "protocol-relative refused");
        check(ModelDiscovery.parse(OR, "{\"data\":[],\"links\":{\"next\":null}}").next == null, "null next");
        check(ModelDiscovery.parse(OR, "<html>").models == null, "not a catalog");
        check(ModelDiscovery.parse(OR, "{\"error\":1}").models == null, "no data");
        ModelDiscovery.Page syn = ModelDiscovery.parse(ModelDiscovery.Source.SYNTHETIC,
                "{\"data\":[{\"id\":\"syn:large:text\",\"name\":\"syn:large:text\",\"display_name\":\"Large\"}]}");
        check(syn.models.get(0).name.equals("Large"), "display name used when name is the id");
        check(!ModelDiscovery.isVariant(ModelDiscovery.Source.SYNTHETIC, "syn:large:text"), "Synthetic colons");
        check(ModelDiscovery.isVariant(ModelDiscovery.Source.KILO, "nvidia/nemotron:free"), "free variant");
        check(ModelDiscovery.isVariant(OR, "~openai/gpt-latest"), "router alias");
        check(ModelDiscovery.isVariant(OR, "openai/gpt-6-batch"), "batch variant");
        check(ModelDiscovery.parse(OR, "{\"data\":[{\"id\":\"a/b\",\"status\":\"preview\"}]}")
                .models.get(0).stage.equals("preview"), "explicit preview metadata");
        check(ModelDiscovery.parse(OR, "{\"data\":[{\"id\":\"a/b-preview\"}]}").models.get(0).stage.isEmpty(),
                "no preview guessed from the id");
    }

    private static void firstCatalogIsSilent() {
        ModelDiscovery.State s = new ModelDiscovery.State();
        List<ModelDiscovery.Entry> c = ModelDiscovery.observe(s, OR, "public",
                list("openai/gpt-5", "openai/gpt-5.5"), true, NOW);
        check(c.isEmpty(), "first catalog seeds silently");
        ModelDiscovery.observe(s, OR, "public", list("openai/gpt-5", "openai/gpt-5.5"), true, NOW + HOUR);
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CODEX), NOW + HOUR).isEmpty(),
                "seeded models never show");
        // A new source has its own baseline even after OpenRouter's.
        check(ModelDiscovery.observe(s, ModelDiscovery.Source.KILO, "public", list("kilo-auto/free"), true,
                NOW + HOUR).isEmpty(), "Kilo's first catalog is silent too");
        check(s.entries.get("kilo|kilo-auto/free").kind == ModelDiscovery.Kind.BASELINE, "Kilo baseline");
    }

    private static ModelDiscovery.State seeded() {
        ModelDiscovery.State s = new ModelDiscovery.State();
        ModelDiscovery.observe(s, OR, "public", list("openai/gpt-5", "anthropic/claude-5"), true, NOW);
        return s;
    }

    private static void secondSightingConfirms() {
        ModelDiscovery.State s = seeded();
        List<ModelDiscovery.Model> next = list("openai/gpt-5", "anthropic/claude-5", "openai/gpt-6");
        check(ModelDiscovery.observe(s, OR, "public", next, true, NOW + HOUR).isEmpty(), "one sighting waits");
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CODEX), NOW + HOUR).isEmpty(), "pending hidden");
        List<ModelDiscovery.Entry> c = ModelDiscovery.observe(s, OR, "public", next, true, NOW + 2 * HOUR);
        check(c.size() == 1 && c.get(0).id.equals("openai/gpt-6"), "second sighting confirms");
        ModelDiscovery.Entry e = s.entries.get("openrouter|openai/gpt-6");
        check(e.firstSeen == NOW + HOUR && e.lastSeen == NOW + 2 * HOUR, "first and last seen kept");
        check(e.label().equals("New on OpenRouter: gpt-6 — subscription availability not verified"),
                "label doesn't imply entitlement: " + e.label());
        check(ModelDiscovery.observe(s, OR, "public", next, true, NOW + 3 * HOUR).isEmpty(), "confirmed once");
    }

    private static void partialFetchChangesNothing() {
        ModelDiscovery.State s = seeded();
        String before = s.toJson();
        check(ModelDiscovery.observe(s, OR, "public", list("openai/gpt-6"), false, NOW + HOUR).isEmpty(),
                "partial response");
        check(ModelDiscovery.observe(s, OR, "public", null, true, NOW + HOUR).isEmpty(), "failed fetch");
        check(ModelDiscovery.observe(s, OR, "public", new ArrayList<>(), true, NOW + HOUR).isEmpty(),
                "empty response");
        check(s.toJson().equals(before), "a failed or partial fetch leaves the record as it was");
        // A model missing from a complete response is not retired.
        ModelDiscovery.observe(s, OR, "public", list("openai/gpt-5"), true, NOW + HOUR);
        check(s.entries.containsKey("openrouter|anthropic/claude-5"), "missing isn't retired");
        // Coming back is not a launch.
        ModelDiscovery.observe(s, OR, "public", list("openai/gpt-5", "anthropic/claude-5"), true, NOW + 2 * HOUR);
        ModelDiscovery.observe(s, OR, "public", list("openai/gpt-5", "anthropic/claude-5"), true, NOW + 3 * HOUR);
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CLAUDE_CODE), NOW + 3 * HOUR).isEmpty(),
                "a model that reappears isn't new");
    }

    private static void notLaunches() {
        ModelDiscovery.State s = new ModelDiscovery.State();
        ModelDiscovery.observe(s, OR, "public", Collections.singletonList(
                new ModelDiscovery.Model("openai/gpt-5", "GPT-5", "openai/gpt-5-20260801", 0L, "")), true, NOW);
        List<ModelDiscovery.Model> next = new ArrayList<>(Arrays.asList(
                new ModelDiscovery.Model("openai/gpt-5", "GPT-5", "openai/gpt-5-20260801", 0L, ""),
                // Alias rename: same canonical model under another id.
                new ModelDiscovery.Model("openai/gpt-5-chat", "GPT-5 Chat", "openai/gpt-5-20260801", 0L, ""),
                new ModelDiscovery.Model("openai/gpt-5:nitro", "GPT-5 nitro", "", 0L, ""),
                new ModelDiscovery.Model("openai/gpt-5-batch", "GPT-5 batch", "", 0L, ""),
                // Listed now, but the catalog says it has existed for months.
                new ModelDiscovery.Model("openai/gpt-4.9", "GPT-4.9", "", (NOW - 90L * 24 * HOUR) / 1000L, "")));
        ModelDiscovery.observe(s, OR, "public", next, true, NOW + HOUR);
        ModelDiscovery.observe(s, OR, "public", next, true, NOW + 2 * HOUR);
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.OPENROUTER), NOW + 2 * HOUR).isEmpty(),
                "renames, variants and old models are not launches");
        check(s.entries.get("openrouter|openai/gpt-5-chat").kind == ModelDiscovery.Kind.RENAME, "rename");
        check(s.entries.get("openrouter|openai/gpt-4.9").kind == ModelDiscovery.Kind.OLD, "old");
        // A pricing or name edit of a known model changes nothing visible.
        List<ModelDiscovery.Model> renamed = new ArrayList<>(next);
        renamed.set(0, new ModelDiscovery.Model("openai/gpt-5", "GPT-5 (new pricing)", "openai/gpt-5-20260801", 0L, ""));
        check(ModelDiscovery.observe(s, OR, "public", renamed, true, NOW + 3 * HOUR).isEmpty(), "edit");
    }

    private static ModelDiscovery.State withNew() {
        ModelDiscovery.State s = seeded();
        List<ModelDiscovery.Model> next = list("openai/gpt-5", "anthropic/claude-5", "openai/gpt-6",
                "google/gemini-4");
        ModelDiscovery.observe(s, OR, "public", next, true, NOW + HOUR);
        ModelDiscovery.observe(s, OR, "public", next, true, NOW + 2 * HOUR);
        return s;
    }

    private static void cardSurface() {
        ModelDiscovery.State s = withNew();
        long t = NOW + 2 * HOUR;
        List<ModelDiscovery.Entry> codex = ModelDiscovery.cardItems(s, allow(UsageProvider.CODEX), t);
        check(codex.size() == 1 && codex.get(0).id.equals("openai/gpt-6"), "relevant model shown, Google hidden");
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CLAUDE_CODE), t).isEmpty(),
                "Claude alone: nothing relevant, no card");
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CURSOR), t).isEmpty(), "Cursor: no card");
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.OPENROUTER), t).size() == 2, "OpenRouter: both");
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CODEX), t + ModelDiscovery.CARD_DAYS_MS).isEmpty(),
                "leaves the card after a week");
        ModelDiscovery.dismiss(s, codex);
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.CODEX), t).isEmpty(), "dismissed");
        check(ModelDiscovery.cardItems(s, allow(UsageProvider.OPENROUTER), t).size() == 1,
                "dismissing hides only what was shown");
        check(ModelDiscovery.CARD_NOTE.contains("not verified")
                && ModelDiscovery.CARD_NOTE.contains("not necessarily newly released"), "card note");
    }

    private static void notificationSurface() {
        // Turned on later: no backlog.
        ModelDiscovery.State s = withNew();
        ModelDiscovery.markAllNotified(s);
        check(ModelDiscovery.takeDigest(s, allow(UsageProvider.CODEX), NOW + 2 * HOUR).isEmpty(), "no backlog");
        // A model confirmed afterwards notifies once, grouped.
        List<ModelDiscovery.Model> next = list("openai/gpt-5", "anthropic/claude-5", "openai/gpt-6",
                "google/gemini-4", "openai/gpt-6-mini", "openai/o9");
        ModelDiscovery.observe(s, OR, "public", next, true, NOW + 3 * HOUR);
        ModelDiscovery.observe(s, OR, "public", next, true, NOW + 4 * HOUR);
        List<ModelDiscovery.Entry> d = ModelDiscovery.takeDigest(s, allow(UsageProvider.CODEX), NOW + 4 * HOUR);
        check(d.size() == 2, "one digest of two, was " + d.size());
        check(ModelDiscovery.takeDigest(s, allow(UsageProvider.CODEX), NOW + 5 * HOUR).isEmpty(), "deduplicated");
        String text = ModelDiscovery.digestText(d);
        check(text.contains("New on OpenRouter") && text.contains("Subscription availability not verified"),
                "digest wording: " + text);
        // Irrelevant models are marked too, so connecting a provider later replays nothing.
        check(ModelDiscovery.takeDigest(s, allow(UsageProvider.OPENROUTER), NOW + 5 * HOUR).isEmpty(),
                "no replay after connecting OpenRouter");
        List<ModelDiscovery.Entry> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.addAll(ModelDiscovery.cardItems(withNew(), allow(UsageProvider.OPENROUTER), NOW + 2 * HOUR));
        }
        check(ModelDiscovery.digestText(many).contains("and 7 more"), "digest is capped");
    }

    private static void cadence() {
        long day = TimeUnit.DAYS.toMillis(1);
        check(ModelDiscovery.intervalMs(3.0, 14, HOUR) == 30 * MIN, "engaged: 30 minutes");
        check(ModelDiscovery.intervalMs(0.2, 14, HOUR) == 60 * MIN, "otherwise: an hour");
        check(ModelDiscovery.intervalMs(0.2, 3, HOUR) == 120 * MIN, "quiet hours: two hours");
        check(ModelDiscovery.intervalMs(3.0, 3, HOUR) == 30 * MIN, "engaged at night stays 30");
        check(ModelDiscovery.intervalMs(0.0, 14, 3 * day) == 6 * HOUR, "days away: 6 hours");
        check(ModelDiscovery.intervalMs(0.0, 14, 8 * day) == 24 * HOUR, "a week away: a day");
        // 5-minute usage jobs don't make 5-minute catalog checks.
        long success = NOW;
        int checks = 0;
        for (long t = NOW + 5 * MIN; t < NOW + 2 * HOUR; t += 5 * MIN) {
            if (ModelDiscovery.isDue(t, success, success, 0, 60 * MIN, false)) {
                checks++;
                success = t;
            }
        }
        check(checks == 1, "hourly with 5-minute jobs, was " + checks);
        check(!ModelDiscovery.isDue(NOW + 29 * MIN, NOW, NOW, 0, 60 * MIN, true), "open: 30-minute floor");
        check(ModelDiscovery.isDue(NOW + 30 * MIN, NOW, NOW, 0, 60 * MIN, true), "open after 30 minutes");
        check(ModelDiscovery.isDue(NOW, 0L, 0L, 0, 60 * MIN, false), "never checked");
        check(!ModelDiscovery.isDue(NOW + 10 * MIN, 0L, NOW, 1, 60 * MIN, true), "failure backs off");
        check(ModelDiscovery.isDue(NOW + 15 * MIN, 0L, NOW, 1, 60 * MIN, true), "retry after 15 minutes");
        check(!ModelDiscovery.isDue(NOW + 90 * MIN, 0L, NOW, 9, 60 * MIN, true), "backoff caps at 2 hours");
    }

    private static void roundTrip() {
        ModelDiscovery.State s = withNew();
        ModelDiscovery.dismiss(s, ModelDiscovery.cardItems(s, allow(UsageProvider.CODEX), NOW + 2 * HOUR));
        ModelDiscovery.markAllNotified(s);
        ModelDiscovery.State back = ModelDiscovery.State.fromJson(s.toJson());
        check(back.toJson().equals(s.toJson()), "state round-trips");
        check(back.baselines.containsKey("openrouter|public"), "baseline scoped by source and account");
        check(ModelDiscovery.State.fromJson("garbage").entries.isEmpty(), "bad state starts over");
    }
}
