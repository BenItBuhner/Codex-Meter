using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// The alert rules both desktop apps run after a refresh and on their minute timer, kept here so
/// the Windows and Linux AppState copies stay small: resets, low and used-up limits, surprise
/// refills, and "usage is back" with its remembered limits saved in settings.
/// </summary>
public static class RefreshAlerts
{
    public static List<UsageAlert> AfterRefresh(SettingsStore store, UsageProvider provider, UsageSnapshot? previous,
        UsageSnapshot snapshot, DateTimeOffset now)
    {
        var s = store.Current;
        var alerts = UsageAlerts.Evaluate(provider, previous, snapshot, s.LowThreshold, s.NotifyResets, s.NotifyLow,
            s.NotifySurpriseRefills, s.NotifyUsageBack, s.LowAlertMetric).ToList();
        var (armed, back) = UsageRefill.Observe(provider, snapshot, s.UsedUpLimits, now);
        Save(store, armed);
        if (s.NotifyUsageBack) alerts.AddRange(back);
        return alerts;
    }

    /// <summary>Used-up limits whose reset time has passed since the last refresh.</summary>
    public static List<UsageAlert> Tick(SettingsStore store, DateTimeOffset now)
    {
        var s = store.Current;
        if (s.UsedUpLimits.Count == 0) return [];
        var (armed, back) = UsageRefill.Due(s.UsedUpLimits, UsageProvider.Find, now);
        Save(store, armed);
        return s.NotifyUsageBack ? back : [];
    }

    /// <summary>Forgets a provider's remembered limits (after signing out).</summary>
    public static void Forget(SettingsStore store, UsageProvider provider)
    {
        var armed = store.Current.UsedUpLimits.Where(p => !p.Key.StartsWith(provider.Id + ":", StringComparison.Ordinal))
            .ToDictionary(p => p.Key, p => p.Value);
        Save(store, armed);
    }

    private static void Save(SettingsStore store, Dictionary<string, long> armed)
    {
        var current = store.Current.UsedUpLimits;
        if (armed.Count == current.Count && armed.All(p => current.TryGetValue(p.Key, out var v) && v == p.Value)) return;
        store.Update(x => x with { UsedUpLimits = armed });
    }

    /// <summary>The newly listed models worth a notification, for the providers the user is signed in to.</summary>
    /// <summary>
    /// Notifications → New reset credits: the available count of Codex reset credits or Claude saved
    /// resets went up since the last refresh. Nothing on the first count after signing in.
    /// </summary>
    public static List<UsageAlert> NewResets(SettingsStore store, UsageProvider provider, ResetCreditsSnapshot? before,
        ResetCreditsSnapshot? after)
    {
        if (!store.Current.NotifyNewResets || before is null || after is null || after.AvailableCount <= before.AvailableCount) return [];
        var gained = after.AvailableCount - before.AvailableCount;
        var what = provider == UsageProvider.ClaudeCode ? (gained == 1 ? "saved reset" : "saved resets") : (gained == 1 ? "reset credit" : "reset credits");
        return [new UsageAlert(UsageAlertKind.Reset, $"New {provider.DisplayName} {what}",
            $"You have {after.AvailableCount} available now. Use one from {provider.DisplayName}'s page or let auto-use spend it.",
            $"newresets:{provider.Id}:{after.AvailableCount}:{after.FetchedAt.ToUnixTimeSeconds() / 3600}")];
    }

    /// <summary>Notifications → Expiring reset credits: one reminder per credit, a day before it expires unused.</summary>
    public static readonly TimeSpan ExpiryReminder = TimeSpan.FromHours(24);

    public static List<UsageAlert> ExpiringResets(SettingsStore store, IEnumerable<(UsageProvider Provider, ResetCreditsSnapshot? Credits)> providers,
        DateTimeOffset now)
    {
        if (!store.Current.NotifyExpiringResets) return [];
        var alerts = new List<UsageAlert>();
        foreach (var (provider, credits) in providers)
        {
            foreach (var credit in (credits?.Credits ?? []).Where(c => c.IsAvailable && c.ExpiresAt is { } e && e > now && e - now <= ExpiryReminder))
            {
                var title = credit.Title.Length > 0 ? credit.Title : "Reset credit";
                alerts.Add(new UsageAlert(UsageAlertKind.Reset, $"{provider.DisplayName} {title.ToLowerInvariant()} expires soon",
                    $"It expires {UsageFormat.Relative(credit.ExpiresAt!.Value, now)}. Use it before then or it's lost.",
                    $"expiring:{provider.Id}:{ClaudeSavedResets.GrantIdOf(credit.Id)}:{credit.ExpiresAt!.Value.ToUnixTimeSeconds()}"));
            }
        }
        // One reminder per grant: a Claude grant with several resets reads once.
        return alerts.DistinctBy(a => a.Key).ToList();
    }

    public static List<UsageAlert> NewModels(IEnumerable<SeenModel> confirmed, IReadOnlyList<UsageProvider> signedIn)
    {
        var alerts = new List<UsageAlert>();
        foreach (var model in confirmed)
        {
            if (ModelDiscovery.MatchedBy(model.Id, signedIn) is not { } provider) continue;
            alerts.Add(new UsageAlert(UsageAlertKind.NewModel, "New on OpenRouter: " + model.Name,
                ModelDiscovery.Reason(provider) + ". Newly listed, not necessarily newly released.", "model:" + model.Id));
        }
        return alerts;
    }
}
