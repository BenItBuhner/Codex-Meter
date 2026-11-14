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
            s.NotifySurpriseRefills, s.NotifyUsageBack).ToList();
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
