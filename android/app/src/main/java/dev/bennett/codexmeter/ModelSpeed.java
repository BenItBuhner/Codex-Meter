package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Public speed figures for one model, from OpenRouter's endpoint metadata
 * ({@code GET /api/v1/models/{author}/{slug}/endpoints}, no key needed). This is a reference for
 * how a model is running across OpenRouter's providers, not the speed of the user's own Codex,
 * Claude Code or Grok subscription: the app has no request-level timing of its own, sends no test
 * prompts, and never labels these numbers as subscription performance.
 *
 * <p>OpenRouter leaves {@code throughput_last_30m} and {@code latency_last_30m} null when it has
 * too few samples; those are missing (NaN), never zero, and never shown. Only a significant
 * slowdown against the model's own recent history is ever surfaced ({@link #slowdown}). Pure Java
 * so the self-tests cover it.
 */
public final class ModelSpeed {

    /** One OpenRouter endpoint (a provider serving the model). */
    public static final class Endpoint {
        public final String provider;
        public final String tag;
        /** Median tokens per second over the last 30 minutes, or NaN with no data. */
        public final double throughput;
        /** Median time to first token in milliseconds over the last 30 minutes, or NaN. */
        public final double latencyMs;
        /** Share of successful requests over the last 30 minutes, 0 to 100, or NaN. */
        public final double uptime30m;
        public final double uptime1d;

        Endpoint(String provider, String tag, double throughput, double latencyMs, double uptime30m,
                double uptime1d) {
            this.provider = provider;
            this.tag = tag;
            this.throughput = throughput;
            this.latencyMs = latencyMs;
            this.uptime30m = uptime30m;
            this.uptime1d = uptime1d;
        }
    }

    public final String modelId;
    public final String modelName;
    public final List<Endpoint> endpoints;
    public final long fetchedAtMillis;

    public ModelSpeed(String modelId, String modelName, List<Endpoint> endpoints, long fetchedAtMillis) {
        this.modelId = modelId == null ? "" : modelId;
        this.modelName = modelName == null || modelName.isEmpty() ? this.modelId : modelName;
        this.endpoints = endpoints == null ? Collections.<Endpoint>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(endpoints));
        this.fetchedAtMillis = fetchedAtMillis;
    }

    /** Parses an endpoints response; null when it isn't one. */
    public static ModelSpeed parse(String body, long now) {
        try {
            JSONObject data = new JSONObject(body).optJSONObject("data");
            if (data == null) {
                return null;
            }
            JSONArray list = data.optJSONArray("endpoints");
            List<Endpoint> endpoints = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject e = list.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    endpoints.add(new Endpoint(e.optString("provider_name", ""), e.optString("tag", ""),
                            metric(e.opt("throughput_last_30m")), metric(e.opt("latency_last_30m")),
                            number(e.opt("uptime_last_30m")), number(e.opt("uptime_last_1d"))));
                }
            }
            String name = data.optString("name", "");
            int colon = name.indexOf(": ");
            return new ModelSpeed(data.optString("id", ""), colon > 0 ? name.substring(colon + 2) : name,
                    endpoints, now);
        } catch (JSONException | NullPointerException e) {
            return null;
        }
    }

    /**
     * A 30-minute metric: a number, or an object of percentiles (the median, p50, is used). Null,
     * missing or negative is NaN ("insufficient data").
     */
    static double metric(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            return number(o.has("p50") ? o.opt("p50") : o.opt("median"));
        }
        return number(value);
    }

    static double number(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return d >= 0 && !Double.isInfinite(d) ? d : Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * The endpoint whose figures are shown: the model maker's own endpoint (Anthropic for Claude,
     * OpenAI for GPT, xAI for Grok) when it has any data, otherwise the endpoint with the most
     * metrics. Null when no endpoint has any.
     */
    public Endpoint featured() {
        String author = modelId.contains("/") ? modelId.substring(0, modelId.indexOf('/')) : "";
        Endpoint best = null;
        int bestScore = 0;
        for (Endpoint e : endpoints) {
            int score = (Double.isNaN(e.throughput) ? 0 : 2) + (Double.isNaN(e.latencyMs) ? 0 : 2)
                    + (Double.isNaN(e.uptime30m) ? 0 : 1);
            if (score == 0) {
                continue;
            }
            if (isFirstParty(author, e) && (e.tag.indexOf('/') < 0)) {
                score += 10;
            }
            if (score > bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }

    static boolean isFirstParty(String author, Endpoint e) {
        String p = e.provider.toLowerCase(Locale.ROOT);
        switch (author) {
            case "anthropic":
                return p.equals("anthropic");
            case "openai":
                return p.equals("openai");
            case "x-ai":
                return p.equals("xai");
            default:
                return false;
        }
    }

    /** A model running much slower than it usually does (public OpenRouter figures). */
    public static final class Slowdown {
        public final String modelName;
        public final String title;
        public final String detail;
        /** How much worse than usual, for ordering: 2 means twice as slow. */
        public final double factor;

        Slowdown(String modelName, String title, String detail, double factor) {
            this.modelName = modelName;
            this.title = title;
            this.detail = detail;
            this.factor = factor;
        }
    }

    /** Samples needed before "usual" means anything. */
    static final int MIN_BASELINE = 6;
    /** Throughput under half its usual median, or first-token latency over twice its usual. */
    static final double SLOW_FACTOR = 2.0;
    /** Uptime under this over the last 30 minutes counts on its own. */
    static final double UPTIME_FLOOR = 90.0;

    /**
     * A significant slowdown of {@code now} against {@code earlier} samples ({throughput, latency}),
     * or null when the figures are normal, missing or there is too little history to tell.
     */
    public static Slowdown slowdown(ModelSpeed now, List<double[]> earlier) {
        Endpoint e = now == null ? null : now.featured();
        if (e == null) {
            return null;
        }
        String via = " (OpenRouter, via " + e.provider + ", last 30 minutes; public figures, not your subscription)";
        if (!Double.isNaN(e.uptime30m) && e.uptime30m < UPTIME_FLOOR) {
            return new Slowdown(now.modelName, now.modelName + " is failing more often than usual",
                    String.format(Locale.US, "%.0f%% of requests succeeded", e.uptime30m) + via,
                    100.0 / Math.max(1.0, e.uptime30m));
        }
        double usualTps = median(earlier, 0);
        double usualLatency = median(earlier, 1);
        double tpsFactor = Double.isNaN(e.throughput) || Double.isNaN(usualTps) || e.throughput <= 0
                ? 0 : usualTps / e.throughput;
        double latencyFactor = Double.isNaN(e.latencyMs) || Double.isNaN(usualLatency) || usualLatency <= 0
                ? 0 : e.latencyMs / usualLatency;
        if (tpsFactor >= SLOW_FACTOR && tpsFactor >= latencyFactor) {
            return new Slowdown(now.modelName, now.modelName + " is running slower than usual",
                    Math.round(e.throughput) + " tokens/s, usually " + Math.round(usualTps) + via, tpsFactor);
        }
        if (latencyFactor >= SLOW_FACTOR) {
            return new Slowdown(now.modelName, now.modelName + " is slow to start responding",
                    seconds(e.latencyMs) + " to the first token, usually " + seconds(usualLatency) + via,
                    latencyFactor);
        }
        return null;
    }

    /** The median of column {@code k} over samples that have it, or NaN under MIN_BASELINE. */
    static double median(List<double[]> samples, int k) {
        List<Double> v = new ArrayList<>();
        if (samples != null) {
            for (double[] s : samples) {
                if (s != null && s.length > k && !Double.isNaN(s[k]) && s[k] > 0) {
                    v.add(s[k]);
                }
            }
        }
        if (v.size() < MIN_BASELINE) {
            return Double.NaN;
        }
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }

    static String seconds(double ms) {
        return ms < 1000 ? Math.round(ms) + " ms" : String.format(Locale.US, "%.1f s", ms / 1000d);
    }

    /** "via Anthropic" or "via Google Vertex", the endpoint the figures come from. */
    public String sourceLine() {
        Endpoint e = featured();
        return e == null ? "No OpenRouter endpoint has reported figures in the last 30 minutes."
                : "OpenRouter, via " + e.provider + ", last 30 minutes";
    }

    public static final String DISCLAIMER =
            "Public OpenRouter figures for this model, not the speed of your own subscription.";

    // ---- Model list -----------------------------------------------------------------------------

    /** A choice for the reference model picker. */
    public static final class Choice {
        public final String id;
        public final String name;

        public Choice(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * The models by {@code author} ("anthropic", "openai", "x-ai") in OpenRouter's
     * {@code /api/v1/models} list, newest first, without batch variants or "~latest" aliases.
     */
    public static List<Choice> modelsBy(String body, String author, int limit) {
        List<Object[]> found = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(body).optJSONArray("data");
            if (data == null) {
                return Collections.emptyList();
            }
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                String id = m == null ? "" : m.optString("id", "");
                if (!id.startsWith(author + "/") || id.indexOf(':') >= 0) {
                    continue;
                }
                String name = m.optString("name", id);
                int colon = name.indexOf(": ");
                found.add(new Object[] {m.optLong("created", 0L),
                        new Choice(id, colon > 0 ? name.substring(colon + 2) : name)});
            }
        } catch (JSONException | NullPointerException e) {
            return Collections.emptyList();
        }
        Collections.sort(found, (a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        List<Choice> out = new ArrayList<>();
        for (Object[] f : found) {
            if (out.size() >= limit) {
                break;
            }
            out.add((Choice) f[1]);
        }
        return out;
    }

    /** OpenRouter's author slug for a provider whose models it lists, or null. */
    public static String authorFor(UsageProvider provider) {
        if (provider == null) {
            return null;
        }
        switch (provider) {
            case CODEX:
                return "openai";
            case CLAUDE_CODE:
                return "anthropic";
            case GROK:
                return "x-ai";
            default:
                return null;
        }
    }

    /** True for an id shaped like "author/model", safe to put in the endpoints path. */
    public static boolean isSafeModelId(String id) {
        return id != null && id.matches("[a-z0-9-]+/[a-z0-9._-]+");
    }

    public static String endpointsUrl(String modelId) {
        return "https://openrouter.ai/api/v1/models/" + modelId + "/endpoints";
    }

    public static final String MODELS_URL = "https://openrouter.ai/api/v1/models";
}
