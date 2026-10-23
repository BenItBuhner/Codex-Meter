using System.Diagnostics;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.App.Services;

/// <summary>What the UI knows about one provider right now.</summary>
public sealed class ProviderEntry(UsageProvider provider)
{
    public UsageProvider Provider { get; } = provider;
    public AuthTokens? Account { get; set; }
    public ProviderCacheEntry? Cache { get; set; }
    public bool Refreshing { get; set; }
    public bool SigningIn { get; set; }
    public DateTimeOffset? RateLimitedAt { get; set; }
    public bool SignedIn => Account is { IsUsable: true };
    public UsageSnapshot? Usage => Cache?.Usage;
}

/// <summary>
/// The app's single source of truth: accounts, cached usage, refresh scheduling and alerts.
/// Everything runs on the UI thread; awaits resume there through the dispatcher context.
/// </summary>
public sealed class AppState
{
    private readonly Dictionary<string, ProviderEntry> entries;
    private readonly HashSet<string> announced = [];

    public AppState(ProviderHub hub, SettingsStore settings)
    {
        Hub = hub;
        Settings = settings;
        entries = UsageProvider.All.ToDictionary(p => p.Id, p => new ProviderEntry(p));
        Settings.Changed += (_, _) => ProvidersChanged?.Invoke();
    }

    public ProviderHub Hub { get; }
    public SettingsStore Settings { get; }
    public bool IsDemo => Hub.IsDemo;

    /// <summary>One provider's account or usage changed.</summary>
    public event Action<UsageProvider>? ProviderChanged;

    /// <summary>Which providers are shown, or their order, changed.</summary>
    public event Action? ProvidersChanged;

    /// <summary>A refresh produced alerts worth a desktop notification.</summary>
    public event Action<IReadOnlyList<UsageAlert>>? Alerts;

    public ProviderEntry this[UsageProvider provider] => entries[provider.Id];

    public IEnumerable<ProviderEntry> Entries => UsageProvider.All.Select(p => entries[p.Id]);

    public IReadOnlyList<UsageProvider> SignedIn => Entries.Where(e => e.SignedIn).Select(e => e.Provider).ToList();

    /// <summary>Providers shown in the navigation pane, in the user's order.</summary>
    public IReadOnlyList<UsageProvider> EnabledProviders
    {
        get
        {
            var settings = Settings.Current;
            var enabled = UsageProvider.EnabledFromIds(settings.EnabledProviders, SignedIn.ToHashSet());
            return UsageProvider.OrderFromIds(settings.ProviderOrder).Where(enabled.Contains).ToList();
        }
    }

    public async Task InitializeAsync()
    {
        foreach (var entry in Entries)
        {
            var service = Hub[entry.Provider];
            entry.Account = await service.AccountAsync();
            entry.Cache = await service.CachedAsync();
        }
    }

    public bool IsStale(UsageProvider provider)
    {
        var entry = this[provider];
        return entry.Usage is not { } usage || DateTimeOffset.UtcNow - usage.FetchedAt > Hub[provider].StaleAfter;
    }

    /// <summary>Refreshes one provider. Without <paramref name="force"/>, fresh data is kept.</summary>
    public async Task RefreshAsync(UsageProvider provider, bool force)
    {
        var entry = this[provider];
        if (!entry.SignedIn || entry.Refreshing) return;
        if (!force && !IsStale(provider)) return;
        entry.Refreshing = true;
        ProviderChanged?.Invoke(provider);
        var previous = entry.Usage;
        var service = Hub[provider];
        try
        {
            var snapshot = await service.RefreshAsync();
            entry.RateLimitedAt = null;
            var settings = Settings.Current;
            var alerts = UsageAlerts.Evaluate(provider, previous, snapshot, settings.LowThreshold, settings.NotifyResets, settings.NotifyLow)
                .Where(a => announced.Add(a.Key + ":" + snapshot.FetchedAt.ToUnixTimeSeconds() / 3600))
                .ToList();
            if (alerts.Count > 0) Alerts?.Invoke(alerts);
        }
        catch (ProviderException error)
        {
            if (error.Message.Contains("rate-limiting", StringComparison.OrdinalIgnoreCase)) entry.RateLimitedAt = DateTimeOffset.UtcNow;
            Debug.WriteLine($"{provider.Id} refresh failed: {error.Message}");
        }
        finally
        {
            entry.Refreshing = false;
            entry.Account = await service.AccountAsync();
            entry.Cache = await service.CachedAsync();
            ProviderChanged?.Invoke(provider);
        }
    }

    public Task RefreshAllAsync(bool force) =>
        Task.WhenAll(EnabledProviders.Where(p => this[p].SignedIn).Select(p => RefreshAsync(p, force)));

    /// <summary>Called every minute: refreshes providers whose background interval has passed.</summary>
    public async Task BackgroundTickAsync()
    {
        var minutes = Math.Max(5, Settings.Current.RefreshMinutes);
        foreach (var provider in EnabledProviders)
        {
            var entry = this[provider];
            if (!entry.SignedIn || entry.Refreshing) continue;
            var interval = TimeSpan.FromMinutes(Math.Max(minutes, Hub[provider].BackgroundInterval.TotalMinutes));
            if (entry.RateLimitedAt is { } limited && DateTimeOffset.UtcNow - limited < TimeSpan.FromHours(1)) continue;
            var last = entry.Usage?.FetchedAt ?? entry.Cache?.LastErrorAt;
            if (last is null || DateTimeOffset.UtcNow - last >= interval) await RefreshAsync(provider, force: true);
        }
    }

    public async Task SignInAsync(UsageProvider provider, Func<Uri, Task> openBrowser, CancellationToken cancellationToken)
    {
        var entry = this[provider];
        entry.SigningIn = true;
        ProviderChanged?.Invoke(provider);
        try
        {
            entry.Account = await Hub[provider].SignInAsync(openBrowser, cancellationToken);
            entry.Cache = null;
            // Signing in to a provider turns its page on.
            Settings.Update(s =>
            {
                var enabled = UsageProvider.EnabledFromIds(s.EnabledProviders, SignedIn.ToHashSet());
                return s with { EnabledProviders = UsageProvider.WithEnabled(enabled, provider, true).Select(p => p.Id).ToList() };
            });
        }
        finally
        {
            entry.SigningIn = false;
            ProviderChanged?.Invoke(provider);
            ProvidersChanged?.Invoke();
        }
        await RefreshAsync(provider, force: true);
    }

    public async Task SignOutAsync(UsageProvider provider)
    {
        await Hub[provider].SignOutAsync();
        var entry = this[provider];
        entry.Account = null;
        entry.Cache = null;
        ProviderChanged?.Invoke(provider);
        ProvidersChanged?.Invoke();
    }

    public async Task<ResetConsumeResult> UseResetAsync(string? creditId)
    {
        var result = await Hub.ConsumeResetAsync(creditId);
        var entry = this[UsageProvider.Codex];
        entry.Cache = await Hub[UsageProvider.Codex].CachedAsync();
        ProviderChanged?.Invoke(UsageProvider.Codex);
        return result;
    }

    public void SetEnabled(UsageProvider provider, bool on)
    {
        Settings.Update(s =>
        {
            var enabled = UsageProvider.EnabledFromIds(s.EnabledProviders, SignedIn.ToHashSet());
            return s with { EnabledProviders = UsageProvider.WithEnabled(enabled, provider, on).Select(p => p.Id).ToList() };
        });
    }

    public void Move(UsageProvider provider, int delta)
    {
        Settings.Update(s =>
        {
            var order = UsageProvider.OrderFromIds(s.ProviderOrder);
            var index = order.IndexOf(provider);
            var target = Math.Clamp(index + delta, 0, order.Count - 1);
            if (index == target) return s;
            order.RemoveAt(index);
            order.Insert(target, provider);
            return s with { ProviderOrder = order.Select(p => p.Id).ToList() };
        });
    }
}
