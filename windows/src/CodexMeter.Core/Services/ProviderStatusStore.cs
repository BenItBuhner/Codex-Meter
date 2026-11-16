using System.Text.Json;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>Polling state for one feed URL (several providers can share one).</summary>
public sealed record StatusFeedState
{
    public DateTimeOffset? LastAttempt { get; init; }
    public int Failures { get; init; }
    public DateTimeOffset? RetryAfterUntil { get; init; }
    /// <summary>A random number in [0, 1) picked per feed, so desktops don't hit a feed together.</summary>
    public double Jitter { get; init; }
}

public sealed record ProviderStatusState
{
    public Dictionary<string, StatusFeedState> Feeds { get; init; } = [];
    public Dictionary<string, ProviderStatus> Statuses { get; init; } = [];
}

/// <summary>
/// Reads each signed-in provider's official status feed when the poll policy allows and keeps the
/// result in status.json. Only first-party feeds on <see cref="ProviderStatusSources.Hosts"/> are
/// asked, with no account data. A failed fetch keeps the last status until it is older than the
/// longest backoff, then reads "unknown", never "down".
/// </summary>
public sealed class ProviderStatusStore(string? directory, HttpClient http)
{
    public const int MaxFeedBytes = 2 * 1024 * 1024;
    private ProviderStatusState? state;
    private string? Path => directory is null ? null : System.IO.Path.Combine(directory, "status.json");

    public ProviderStatusState State => state ??= Load();

    /// <summary>The provider's last status, or null when it has none or it is too old to trust.</summary>
    public ProviderStatus? For(UsageProvider provider, DateTimeOffset now) =>
        State.Statuses.TryGetValue(provider.Id, out var status) && now - status.CheckedAt <= StatusPollPolicy.MaxBackoff ? status : null;

    private ProviderStatusState Load()
    {
        try
        {
            if (Path is { } path && File.Exists(path))
                return JsonSerializer.Deserialize<ProviderStatusState>(File.ReadAllBytes(path), Storage.Options) ?? new();
        }
        catch (JsonException) { }
        catch (IOException) { }
        return new ProviderStatusState();
    }

    private void Set(ProviderStatusState value)
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

    /// <summary>Forgets a provider's status (after signing out).</summary>
    public void Forget(UsageProvider provider)
    {
        if (!State.Statuses.ContainsKey(provider.Id)) return;
        var statuses = new Dictionary<string, ProviderStatus>(State.Statuses);
        statuses.Remove(provider.Id);
        Set(State with { Statuses = statuses });
    }

    /// <summary>
    /// Asks each due feed once for <paramref name="providers"/>. Returns true when a shown status
    /// changed (its state, headline or detail).
    /// </summary>
    public async Task<bool> RefreshAsync(IEnumerable<UsageProvider> providers, bool foreground, DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        var changed = false;
        var byFeed = providers.Select(p => (Provider: p, Source: ProviderStatusSources.For(p)))
            .Where(p => p.Source is not null).GroupBy(p => p.Source!.FeedUrl);
        foreach (var group in byFeed)
        {
            var url = group.Key;
            var feed = State.Feeds.GetValueOrDefault(url) ?? new StatusFeedState { Jitter = Random.Shared.NextDouble() };
            if (!StatusPollPolicy.ShouldFetch(now, feed.LastAttempt, feed.Failures, feed.RetryAfterUntil, foreground, feed.Jitter)) continue;
            var (body, retryAfter) = await FetchAsync(url, now, cancellationToken).ConfigureAwait(false);
            var feeds = new Dictionary<string, StatusFeedState>(State.Feeds)
            {
                [url] = feed with
                {
                    LastAttempt = now,
                    Failures = body is null ? Math.Min(feed.Failures + 1, 10) : 0,
                    RetryAfterUntil = retryAfter,
                    Jitter = Random.Shared.NextDouble(),
                },
            };
            var statuses = new Dictionary<string, ProviderStatus>(State.Statuses);
            foreach (var (provider, source) in group)
            {
                ProviderStatus? next;
                if (body is not null) next = ProviderStatusSources.Parse(provider, body, now);
                else if (statuses.TryGetValue(provider.Id, out var previous) && now - previous.CheckedAt < StatusPollPolicy.MaxBackoff) continue;
                else next = ProviderStatus.Unknown(provider.Id, source!.PageUrl, now);
                if (next is null) continue;
                statuses.TryGetValue(provider.Id, out var before);
                changed |= before is null || before.State != next.State
                    || before.Headline(provider.DisplayName) != next.Headline(provider.DisplayName) || before.Detail != next.Detail;
                statuses[provider.Id] = next;
            }
            Set(State with { Feeds = feeds, Statuses = statuses });
        }
        return changed;
    }

    private async Task<(string? Body, DateTimeOffset? RetryAfter)> FetchAsync(string url, DateTimeOffset now, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            var retry = StatusPollPolicy.RetryAfterUntil(response.Headers.RetryAfter?.Delta, response.Headers.RetryAfter?.Date, now);
            var host = response.RequestMessage?.RequestUri?.Host ?? "";
            if (!response.IsSuccessStatusCode || !ProviderStatusSources.Hosts.Contains(host)) return (null, retry);
            if (response.Content.Headers.ContentLength > MaxFeedBytes) return (null, retry);
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return body.Length > MaxFeedBytes ? (null, retry) : (body, retry);
        }
        catch (HttpRequestException) { return (null, null); }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested) { return (null, null); }
    }
}
