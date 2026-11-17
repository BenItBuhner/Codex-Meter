using System.Text.Json;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>One followed model: its last endpoints answer and a rolling history of featured figures.</summary>
public sealed record ModelSpeedRecord
{
    public string Body { get; init; } = "";
    public DateTimeOffset FetchedAt { get; init; }
    /// <summary>Oldest first: throughput (tokens/s) and first-token latency (ms); null when missing.</summary>
    public List<double?[]> History { get; init; } = [];
}

public sealed record ModelSpeedState
{
    public Dictionary<string, ModelSpeedRecord> Models { get; init; } = [];
    public Dictionary<string, DateTimeOffset> Attempts { get; init; } = [];
    public Dictionary<string, int> Failures { get; init; } = [];
}

/// <summary>
/// Public OpenRouter speed figures for each signed-in provider's newest models (three per maker,
/// from the catalog the new-model check already fetched), with a rolling history, so Priority can
/// warn when a model runs much slower than usual. A port of the Android <c>ModelSpeedStore</c>:
/// requests go to OpenRouter's public model metadata only, without a key, account data or prompts,
/// and nothing shows while figures are normal or too few.
/// </summary>
public sealed class ModelSpeedStore(string? directory, HttpClient http)
{
    public static readonly TimeSpan RefreshInterval = TimeSpan.FromMinutes(45);
    public static readonly TimeSpan MaxBackoff = TimeSpan.FromHours(2);
    public const int Tracked = 3;
    /// <summary>Samples kept per model (about a day and a half at one every 45 minutes).</summary>
    public const int HistoryLength = 48;
    public const int MaxBodyBytes = 4 * 1024 * 1024;

    private ModelSpeedState? state;
    private string? Path => directory is null ? null : System.IO.Path.Combine(directory, "model-speed.json");

    public ModelSpeedState State => state ??= Load();

    private ModelSpeedState Load()
    {
        try
        {
            if (Path is { } path && File.Exists(path))
                return JsonSerializer.Deserialize<ModelSpeedState>(File.ReadAllBytes(path), Storage.Options) ?? new();
        }
        catch (JsonException) { }
        catch (IOException) { }
        return new ModelSpeedState();
    }

    private void Set(ModelSpeedState value)
    {
        state = value;
        if (Path is not { } path) return;
        try
        {
            Directory.CreateDirectory(directory!);
            File.WriteAllBytes(path + ".tmp", JsonSerializer.SerializeToUtf8Bytes(value, Storage.Options));
            File.Move(path + ".tmp", path, overwrite: true);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    /// <summary>The maker's newest models in the discovery catalog, without variants (":free").</summary>
    public static List<SeenModel> TrackedModels(ModelDiscoveryState catalog, UsageProvider provider)
    {
        if (ModelSpeed.AuthorFor(provider) is not { } author) return [];
        return catalog.Models.Values
            .Where(m => m.Id.StartsWith(author + "/", StringComparison.Ordinal) && !ModelDiscovery.IsVariant(m.Id) && m.Created is not null)
            .OrderByDescending(m => m.Created).ThenBy(m => m.Id, StringComparer.Ordinal)
            .Take(Tracked).ToList();
    }

    /// <summary>Significant slowdowns of the provider's followed models right now; empty when normal.</summary>
    public List<ModelSlowdown> Slowdowns(ModelDiscoveryState catalog, UsageProvider provider, DateTimeOffset now)
    {
        var output = new List<ModelSlowdown>();
        foreach (var model in TrackedModels(catalog, provider))
        {
            if (!State.Models.TryGetValue(model.Id, out var record) || now - record.FetchedAt > 2 * RefreshInterval) continue;
            var speed = ModelSpeed.Parse(record.Body, record.FetchedAt);
            // The latest sample is the current one; the baseline is everything before it.
            var earlier = record.History.Take(Math.Max(0, record.History.Count - 1))
                .Select(h => (h.Length > 0 ? h[0] ?? double.NaN : double.NaN, h.Length > 1 ? h[1] ?? double.NaN : double.NaN)).ToList();
            if (ModelSpeed.Slowdown(speed, earlier) is { } slow) output.Add(slow);
        }
        return output;
    }

    /// <summary>Fetches the followed models' figures when due and adds them to their history. True if anything changed.</summary>
    public async Task<bool> RefreshAsync(ModelDiscoveryState catalog, IEnumerable<UsageProvider> providers, DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        var changed = false;
        foreach (var provider in providers.Where(p => ModelSpeed.AuthorFor(p) is not null))
        {
            var last = State.Attempts.GetValueOrDefault(provider.Id);
            var failures = State.Failures.GetValueOrDefault(provider.Id);
            var backoff = TimeSpan.FromMinutes(15 << Math.Min(Math.Max(failures - 1, 0), 4));
            var wait = failures == 0 ? RefreshInterval : backoff > MaxBackoff ? MaxBackoff : backoff;
            if (last != default && now - last < wait && now >= last) continue;
            var models = TrackedModels(catalog, provider);
            Set(State with { Attempts = new Dictionary<string, DateTimeOffset>(State.Attempts) { [provider.Id] = now } });
            var failed = models.Count == 0;
            var fetched = false;
            foreach (var model in models.Where(m => ModelSpeed.IsSafeModelId(m.Id)))
            {
                var body = await GetAsync(ModelSpeed.EndpointsUrl(model.Id), cancellationToken).ConfigureAwait(false);
                if (ModelSpeed.Parse(body, now) is not { } speed)
                {
                    failed = true;
                    continue;
                }
                var featured = speed.Featured();
                var previous = State.Models.GetValueOrDefault(model.Id) ?? new ModelSpeedRecord();
                var history = previous.History.Append([
                    featured is null || double.IsNaN(featured.Throughput) ? null : featured.Throughput,
                    featured is null || double.IsNaN(featured.LatencyMs) ? null : featured.LatencyMs,
                ]).TakeLast(HistoryLength).ToList();
                Set(State with
                {
                    Models = new Dictionary<string, ModelSpeedRecord>(State.Models)
                    {
                        [model.Id] = new ModelSpeedRecord { Body = body!, FetchedAt = now, History = history },
                    },
                });
                fetched = true;
            }
            changed |= fetched;
            Set(State with { Failures = new Dictionary<string, int>(State.Failures) { [provider.Id] = failed && !fetched ? failures + 1 : 0 } });
        }
        return changed;
    }

    /// <summary>An anonymous GET: no key, cookie or account header is ever attached.</summary>
    private async Task<string?> GetAsync(string url, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode || response.RequestMessage?.RequestUri?.Host != "openrouter.ai") return null;
            if (response.Content.Headers.ContentLength > MaxBodyBytes) return null;
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return body.Length > MaxBodyBytes ? null : body;
        }
        catch (HttpRequestException) { return null; }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested) { return null; }
    }
}
