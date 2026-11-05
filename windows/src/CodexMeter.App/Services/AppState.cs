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
    public ProviderHistory History { get; set; } = new();
    public bool Refreshing { get; set; }
    public bool SigningIn { get; set; }
    public DateTimeOffset? RateLimitedAt { get; set; }
    public bool SignedIn => Account is { IsUsable: true };
    public UsageSnapshot? Usage => Cache?.Usage;

    /// <summary>The last refresh failed after the shown data was fetched.</summary>
    public bool HasError => SignedIn && Cache?.LastError is not null && (Usage is null || Cache.LastErrorAt > Usage.FetchedAt);

    /// <summary>Some window is at or under 15% left, or a limit is reached.</summary>
    public bool IsLow => Usage is { } usage && (usage.LimitReached
        || new[] { usage.FiveHour, usage.Weekly, usage.Monthly }.Any(w => w is { RemainingPercent: <= 15 }));
}

/// <summary>
/// The app's single source of truth: accounts, cached usage, history, refresh scheduling, alerts
/// and reset auto-use. Everything runs on the UI thread; awaits resume there through the
/// dispatcher context.
/// </summary>
public sealed class AppState
{
    private readonly Dictionary<string, ProviderEntry> entries;
    private readonly HashSet<string> announced = [];
    private readonly HashSet<string> autoUsedCredits = [];
    private bool autoUsing;

    public AppState(ProviderHub hub, SettingsStore settings, UsageHistoryStore history)
    {
        Hub = hub;
        Settings = settings;
        HistoryStore = history;
        entries = hub.Providers.ToDictionary(p => p.Id, p => new ProviderEntry(p));
        Settings.Changed += (_, _) => ProvidersChanged?.Invoke();
    }

    public ProviderHub Hub { get; }
    public SettingsStore Settings { get; }
    public UsageHistoryStore HistoryStore { get; }
    public bool IsDemo => Hub.IsDemo;

    /// <summary>One provider's account or usage changed.</summary>
    public event Action<UsageProvider>? ProviderChanged;

    /// <summary>Which providers are shown, or their order, changed.</summary>
    public event Action? ProvidersChanged;

    /// <summary>A refresh produced alerts worth a desktop notification.</summary>
    public event Action<IReadOnlyList<UsageAlert>>? Alerts;

    public ProviderEntry this[UsageProvider provider] => entries[provider.Id];

    public bool Supports(UsageProvider provider) => entries.ContainsKey(provider.Id);

    /// <summary>Every provider this build can connect, in recommended order.</summary>
    public IEnumerable<ProviderEntry> Entries => Hub.Providers.Select(p => entries[p.Id]);

    public IReadOnlyList<UsageProvider> SignedIn => Entries.Where(e => e.SignedIn).Select(e => e.Provider).ToList();

    /// <summary>Providers shown in the navigation pane, in the user's order.</summary>
    public IReadOnlyList<UsageProvider> EnabledProviders
    {
        get
        {
            var settings = Settings.Current;
            var enabled = UsageProvider.EnabledFromIds(settings.EnabledProviders, SignedIn.ToHashSet());
            return UsageProvider.OrderFromIds(settings.ProviderOrder).Where(p => enabled.Contains(p) && Supports(p)).ToList();
        }
    }

    /// <summary>Providers listed in Settings → Sidebar: enabled or connected, in the user's order.</summary>
    public IReadOnlyList<UsageProvider> SidebarCandidates
    {
        get
        {
            var enabled = EnabledProviders;
            var connected = SignedIn;
            return UsageProvider.OrderFromIds(Settings.Current.ProviderOrder)
                .Where(p => Supports(p) && (enabled.Contains(p) || connected.Contains(p) || p.IsBuiltIn)).ToList();
        }
    }

    public async Task InitializeAsync()
    {
        foreach (var entry in Entries)
        {
            var service = Hub[entry.Provider];
            entry.Account = await service.AccountAsync();
            entry.Cache = await service.CachedAsync();
            entry.History = await HistoryStore.LoadAsync(entry.Provider.Id);
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
            entry.History = await HistoryStore.RecordAsync(provider.Id, snapshot);
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
        if (provider == UsageProvider.Codex) await AutoUseResetsAsync();
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
        await AutoUseResetsAsync();
    }

    /// <summary>Runs any sign-in style: a browser round trip, a device code, or a pasted token.</summary>
    public async Task SignInAsync(UsageProvider provider, SignInPrompt prompt, CancellationToken cancellationToken)
    {
        var entry = this[provider];
        entry.SigningIn = true;
        ProviderChanged?.Invoke(provider);
        try
        {
            entry.Account = await Hub[provider].SignInAsync(prompt, cancellationToken);
            await AfterSignInAsync(provider);
        }
        finally
        {
            entry.SigningIn = false;
            ProviderChanged?.Invoke(provider);
            ProvidersChanged?.Invoke();
        }
        await RefreshAsync(provider, force: true);
    }

    /// <summary>Connects with a pasted API key; the key is checked against the provider first.</summary>
    public async Task SignInWithApiKeyAsync(UsageProvider provider, string key, CancellationToken cancellationToken)
    {
        var entry = this[provider];
        entry.SigningIn = true;
        ProviderChanged?.Invoke(provider);
        try
        {
            entry.Account = await Hub[provider].SignInWithApiKeyAsync(key, cancellationToken);
            await AfterSignInAsync(provider);
        }
        finally
        {
            entry.SigningIn = false;
            ProviderChanged?.Invoke(provider);
            ProvidersChanged?.Invoke();
        }
        await RefreshAsync(provider, force: true);
    }

    private async Task AfterSignInAsync(UsageProvider provider)
    {
        var entry = this[provider];
        entry.Cache = null;
        // A different account's history must not carry over.
        await HistoryStore.ClearAsync(provider.Id);
        entry.History = new ProviderHistory();
        // Signing in to a provider turns its page on.
        Settings.Update(s =>
        {
            var enabled = UsageProvider.EnabledFromIds(s.EnabledProviders, SignedIn.ToHashSet());
            return s with
            {
                EnabledProviders = UsageProvider.WithEnabled(enabled, provider, true).Select(p => p.Id).ToList(),
                OnboardingDone = true,
            };
        });
    }

    public async Task SignOutAsync(UsageProvider provider)
    {
        await Hub[provider].SignOutAsync();
        await HistoryStore.ClearAsync(provider.Id);
        var entry = this[provider];
        entry.Account = null;
        entry.Cache = null;
        entry.History = new ProviderHistory();
        // Hide its page unless it's the last one.
        if (EnabledProviders.Contains(provider) && EnabledProviders.Count > 1) SetEnabled(provider, false);
        ProviderChanged?.Invoke(provider);
        ProvidersChanged?.Invoke();
    }

    public async Task ClearHistoryAsync(UsageProvider? provider = null)
    {
        foreach (var entry in provider is null ? Entries : [this[provider]])
        {
            await HistoryStore.ClearAsync(entry.Provider.Id);
            entry.History = new ProviderHistory();
            ProviderChanged?.Invoke(entry.Provider);
        }
    }

    public async Task<ResetConsumeResult> UseResetAsync(string? creditId)
    {
        var result = await Hub.ConsumeResetAsync(creditId);
        var entry = this[UsageProvider.Codex];
        entry.Cache = await Hub[UsageProvider.Codex].CachedAsync();
        if (entry.Usage is { } usage) entry.History = await HistoryStore.RecordAsync(UsageProvider.Codex.Id, usage);
        ProviderChanged?.Invoke(UsageProvider.Codex);
        return result;
    }

    /// <summary>
    /// Codex reset auto-use: spends a credit that is about to expire, or one when a window runs
    /// low, as chosen in Settings. Each rule fires at most once per credit or window.
    /// </summary>
    private async Task AutoUseResetsAsync()
    {
        var settings = Settings.Current;
        if (autoUsing || !Supports(UsageProvider.Codex) || !(settings.AutoUseExpiringResets || settings.AutoUseWhenLow)) return;
        var entry = this[UsageProvider.Codex];
        if (!entry.SignedIn) return;
        autoUsing = true;
        try
        {
            var now = DateTimeOffset.UtcNow;
            if (settings.AutoUseExpiringResets && entry.Cache?.Credits is { } credits)
            {
                foreach (var due in CreditExpiryAutoApply.Due(credits.Credits, now, settings.AutoUseLeadMinutes))
                {
                    if (!autoUsedCredits.Add(due.Token)) continue;
                    var result = await UseResetAsync(due.CreditId);
                    Alerts?.Invoke([new UsageAlert(UsageAlertKind.Reset, "Used an expiring Codex reset",
                        result.Applied ? "It would have expired soon, so Codex Meter used it. Your 5-hour and weekly limits are clear."
                            : result.UserMessage, "autouse:" + due.Token)]);
                    return;
                }
            }
            if (settings.AutoUseWhenLow)
            {
                var decision = LowUsageAutoUse.Evaluate(entry.Usage, settings.AutoUseMetric, settings.AutoUseThreshold,
                    entry.Cache?.Credits?.AvailableCount ?? entry.Usage?.ResetCreditsAvailable, settings.AutoUseLastToken, now);
                if (!decision.Fires) return;
                Settings.Update(s => s with { AutoUseLastToken = decision.WindowToken });
                var result = await UseResetAsync(null);
                Alerts?.Invoke([new UsageAlert(UsageAlertKind.Reset, "Used a Codex reset",
                    result.Applied ? $"Your {decision.WindowLabel} limit was down to {decision.RemainingPercent}%, so Codex Meter used a banked reset."
                        : result.UserMessage, "autouse:" + decision.WindowToken)]);
            }
        }
        catch (Exception error)
        {
            Debug.WriteLine("Reset auto-use failed: " + error.Message);
        }
        finally
        {
            autoUsing = false;
        }
    }

    public void SetEnabled(UsageProvider provider, bool on)
    {
        Settings.Update(s =>
        {
            var enabled = UsageProvider.EnabledFromIds(s.EnabledProviders, SignedIn.ToHashSet());
            return s with { EnabledProviders = UsageProvider.WithEnabled(enabled, provider, on).Select(p => p.Id).ToList() };
        });
    }

    /// <summary>Saves a new sidebar order (from drag and drop); providers not listed keep their place after.</summary>
    public void SetOrder(IEnumerable<UsageProvider> order)
    {
        var ids = order.Select(p => p.Id).ToList();
        Settings.Update(s => s with { ProviderOrder = UsageProvider.OrderFromIds(ids.Concat(s.ProviderOrder)).Select(p => p.Id).ToList() });
    }

    public void Move(UsageProvider provider, int delta)
    {
        var order = SidebarCandidates.ToList();
        var index = order.IndexOf(provider);
        if (index < 0) return;
        var target = Math.Clamp(index + delta, 0, order.Count - 1);
        if (index == target) return;
        order.RemoveAt(index);
        order.Insert(target, provider);
        SetOrder(order);
    }

    /// <summary>The cards a dashboard shows, in the user's order, without hidden ones.</summary>
    public List<string> VisibleSections(UsageProvider provider)
    {
        var entry = this[provider];
        var settings = Settings.Current;
        return DashboardSections.Visible(entry.Usage, HasResetCredits(provider), settings.SectionOrderFor(provider.Id),
            settings.HiddenSectionsFor(provider.Id));
    }

    public bool HasResetCredits(UsageProvider provider) =>
        provider == UsageProvider.Codex && (this[provider].Cache?.Credits is not null || this[provider].Usage?.ResetCreditsAvailable is not null);
}
