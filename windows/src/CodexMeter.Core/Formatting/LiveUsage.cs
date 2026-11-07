using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Formatting;

/// <summary>Which window the live icon follows. Mirrors Android's "Percentage shown" (NowBarPercentMode).</summary>
public enum LiveWindowChoice { Auto, FiveHour, Weekly }

/// <summary>How the notification-area icon draws the value.</summary>
public enum LiveIconStyle { Ring, Number }

public enum LiveLevel { None, Healthy, Low, Critical }

/// <summary>
/// One provider's live value, as the tray icon, menu bar item and tooltip show it. Like the
/// Android live notification, a used-up window shows how long until it resets instead of 0%.
/// </summary>
public sealed record LiveReading(UsageProvider Provider, UsageWindow? Window, string WindowLabel, DateTimeOffset FetchedAt)
{
    public int RemainingPercent => Window?.RemainingPercent ?? 0;

    public bool Exhausted => Window is { RemainingPercent: 0 };

    /// <summary>Low at 35% left, critical at 15% (the tray's amber and red).</summary>
    public LiveLevel Level => Window is null ? LiveLevel.None
        : RemainingPercent <= 15 ? LiveLevel.Critical
        : RemainingPercent <= 35 ? LiveLevel.Low
        : LiveLevel.Healthy;

    /// <summary>How much of the ring is filled, 0 to 1: what's left, or what's used in "Used" mode.</summary>
    public double Fraction(UsageDisplayMode mode) => Window is null ? 0 : UsageFormat.Value(Window, mode) / 100.0;

    public TimeSpan? UntilReset(DateTimeOffset now) =>
        Window?.EffectiveResetDate(FetchedAt) is { } reset && reset > now ? reset - now : null;

    /// <summary>At most three characters for a 16–32px badge: "62", "100", "4h", "2d", or "–".</summary>
    public string IconText(UsageDisplayMode mode, DateTimeOffset now)
    {
        if (Window is null) return "–";
        if (Exhausted && UntilReset(now) is { } left) return LiveUsage.TinyDuration(left);
        return UsageFormat.Value(Window, mode).ToString();
    }

    /// <summary>Menu bar text: "62%", or "2h 14m" until a used-up window resets.</summary>
    public string CompactText(UsageDisplayMode mode, DateTimeOffset now)
    {
        if (Window is null) return "—";
        if (Exhausted && UntilReset(now) is { } left) return LiveUsage.ShortDuration(left);
        return $"{UsageFormat.Value(Window, mode)}%";
    }

    /// <summary>"Codex · 5-hour 62% left", or "Codex · Weekly resets in 2d 4h" once used up.</summary>
    public string Headline(UsageDisplayMode mode, DateTimeOffset now)
    {
        if (Window is null) return $"{Provider.DisplayName} · no data yet";
        if (Exhausted)
        {
            return UntilReset(now) is { } left
                ? $"{Provider.DisplayName} · {WindowLabel} resets in {LiveUsage.ShortDuration(left)}"
                : $"{Provider.DisplayName} · {WindowLabel} used up";
        }
        return $"{Provider.DisplayName} · {WindowLabel} {UsageFormat.Percent(Window, mode)}";
    }
}

/// <summary>
/// The rules the desktop tray and menu bar share with the Android live notification: which
/// provider to show (the AI app in front, then your choice, then the first in your sidebar), which
/// window (the tighter of 5-hour and weekly unless you pick one), and the used-up countdown.
/// </summary>
public static class LiveUsage
{
    /// <summary>The setting value meaning "pick automatically".</summary>
    public const string AutomaticProvider = "";

    /// <summary>
    /// Desktop apps that point the live icon at a provider while they're in front, by process or
    /// app name (lowercase, without ".exe"). The desktop twin of Android's AppSessionRules.
    /// Names with spaces are matched with and without them, so "grok bot" also covers "GrokBot".
    /// </summary>
    private static readonly Dictionary<string, UsageProvider> KnownApps = new(StringComparer.OrdinalIgnoreCase)
    {
        ["chatgpt"] = UsageProvider.Codex,
        ["codex"] = UsageProvider.Codex,
        ["claude"] = UsageProvider.ClaudeCode,
        ["cursor"] = UsageProvider.Cursor,
        ["windsurf"] = UsageProvider.Windsurf,
        ["kiro"] = UsageProvider.Kiro,
        ["antigravity"] = UsageProvider.Antigravity,
        ["warp"] = UsageProvider.Warp,
        // The SpaceXSI desktop family (published as SpaceXAI): the Grok app's process is "Grok Bot".
        ["grok"] = UsageProvider.Grok,
        ["grok bot"] = UsageProvider.Grok,
        ["grokbot"] = UsageProvider.Grok,
        ["grok desktop"] = UsageProvider.Grok,
        ["kimi"] = UsageProvider.Kimi,
    };

    /// <summary>
    /// Publishers, from an app's CompanyName, whose desktop apps belong to one provider. Keys are
    /// normalized (lowercase letters and digits, legal suffixes like "Inc." or "PBC" dropped).
    /// </summary>
    private static readonly Dictionary<string, UsageProvider> KnownCompanies = new(StringComparer.Ordinal)
    {
        ["anthropic"] = UsageProvider.ClaudeCode,
        ["anysphere"] = UsageProvider.Cursor,
        // SpaceXSI, which signs its Windows apps "SpaceXAI", and xAI before it.
        ["spacexai"] = UsageProvider.Grok,
        ["spacexsi"] = UsageProvider.Grok,
        ["xai"] = UsageProvider.Grok,
    };

    /// <summary>The same apps keyed without spaces, dashes or other separators.</summary>
    private static readonly Dictionary<string, UsageProvider> CompactApps = KnownApps
        .GroupBy(pair => Compact(pair.Key))
        .ToDictionary(group => group.Key, group => group.First().Value, StringComparer.Ordinal);

    private static readonly HashSet<string> CompanySuffixes = new(StringComparer.Ordinal)
    {
        "inc", "incorporated", "llc", "ltd", "limited", "pbc", "corp", "corporation", "co", "company", "gmbh", "plc",
    };

    /// <summary>Names of the apps matched to <paramref name="provider"/>, for the setting's description.</summary>
    public static IEnumerable<string> AppNamesFor(UsageProvider provider) =>
        KnownApps.Where(pair => pair.Value == provider).Select(pair => pair.Key);

    /// <summary>
    /// The provider whose app this is, from a process name ("Claude", "ChatGPT.exe", "Grok Bot")
    /// or app name. Tries the exact name, then the name without spaces or separators ("grok-bot",
    /// "GrokBot"), then the first word ("Grok Desktop", "Cursor Helper"). It never matches a
    /// name that merely contains a known one, so "Claudette" or "Precursor" stay unmatched.
    /// </summary>
    public static UsageProvider? ProviderForApp(string? appName)
    {
        if (string.IsNullOrWhiteSpace(appName)) return null;
        var name = appName.Trim();
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4].TrimEnd();
        if (name.Length == 0) return null;
        if (KnownApps.TryGetValue(name, out var exact)) return exact;
        var compact = Compact(name);
        if (compact.Length == 0) return null;
        if (CompactApps.TryGetValue(compact, out var normalized)) return normalized;
        var words = Words(name);
        if (words.Count > 1 && CompactApps.TryGetValue(words[0], out var leading)) return leading;
        return null;
    }

    /// <summary>
    /// The provider of a running app, from what Windows knows about it: the process name first,
    /// then the executable's ProductName, then its CompanyName (Anthropic, Anysphere, SpaceXAI).
    /// </summary>
    public static UsageProvider? ProviderForApp(string? processName, string? productName, string? companyName) =>
        ProviderForApp(processName) ?? ProviderForApp(productName) ?? ProviderForCompany(companyName);

    /// <summary>The provider that publishes apps as <paramref name="companyName"/> ("Anthropic, PBC", "SpaceXAI").</summary>
    public static UsageProvider? ProviderForCompany(string? companyName)
    {
        if (string.IsNullOrWhiteSpace(companyName)) return null;
        var words = Words(companyName);
        while (words.Count > 1 && CompanySuffixes.Contains(words[^1])) words.RemoveAt(words.Count - 1);
        var key = string.Concat(words);
        return key.Length > 0 && KnownCompanies.TryGetValue(key, out var provider) ? provider : null;
    }

    /// <summary>Lowercase letters and digits only: "Grok Bot" → "grokbot".</summary>
    private static string Compact(string value) =>
        string.Concat(value.Where(char.IsLetterOrDigit)).ToLowerInvariant();

    /// <summary>Lowercase words split on anything that isn't a letter or digit.</summary>
    private static List<string> Words(string value)
    {
        var words = new List<string>();
        var start = -1;
        for (var i = 0; i <= value.Length; i++)
        {
            var inWord = i < value.Length && char.IsLetterOrDigit(value[i]);
            if (inWord && start < 0) start = i;
            else if (!inWord && start >= 0)
            {
                words.Add(value[start..i].ToLowerInvariant());
                start = -1;
            }
        }
        return words;
    }

    /// <summary>
    /// The provider to show. <paramref name="candidates"/> are the providers with usage to show,
    /// in sidebar order. The app in front wins, then the chosen provider, then the first one.
    /// </summary>
    public static UsageProvider? ChooseProvider(string? chosenId, UsageProvider? frontApp, IReadOnlyList<UsageProvider> candidates)
    {
        if (frontApp is not null && candidates.Contains(frontApp)) return frontApp;
        if (UsageProvider.Find(chosenId) is { } chosen && candidates.Contains(chosen)) return chosen;
        return candidates.FirstOrDefault();
    }

    /// <summary>
    /// The window to show and its label. Auto takes whichever of 5-hour and weekly (or monthly)
    /// has less left, preferring 5-hour on a tie; an explicit choice falls back to the other window
    /// when the service doesn't report it. Services with neither use their first extra limit.
    /// </summary>
    public static (UsageWindow? Window, string Label) ChooseWindow(UsageSnapshot? usage, LiveWindowChoice choice)
    {
        if (usage is null) return (null, "");
        var five = usage.FiveHour;
        var longWindow = usage.LongWindow;
        var longLabel = usage.LongWindowIsMonthly ? "Monthly" : "Weekly";
        if (five is null && longWindow is null)
        {
            var limit = usage.AdditionalLimits.FirstOrDefault();
            var window = limit?.Primary ?? limit?.Secondary;
            return window is null ? (null, "") : (window, limit!.DisplayName);
        }
        var useLong = choice switch
        {
            LiveWindowChoice.FiveHour => five is null,
            LiveWindowChoice.Weekly => longWindow is not null,
            _ => five is null || (longWindow is not null && longWindow.RemainingPercent < five.RemainingPercent),
        };
        return useLong ? (longWindow, longLabel) : (five, "5-hour");
    }

    public static LiveReading Read(UsageProvider provider, UsageSnapshot? usage, LiveWindowChoice choice)
    {
        var (window, label) = ChooseWindow(usage, choice);
        return new LiveReading(provider, window, label, usage?.FetchedAt ?? DateTimeOffset.UtcNow);
    }

    /// <summary>"2d 4h", "3h 12m" or "45m": the Android live notification's countdown.</summary>
    public static string ShortDuration(TimeSpan span)
    {
        var minutes = (long)Math.Ceiling(Math.Max(0, span.TotalMinutes));
        var days = minutes / 1440;
        var hours = minutes % 1440 / 60;
        if (days > 0) return hours > 0 ? $"{days}d {hours}h" : $"{days}d";
        if (hours > 0) return minutes % 60 > 0 ? $"{hours}h {minutes % 60}m" : $"{hours}h";
        return $"{Math.Max(1, minutes)}m";
    }

    /// <summary>
    /// One unit in at most three characters, for the icon: "45m", "4h" (rounded up, under two days),
    /// or "3d" (nearest day).
    /// </summary>
    public static string TinyDuration(TimeSpan span)
    {
        var minutes = (long)Math.Ceiling(Math.Max(0, span.TotalMinutes));
        if (minutes >= 2 * 1440) return $"{(minutes + 720) / 1440}d";
        if (minutes >= 60) return $"{(minutes + 59) / 60}h";
        return $"{Math.Max(1, minutes)}m";
    }
}
