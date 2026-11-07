namespace CodexMeter.Core.Models;

/// <summary>
/// Stable keys and merge rules for movable dashboard cards (Edit dashboard). A port of the
/// iOS and Android <c>DashboardSections</c>, so the same keys mean the same cards everywhere.
/// </summary>
public static class DashboardSections
{
    public const string FiveHour = "five_hour";
    public const string Weekly = "weekly";
    public const string Monthly = "monthly";
    public const string UsageCredits = "usage_credits";
    public const string SpendControl = "spend_control";
    public const string UsageHistory = "usage_history";
    public const string ResetCredits = "reset_credits";

    private const string LimitPrefix = "limit:";

    /// <summary>A stable key for an additional limit, preferring the API id over display names.</summary>
    public static string LimitKey(UsageLimit limit)
    {
        var identity = new[] { limit.Id, limit.Name, limit.MeteredFeature, "additional" }.First(s => !string.IsNullOrWhiteSpace(s));
        return LimitPrefix + identity.ToLowerInvariant().Replace(',', '_');
    }

    public static bool IsLimitKey(string key) => key.StartsWith(LimitPrefix, StringComparison.Ordinal);

    /// <summary>
    /// Every card <paramref name="snapshot"/> can show, in default order: the windows, the monthly
    /// credit limit, additional limits, then credits, history and resets.
    /// </summary>
    public static List<string> Available(UsageSnapshot? snapshot, bool hasResetCredits, bool includeHistory = true)
    {
        var result = new List<string>();
        if (snapshot is not null)
        {
            if (snapshot.FiveHour is not null) result.Add(FiveHour);
            if (snapshot.Weekly is not null) result.Add(Weekly);
            if (snapshot.Monthly is not null) result.Add(Monthly);
            if (snapshot.SpendControlLimit is not null) result.Add(SpendControl);
            foreach (var limit in snapshot.AdditionalLimits)
            {
                var key = LimitKey(limit);
                if (!result.Contains(key)) result.Add(key);
            }
            if (snapshot.UsageCredits?.ShouldDisplay == true) result.Add(UsageCredits);
        }
        if (includeHistory) result.Add(UsageHistory);
        if (hasResetCredits) result.Add(ResetCredits);
        return result;
    }

    /// <summary>
    /// Applies a saved order to the available keys. Unavailable saved keys drop out; new keys go
    /// after their nearest preceding key in the natural order instead of at the end.
    /// </summary>
    public static List<string> ResolveOrder(IEnumerable<string>? saved, IEnumerable<string> available)
    {
        var availableKeys = available.Distinct().ToList();
        var result = (saved ?? []).Distinct().Where(availableKeys.Contains).ToList();
        for (var index = 0; index < availableKeys.Count; index++)
        {
            var key = availableKeys[index];
            if (result.Contains(key)) continue;
            var insertion = 0;
            foreach (var preceding in availableKeys.Take(index))
            {
                var position = result.IndexOf(preceding);
                if (position >= 0) insertion = Math.Max(insertion, position + 1);
            }
            result.Insert(insertion, key);
        }
        return result;
    }

    /// <summary>The cards to show, in the user's order, without hidden ones.</summary>
    public static List<string> Visible(UsageSnapshot? snapshot, bool hasResetCredits, IEnumerable<string>? savedOrder,
        IEnumerable<string>? hidden, bool includeHistory = true)
    {
        var hiddenKeys = (hidden ?? []).ToHashSet();
        return ResolveOrder(savedOrder, Available(snapshot, hasResetCredits, includeHistory)).Where(k => !hiddenKeys.Contains(k)).ToList();
    }

    /// <summary>Edit dashboard's name for a card.</summary>
    public static string Label(string key, UsageSnapshot? snapshot)
    {
        if (IsLimitKey(key))
            return snapshot?.AdditionalLimits.FirstOrDefault(l => LimitKey(l) == key)?.DisplayName ?? "Additional limit";
        return key switch
        {
            FiveHour => snapshot?.FiveHour is { } window ? Formatting.UsageFormat.WindowName(window.WindowSeconds) + " limit" : "5-hour limit",
            Weekly => "Weekly limit",
            Monthly => "Monthly limit",
            UsageCredits => "Credits",
            SpendControl => "Monthly credit limit",
            UsageHistory => "Usage history",
            ResetCredits => "Reset credits",
            _ => key,
        };
    }
}

/// <summary>
/// Optional highlights under the usage history chart. Visibility is saved as the keys the user
/// switched away from their default, so a later default change never overrides a choice.
/// </summary>
public static class HistorySections
{
    public const string Guide = "guide";
    public const string WindowList = "window_list";
    public const string InsightPace = "insight_pace";
    public const string InsightExhaustion = "insight_exhaustion";
    public const string InsightAverage = "insight_average";
    public const string InsightPeak = "insight_peak";

    public static IReadOnlyList<string> All { get; } = [Guide, WindowList, InsightPace, InsightExhaustion, InsightAverage, InsightPeak];

    public static bool DefaultVisible(string key) => key != Guide;

    public static string Label(string key) => key switch
    {
        Guide => "How to read the chart",
        WindowList => "Previous windows",
        InsightPace => "Pace vs. typical",
        InsightExhaustion => "Projected run-out",
        InsightAverage => "Average completed window",
        InsightPeak => "Peak burn rate",
        _ => key,
    };

    public static bool IsVisible(IEnumerable<string>? overrides, string key) => DefaultVisible(key) != (overrides ?? []).Contains(key);

    public static List<string> SetVisible(IEnumerable<string>? overrides, string key, bool visible)
    {
        var keys = (overrides ?? []).Where(k => k.Trim().Length > 0).Distinct().ToList();
        if (visible == DefaultVisible(key)) keys.Remove(key);
        else if (!keys.Contains(key)) keys.Add(key);
        return keys;
    }
}
