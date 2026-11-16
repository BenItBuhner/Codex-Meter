using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Models;

/// <summary>One OpenRouter endpoint (a provider serving the model). Missing figures are NaN, never zero.</summary>
public sealed record SpeedEndpoint(string Provider, string Tag, double Throughput, double LatencyMs, double Uptime30m, double Uptime1d);

/// <summary>A model running much slower than it usually does (public OpenRouter figures).</summary>
public sealed record ModelSlowdown(string ModelName, string Title, string Detail, double Factor);

/// <summary>
/// Public speed figures for one model from OpenRouter's endpoint metadata
/// (<c>GET /api/v1/models/{author}/{slug}/endpoints</c>, no key). A port of the Android
/// <c>ModelSpeed</c>: this is a reference for how a model is running across OpenRouter's providers,
/// not the speed of the user's own subscription, and only a significant slowdown against the
/// model's own recent history is ever shown.
/// </summary>
public sealed record ModelSpeed(string ModelId, string ModelName, IReadOnlyList<SpeedEndpoint> Endpoints, DateTimeOffset FetchedAt)
{
    public const string ModelsUrl = "https://openrouter.ai/api/v1/models";
    public const string Disclaimer = "Public OpenRouter figures for this model, not the speed of your own subscription.";

    /// <summary>Samples needed before "usual" means anything.</summary>
    public const int MinBaseline = 6;
    /// <summary>Throughput under half its usual median, or first-token latency over twice its usual.</summary>
    public const double SlowFactor = 2.0;
    /// <summary>Uptime under this over the last 30 minutes counts on its own.</summary>
    public const double UptimeFloor = 90.0;

    public static string EndpointsUrl(string modelId) => $"https://openrouter.ai/api/v1/models/{modelId}/endpoints";

    private static readonly Regex SafeId = new("^[a-z0-9-]+/[a-z0-9._-]+$", RegexOptions.CultureInvariant);

    /// <summary>True for an id shaped like "author/model", safe to put in the endpoints path.</summary>
    public static bool IsSafeModelId(string? id) => id is not null && SafeId.IsMatch(id);

    /// <summary>OpenRouter's author slug for a provider whose models it lists, or null.</summary>
    public static string? AuthorFor(UsageProvider? provider) => provider?.Id switch
    {
        "codex" => "openai",
        "claude_code" => "anthropic",
        "grok" => "x-ai",
        _ => null,
    };

    /// <summary>"Anthropic: Claude Fable 5" as "Claude Fable 5".</summary>
    public static string ShortName(string name)
    {
        var colon = name.IndexOf(": ", StringComparison.Ordinal);
        return colon > 0 ? name[(colon + 2)..] : name;
    }

    /// <summary>Parses an endpoints response; null when it isn't one.</summary>
    public static ModelSpeed? Parse(string? body, DateTimeOffset now)
    {
        if (Json.Obj(Json.Get(Json.TryParseObject(body), "data")) is not { } data) return null;
        var endpoints = new List<SpeedEndpoint>();
        if (Json.Arr(Json.Get(data, "endpoints")) is { } list)
        {
            foreach (var node in list)
            {
                if (Json.Obj(node) is not { } e) continue;
                endpoints.Add(new SpeedEndpoint(Json.Str(Json.Get(e, "provider_name")), Json.Str(Json.Get(e, "tag")),
                    Metric(Json.Get(e, "throughput_last_30m")), Metric(Json.Get(e, "latency_last_30m")),
                    Number(Json.Get(e, "uptime_last_30m")), Number(Json.Get(e, "uptime_last_1d"))));
            }
        }
        var id = Json.Str(Json.Get(data, "id"));
        var name = ShortName(Json.Str(Json.Get(data, "name")));
        return new ModelSpeed(id, name.Length == 0 ? id : name, endpoints, now);
    }

    /// <summary>A 30-minute metric: a number, or percentiles (the median, p50, is used). Null or negative is NaN.</summary>
    public static double Metric(JsonNode? value) =>
        value is JsonObject o ? Number(Json.Get(o, "p50") ?? Json.Get(o, "median")) : Number(value);

    public static double Number(JsonNode? value) =>
        value is JsonValue && Json.Finite(value) is { } d && d >= 0 ? d : double.NaN;

    /// <summary>
    /// The endpoint whose figures are shown: the model maker's own endpoint when it has any data,
    /// otherwise the endpoint with the most metrics. Null when no endpoint has any.
    /// </summary>
    public SpeedEndpoint? Featured()
    {
        var slash = ModelId.IndexOf('/');
        var author = slash > 0 ? ModelId[..slash] : "";
        SpeedEndpoint? best = null;
        var bestScore = 0;
        foreach (var e in Endpoints)
        {
            var score = (double.IsNaN(e.Throughput) ? 0 : 2) + (double.IsNaN(e.LatencyMs) ? 0 : 2) + (double.IsNaN(e.Uptime30m) ? 0 : 1);
            if (score == 0) continue;
            if (IsFirstParty(author, e) && !e.Tag.Contains('/')) score += 10;
            if (score > bestScore)
            {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }

    private static bool IsFirstParty(string author, SpeedEndpoint e) => author switch
    {
        "anthropic" => e.Provider.Equals("anthropic", StringComparison.OrdinalIgnoreCase),
        "openai" => e.Provider.Equals("openai", StringComparison.OrdinalIgnoreCase),
        "x-ai" => e.Provider.Equals("xai", StringComparison.OrdinalIgnoreCase),
        _ => false,
    };

    /// <summary>
    /// A significant slowdown of <paramref name="now"/> against earlier samples (throughput, latency),
    /// or null when the figures are normal, missing or there is too little history to tell.
    /// </summary>
    public static ModelSlowdown? Slowdown(ModelSpeed? now, IReadOnlyList<(double Throughput, double LatencyMs)> earlier)
    {
        if (now?.Featured() is not { } e) return null;
        var via = $" (OpenRouter, via {e.Provider}, last 30 minutes; public figures, not your subscription)";
        if (!double.IsNaN(e.Uptime30m) && e.Uptime30m < UptimeFloor)
            return new ModelSlowdown(now.ModelName, now.ModelName + " is failing more often than usual",
                string.Create(CultureInfo.InvariantCulture, $"{e.Uptime30m:0}% of requests succeeded") + via, 100.0 / Math.Max(1.0, e.Uptime30m));
        var usualTps = Median(earlier.Select(s => s.Throughput));
        var usualLatency = Median(earlier.Select(s => s.LatencyMs));
        var tpsFactor = double.IsNaN(e.Throughput) || double.IsNaN(usualTps) || e.Throughput <= 0 ? 0 : usualTps / e.Throughput;
        var latencyFactor = double.IsNaN(e.LatencyMs) || double.IsNaN(usualLatency) || usualLatency <= 0 ? 0 : e.LatencyMs / usualLatency;
        if (tpsFactor >= SlowFactor && tpsFactor >= latencyFactor)
            return new ModelSlowdown(now.ModelName, now.ModelName + " is running slower than usual",
                $"{Math.Round(e.Throughput)} tokens/s, usually {Math.Round(usualTps)}" + via, tpsFactor);
        if (latencyFactor >= SlowFactor)
            return new ModelSlowdown(now.ModelName, now.ModelName + " is slow to start responding",
                $"{Seconds(e.LatencyMs)} to the first token, usually {Seconds(usualLatency)}" + via, latencyFactor);
        return null;
    }

    /// <summary>The median of the positive values, or NaN with fewer than <see cref="MinBaseline"/>.</summary>
    public static double Median(IEnumerable<double> samples)
    {
        var values = samples.Where(v => !double.IsNaN(v) && v > 0).Order().ToList();
        if (values.Count < MinBaseline) return double.NaN;
        var n = values.Count;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2;
    }

    public static string Seconds(double ms) =>
        ms < 1000 ? $"{Math.Round(ms)} ms" : (ms / 1000d).ToString("0.0", CultureInfo.InvariantCulture) + " s";
}
