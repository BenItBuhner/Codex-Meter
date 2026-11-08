package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** OpenRouter endpoint metadata: null metrics stay missing, first-party preferred, slowdowns only when significant. */
public final class ModelSpeedSelfTest {
    private static final long NOW = 1_794_000_000_000L;

    public static void main(String[] args) throws Exception {
        realResponseWithNullMetrics();
        percentileObjectsAndNumbers();
        firstPartyEndpointPreferred();
        nothingReportedIsInsufficient();
        slowdowns();
        garbage();
        modelList();
        safeIds();
        System.out.println("Model speed: OpenRouter endpoints, missing data, slowdowns, model list and ids verified.");
    }

    private static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(System.getProperty("codex.fixtures", "tests/fixtures"),
                "speed", name)), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void realResponseWithNullMetrics() throws Exception {
        ModelSpeed s = ModelSpeed.parse(fixture("sonnet-endpoints.json"), NOW);
        check(s != null && s.modelId.equals("anthropic/claude-sonnet-5.5"), "parsed id");
        check(s.modelName.equals("Claude Sonnet 5.5"), "name without author prefix: " + s.modelName);
        check(s.endpoints.size() == 10, "ten endpoints, was " + s.endpoints.size());
        ModelSpeed.Endpoint e = s.featured();
        check(e != null && e.provider.equals("Anthropic"), "Anthropic's own endpoint featured: " + (e == null ? null : e.provider));
        check(Double.isNaN(e.throughput) && Double.isNaN(e.latencyMs), "null metrics are NaN, not zero");
        check(ModelSpeed.slowdown(s, java.util.Collections.<double[]>emptyList()) == null,
                "no figures, no warning (never \"insufficient data\")");
        check(s.sourceLine().equals("OpenRouter, via Anthropic, last 30 minutes"), s.sourceLine());
    }

    private static String endpoints(String... items) {
        return "{\"data\":{\"id\":\"openai/gpt-6-luna\",\"name\":\"OpenAI: GPT-6 Luna\",\"endpoints\":["
                + String.join(",", items) + "]}}";
    }

    private static String endpoint(String provider, String tag, String tps, String latency, String uptime) {
        return "{\"provider_name\":\"" + provider + "\",\"tag\":\"" + tag + "\",\"throughput_last_30m\":" + tps
                + ",\"latency_last_30m\":" + latency + ",\"uptime_last_30m\":" + uptime + "}";
    }

    private static void percentileObjectsAndNumbers() {
        ModelSpeed s = ModelSpeed.parse(endpoints(
                endpoint("OpenAI", "openai", "{\"p50\":71.6,\"p90\":40}", "{\"p50\":820,\"p90\":2000}", "99.4")), NOW);
        ModelSpeed.Endpoint e = s.featured();
        check(e.throughput == 71.6 && e.latencyMs == 820, "p50 used");
        check(ModelSpeed.seconds(e.latencyMs).equals("820 ms"), "ms");
        check(ModelSpeed.seconds(1530).equals("1.5 s"), "seconds");
        ModelSpeed n = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "55", "-1", "null")), NOW);
        check(n.featured().throughput == 55 && Double.isNaN(n.featured().latencyMs), "plain number; negative is no data");
    }

    private static void firstPartyEndpointPreferred() {
        ModelSpeed s = ModelSpeed.parse(endpoints(
                endpoint("Azure", "azure", "120", "400", "100"),
                endpoint("OpenAI", "openai/flex", "20", "900", "100"),
                endpoint("OpenAI", "openai", "60", "700", "100")), NOW);
        check(s.featured().tag.equals("openai"), "the maker's main endpoint, not a faster third party or a tier");
        ModelSpeed t = ModelSpeed.parse(endpoints(
                endpoint("Azure", "azure", "120", "400", "100"),
                endpoint("OpenAI", "openai", "null", "null", "null")), NOW);
        check(t.featured().provider.equals("Azure"), "falls back to an endpoint with data");
    }

    private static void nothingReportedIsInsufficient() {
        ModelSpeed s = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "null", "null", "null")), NOW);
        check(s.featured() == null, "no featured endpoint");
        check(s.sourceLine().startsWith("No OpenRouter endpoint"), s.sourceLine());
    }

    private static java.util.List<double[]> usual(double tps, double latency, int n) {
        java.util.List<double[]> h = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            h.add(new double[] {tps + (i % 3) - 1, latency + 10 * (i % 2)});
        }
        return h;
    }

    private static void slowdowns() {
        ModelSpeed normal = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "70", "800", "99.8")), NOW);
        check(ModelSpeed.slowdown(normal, usual(72, 790, 10)) == null, "normal: nothing");
        ModelSpeed slow = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "30", "820", "99.8")), NOW);
        ModelSpeed.Slowdown d = ModelSpeed.slowdown(slow, usual(72, 790, 10));
        check(d != null && d.title.equals("GPT-6 Luna is running slower than usual"), "half speed warns: " + (d == null ? null : d.title));
        check(d.detail.startsWith("30 tokens/s, usually 72"), d.detail);
        check(ModelSpeed.slowdown(slow, usual(72, 790, 5)) == null, "too little history: nothing");
        ModelSpeed mild = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "45", "900", "99.8")), NOW);
        check(ModelSpeed.slowdown(mild, usual(72, 790, 10)) == null, "a mild dip is not significant");
        ModelSpeed late = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "70", "2400", "99.8")), NOW);
        ModelSpeed.Slowdown l = ModelSpeed.slowdown(late, usual(72, 790, 10));
        check(l != null && l.title.contains("slow to start"), "slow first token warns");
        ModelSpeed failing = ModelSpeed.parse(endpoints(endpoint("OpenAI", "openai", "70", "800", "80")), NOW);
        check(ModelSpeed.slowdown(failing, java.util.Collections.<double[]>emptyList()) != null, "low uptime warns without history");
    }

    private static void garbage() {
        check(ModelSpeed.parse("<html>", NOW) == null, "html");
        check(ModelSpeed.parse("{\"error\":{\"code\":404}}", NOW) == null, "error body");
        check(ModelSpeed.modelsBy("nope", "openai", 5).isEmpty(), "bad list");
    }

    private static void modelList() throws Exception {
        List<ModelSpeed.Choice> anthropic = ModelSpeed.modelsBy(fixture("models.json"), "anthropic", 30);
        check(!anthropic.isEmpty(), "anthropic models listed");
        for (ModelSpeed.Choice c : anthropic) {
            check(c.id.startsWith("anthropic/") && !c.id.contains(":"), "no batch variants or aliases: " + c.id);
            check(!c.name.startsWith("Anthropic: "), "names without prefix: " + c.name);
        }
        check(anthropic.get(0).id.equals("anthropic/claude-haiku-5.5"), "newest first: " + anthropic.get(0).id);
        check(ModelSpeed.modelsBy(fixture("models.json"), "openai", 3).size() == 3, "limit");
        check(ModelSpeed.authorFor(UsageProvider.CLAUDE_CODE).equals("anthropic"), "Claude Code → anthropic");
        check(ModelSpeed.authorFor(UsageProvider.CURSOR) == null, "Cursor has no single maker; no card");
    }

    private static void safeIds() {
        check(ModelSpeed.isSafeModelId("anthropic/claude-sonnet-5.5"), "normal id");
        check(!ModelSpeed.isSafeModelId("../../etc"), "path traversal");
        check(!ModelSpeed.isSafeModelId("openai/gpt?x=1"), "query");
        check(!ModelSpeed.isSafeModelId(""), "empty");
        check(ModelSpeed.endpointsUrl("x-ai/grok-4.7").equals(
                "https://openrouter.ai/api/v1/models/x-ai/grok-4.7/endpoints"), "url");
    }
}
