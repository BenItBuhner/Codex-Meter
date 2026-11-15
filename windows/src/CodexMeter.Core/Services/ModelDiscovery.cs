using System.Text.Json;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>One model the discovery has seen in OpenRouter's public catalog.</summary>
public sealed record SeenModel
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public DateTimeOffset FirstSeen { get; init; }
    /// <summary>Complete catalog responses it has appeared in (counting stops at 2).</summary>
    public int Sightings { get; init; }
    /// <summary>When it counted as new: seen twice, recently listed, not a variant. Null for baseline or old models.</summary>
    public DateTimeOffset? ConfirmedAt { get; init; }
}

public sealed record ModelDiscoveryState
{
    public bool Baseline { get; init; }
    public DateTimeOffset? LastFetch { get; init; }
    public Dictionary<string, SeenModel> Models { get; init; } = [];
}

/// <summary>
/// "New models" for the desktop Priority page and notification: a port of the Android
/// <c>ModelDiscovery</c> limited to OpenRouter's public catalog (no key). A model shows only when
/// an explicit allowlist ties its maker to a provider the user is signed in to (Codex → OpenAI,
/// Claude Code → Anthropic, ...), and each row says which one, as changed for the tester report
/// (PR #227). Tools that offer many makers' models, like Cursor or Copilot, add none.
/// Safeguards as on Android: the first complete catalog is a silent baseline, a model must
/// appear in two complete responses, variants (":free", ":beta") and long-listed models are
/// recorded but not announced, and a failed or partial fetch changes nothing.
/// </summary>
public static class ModelDiscovery
{
    public const string CatalogUrl = "https://openrouter.ai/api/v1/models";
    public const int MaxCatalogBytes = 12 * 1024 * 1024;
    /// <summary>A response with fewer models than this is treated as partial and ignored.</summary>
    public const int MinCompleteCatalog = 50;
    public static readonly TimeSpan FetchInterval = TimeSpan.FromHours(12);
    public static readonly TimeSpan RecentlyListed = TimeSpan.FromDays(30);
    public static readonly TimeSpan ShowFor = TimeSpan.FromDays(14);

    public const string CardNote = "Newly listed in a public catalog, not necessarily newly released. Subscription availability not verified.";

    /// <summary>OpenRouter maker slug for a provider the user signs in to; "*" for the whole catalog, null for none.</summary>
    public static string? MakerFor(UsageProvider provider) => provider.Id switch
    {
        "codex" => "openai",
        "claude_code" => "anthropic",
        "grok" => "x-ai",
        "antigravity" => "google",
        "minimax" => "minimax",
        "kimi" => "moonshotai",
        "zai" => "z-ai",
        "openrouter" => "*",
        _ => null,
    };

    public static (string Id, string Name, DateTimeOffset? Created)[]? ParseCatalog(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            if (!document.RootElement.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Array) return null;
            var list = new List<(string, string, DateTimeOffset?)>();
            foreach (var model in data.EnumerateArray())
            {
                if (model.ValueKind != JsonValueKind.Object) continue;
                var id = model.TryGetProperty("id", out var i) && i.ValueKind == JsonValueKind.String ? i.GetString() ?? "" : "";
                if (id.Length == 0 || id.Length > 200) continue;
                var name = model.TryGetProperty("name", out var n) && n.ValueKind == JsonValueKind.String ? n.GetString() ?? id : id;
                DateTimeOffset? created = model.TryGetProperty("created", out var c) && c.TryGetInt64(out var unix) && unix > 0
                    ? DateTimeOffset.FromUnixTimeSeconds(unix) : null;
                list.Add((id, name.Length > 160 ? name[..160] : name, created));
            }
            return [.. list];
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Folds one catalog response into the state; returns it and the models confirmed as new by it.</summary>
    public static (ModelDiscoveryState State, List<SeenModel> Confirmed) Observe(ModelDiscoveryState state,
        IReadOnlyList<(string Id, string Name, DateTimeOffset? Created)>? catalog, DateTimeOffset now)
    {
        if (catalog is null || catalog.Count < MinCompleteCatalog) return (state, []);
        var models = new Dictionary<string, SeenModel>(state.Models);
        var confirmed = new List<SeenModel>();
        foreach (var (id, name, created) in catalog)
        {
            if (!state.Baseline)
            {
                models[id] = new SeenModel { Id = id, Name = name, FirstSeen = now, Sightings = 2 };
                continue;
            }
            if (!models.TryGetValue(id, out var seen))
            {
                models[id] = new SeenModel { Id = id, Name = name, FirstSeen = now, Sightings = 1 };
                continue;
            }
            if (seen.Sightings >= 2) continue;
            var announce = !IsVariant(id) && created is { } at && now - at <= RecentlyListed;
            var updated = seen with { Name = name, Sightings = 2, ConfirmedAt = announce ? now : null };
            models[id] = updated;
            if (announce) confirmed.Add(updated);
        }
        return (state with { Baseline = true, LastFetch = now, Models = models }, confirmed);
    }

    /// <summary>":free", ":beta", ":extended" and other suffixed variants of a model already listed.</summary>
    public static bool IsVariant(string id) => id.Contains(':');

    /// <summary>The provider a model is shown for, preferring the maker's own subscription over a whole-catalog one.</summary>
    public static UsageProvider? MatchedBy(string modelId, IEnumerable<UsageProvider> signedIn)
    {
        var slash = modelId.IndexOf('/');
        var maker = slash > 0 ? modelId[..slash] : "";
        UsageProvider? catalog = null;
        foreach (var provider in signedIn)
        {
            var scope = MakerFor(provider);
            if (scope == maker) return provider;
            if (scope == "*") catalog ??= provider;
        }
        return catalog;
    }

    /// <summary>"Anthropic model, for your Claude Code" or "for your OpenRouter".</summary>
    public static string Reason(UsageProvider provider) =>
        MakerFor(provider) == "*" ? "for your " + provider.DisplayName : $"{provider.VendorName} model, for your {provider.DisplayName}";

    /// <summary>Recently confirmed models for the user's providers, newest first, as Priority rows.</summary>
    public static List<PriorityItem> Items(ModelDiscoveryState state, IReadOnlyList<UsageProvider> signedIn, DateTimeOffset now)
    {
        var items = new List<PriorityItem>();
        foreach (var model in state.Models.Values.Where(m => m.ConfirmedAt is { } at && now - at <= ShowFor)
                     .OrderByDescending(m => m.ConfirmedAt))
        {
            if (MatchedBy(model.Id, signedIn) is not { } provider) continue;
            items.Add(new PriorityItem(provider, "New on OpenRouter: " + model.Name, Reason(provider), null, model.ConfirmedAt));
        }
        return items;
    }

    /// <summary>The note under the list, naming the providers it is shown for.</summary>
    public static string Note(IEnumerable<PriorityItem> items)
    {
        var names = items.Select(i => i.Provider.DisplayName).Distinct().ToList();
        if (names.Count == 0) return CardNote;
        var shownFor = names.Count == 1 ? names[0] : string.Join(", ", names.Take(names.Count - 1)) + " and " + names[^1];
        return $"Shown for {shownFor}. Tools that offer many makers' models, like Cursor or Copilot, add none. " + CardNote;
    }
}

/// <summary>Fetches the catalog at most every 12 hours and keeps the discovery state in models.json.</summary>
public sealed class ModelDiscoveryStore(string? directory, HttpClient http)
{
    private ModelDiscoveryState? state;
    private string? Path => directory is null ? null : System.IO.Path.Combine(directory, "models.json");

    public ModelDiscoveryState State => state ??= Load();

    private ModelDiscoveryState Load()
    {
        try
        {
            if (Path is { } path && File.Exists(path))
                return JsonSerializer.Deserialize<ModelDiscoveryState>(File.ReadAllBytes(path), Storage.Options) ?? new();
        }
        catch (JsonException) { }
        catch (IOException) { }
        return new ModelDiscoveryState();
    }

    public void Set(ModelDiscoveryState value)
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
    }

    /// <summary>Checks the catalog when due; returns models newly confirmed by this check.</summary>
    public async Task<List<SeenModel>> RefreshAsync(DateTimeOffset now, CancellationToken cancellationToken = default)
    {
        if (State.LastFetch is { } last && now - last < ModelDiscovery.FetchInterval && now >= last) return [];
        // Counted as an attempt even if it fails, so a dead network isn't retried every minute.
        Set(State with { LastFetch = now });
        try
        {
            using var response = await http.GetAsync(ModelDiscovery.CatalogUrl, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode || response.RequestMessage?.RequestUri?.Host != "openrouter.ai") return [];
            if (response.Content.Headers.ContentLength > ModelDiscovery.MaxCatalogBytes) return [];
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (body.Length > ModelDiscovery.MaxCatalogBytes) return [];
            var (next, confirmed) = ModelDiscovery.Observe(State, ModelDiscovery.ParseCatalog(body), now);
            Set(next with { LastFetch = now });
            return confirmed;
        }
        catch (HttpRequestException) { return []; }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested) { return []; }
    }
}
