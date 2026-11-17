using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Formatting;

/// <summary>One "While using AI apps" entry as stored: an app or process name and the service it shows.</summary>
public sealed record AppMapping(string App, string ProviderId);

/// <summary>
/// One row of the "While using AI apps" list: a built-in app (possibly re-pointed by the user) or
/// one the user added, and whether it's switched on.
/// </summary>
public sealed record AppRule(string App, UsageProvider Provider, bool On, bool BuiltIn, bool Edited)
{
    public string Key => AppMappings.Key(App);
}

/// <summary>
/// The desktop twin of Android's AppSessionRules and AppSessionPreferences: which desktop apps point
/// the live icon at a service while they're in front. The built-in apps come first; the user can
/// add any app or process, re-point or remove a built-in one, and switch each one off. Only the
/// changes are stored, so built-in apps added in later versions still show up.
/// </summary>
public static class AppMappings
{
    /// <summary>
    /// The built-in apps, by process or app name. Names are matched without case, spaces or
    /// separators, so "Grok Bot" also covers "GrokBot", "grok-bot" and "Grok Bot.exe".
    /// </summary>
    public static readonly IReadOnlyList<AppMapping> Defaults =
    [
        new("ChatGPT", UsageProvider.Codex.Id),
        new("Codex", UsageProvider.Codex.Id),
        new("Claude", UsageProvider.ClaudeCode.Id),
        new("Cursor", UsageProvider.Cursor.Id),
        new("Windsurf", UsageProvider.Windsurf.Id),
        new("Kiro", UsageProvider.Kiro.Id),
        new("Antigravity", UsageProvider.Antigravity.Id),
        new("Warp", UsageProvider.Warp.Id),
        new("Grok", UsageProvider.Grok.Id),
        // The SpaceXSI desktop family (published as SpaceXAI): the Grok app's process is "Grok Bot".
        new("Grok Bot", UsageProvider.Grok.Id),
        new("Kimi", UsageProvider.Kimi.Id),
    ];

    private static readonly HashSet<string> DefaultKeys = Defaults.Select(d => Key(d.App)).ToHashSet(StringComparer.Ordinal);

    /// <summary>The name an app is matched and stored by: lowercase letters and digits, without ".exe".</summary>
    public static string Key(string? app)
    {
        var name = (app ?? "").Trim();
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
        return string.Concat(name.Where(char.IsLetterOrDigit)).ToLowerInvariant();
    }

    public static bool IsBuiltIn(string app) => DefaultKeys.Contains(Key(app));

    /// <summary>
    /// Every row, in order: the built-in apps the user kept (with their edits), then the apps they
    /// added. Entries naming a service this version doesn't know are skipped.
    /// </summary>
    public static IReadOnlyList<AppRule> Resolve(AppSettings settings)
    {
        var added = new Dictionary<string, AppMapping>(StringComparer.Ordinal);
        foreach (var mapping in settings.LiveAppsAdded)
        {
            var key = Key(mapping.App);
            if (key.Length > 0) added[key] = mapping;
        }
        var off = settings.LiveAppsOff.Select(Key).ToHashSet(StringComparer.Ordinal);
        var removed = settings.LiveAppsRemoved.Select(Key).ToHashSet(StringComparer.Ordinal);
        var rules = new List<AppRule>();
        foreach (var builtIn in Defaults)
        {
            var key = Key(builtIn.App);
            if (removed.Contains(key)) continue;
            var edit = added.GetValueOrDefault(key);
            if (UsageProvider.Find(edit?.ProviderId ?? builtIn.ProviderId) is not { } provider) continue;
            rules.Add(new AppRule(edit?.App ?? builtIn.App, provider, !off.Contains(key), BuiltIn: true,
                Edited: edit is not null && edit.ProviderId != builtIn.ProviderId));
        }
        foreach (var (key, mapping) in added)
        {
            if (DefaultKeys.Contains(key) || UsageProvider.Find(mapping.ProviderId) is not { } provider) continue;
            rules.Add(new AppRule(mapping.App.Trim(), provider, !off.Contains(key), BuiltIn: false, Edited: false));
        }
        return rules;
    }

    /// <summary>
    /// Adds an app, or changes one: <paramref name="replacing"/> is the row being edited, when the
    /// user renamed it. The app is switched on, as on Android.
    /// </summary>
    public static AppSettings Set(AppSettings settings, string app, UsageProvider provider, string? replacing = null)
    {
        var name = app.Trim();
        var key = Key(name);
        if (key.Length == 0) return settings;
        if (replacing is not null && Key(replacing) != key) settings = Remove(settings, replacing);
        var added = settings.LiveAppsAdded.Where(m => Key(m.App) != key).ToList();
        var builtIn = Defaults.FirstOrDefault(d => Key(d.App) == key);
        // Pointing a built-in app back at its own service is the same as not editing it.
        if (builtIn is null || builtIn.ProviderId != provider.Id) added.Add(new AppMapping(name, provider.Id));
        return settings with
        {
            LiveAppsAdded = added,
            LiveAppsOff = settings.LiveAppsOff.Where(k => Key(k) != key).ToList(),
            LiveAppsRemoved = settings.LiveAppsRemoved.Where(k => Key(k) != key).ToList(),
        };
    }

    /// <summary>Removes an added app, or hides a built-in one (Restore defaults brings it back).</summary>
    public static AppSettings Remove(AppSettings settings, string app)
    {
        var key = Key(app);
        var removed = settings.LiveAppsRemoved.Where(k => Key(k) != key).ToList();
        if (DefaultKeys.Contains(key)) removed.Add(key);
        return settings with
        {
            LiveAppsAdded = settings.LiveAppsAdded.Where(m => Key(m.App) != key).ToList(),
            LiveAppsOff = settings.LiveAppsOff.Where(k => Key(k) != key).ToList(),
            LiveAppsRemoved = removed,
        };
    }

    public static AppSettings SetOn(AppSettings settings, string app, bool on)
    {
        var key = Key(app);
        var off = settings.LiveAppsOff.Where(k => Key(k) != key).ToList();
        if (!on) off.Add(key);
        return settings with { LiveAppsOff = off };
    }

    /// <summary>Back to the built-in list: added apps, edits, removals and switches are cleared.</summary>
    public static AppSettings RestoreDefaults(AppSettings settings) =>
        settings with { LiveAppsAdded = [], LiveAppsOff = [], LiveAppsRemoved = [] };

    public static bool IsCustomized(AppSettings settings) =>
        settings.LiveAppsAdded.Count > 0 || settings.LiveAppsOff.Count > 0 || settings.LiveAppsRemoved.Count > 0;
}

/// <summary>
/// Finds the service of the app in front from a set of <see cref="AppRule"/>s. A name matches a rule
/// when, without case, spaces or separators, it equals the rule's app or its leading words do
/// ("Cursor Helper", "Grok Desktop"). It never matches a name that merely contains a rule's app,
/// so "Claudette" or "Precursor" stay unmatched. A switched-off app matches nothing, and stops the
/// fallbacks too, so turning off "Claude" isn't undone by its publisher being Anthropic.
/// </summary>
public sealed class AppMatcher
{
    /// <summary>
    /// Publishers, from an app's CompanyName, whose desktop apps belong to one service. Keys are
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

    private static readonly HashSet<string> CompanySuffixes = new(StringComparer.Ordinal)
    {
        "inc", "incorporated", "llc", "ltd", "limited", "pbc", "corp", "corporation", "co", "company", "gmbh", "plc",
    };

    private static AppMatcher? cached;

    /// <summary>App key to service; null for a switched-off app.</summary>
    private readonly Dictionary<string, UsageProvider?> rules = new(StringComparer.Ordinal);

    /// <summary>Services with at least one app switched on: only those can match by publisher.</summary>
    private readonly HashSet<UsageProvider> followed = [];

    public AppMatcher(IEnumerable<AppRule> rules)
    {
        foreach (var rule in rules)
        {
            if (rule.Key.Length == 0) continue;
            this.rules[rule.Key] = rule.On ? rule.Provider : null;
            if (rule.On) followed.Add(rule.Provider);
        }
    }

    /// <summary>The built-in apps, all switched on.</summary>
    public static AppMatcher Default { get; } = new(AppMappings.Resolve(new AppSettings()));

    public AppSettings? Source { get; private init; }

    /// <summary>The matcher for these settings, rebuilt only when they change.</summary>
    public static AppMatcher For(AppSettings settings)
    {
        var current = cached;
        if (current is not null && ReferenceEquals(current.Source, settings)) return current;
        current = new AppMatcher(AppMappings.Resolve(settings)) { Source = settings };
        cached = current;
        return current;
    }

    /// <summary>
    /// Whether a rule decides <paramref name="appName"/>: true with the service, or true with null
    /// when that app is switched off; false when no rule names it.
    /// </summary>
    public bool TryMatch(string? appName, out UsageProvider? provider)
    {
        provider = null;
        if (string.IsNullOrWhiteSpace(appName)) return false;
        var name = appName.Trim();
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
        var words = Words(name);
        // The whole name first, then fewer and fewer leading words: "Grok Bot Helper" tries
        // "grokbothelper", then "grokbot", then "grok".
        for (var count = words.Count; count >= 1; count--)
        {
            if (rules.TryGetValue(string.Concat(words.Take(count)), out provider)) return true;
        }
        return false;
    }

    /// <summary>The service of an app, from its process or app name ("Claude", "ChatGPT.exe", "Grok Bot").</summary>
    public UsageProvider? ProviderForApp(string? appName) => TryMatch(appName, out var provider) ? provider : null;

    /// <summary>
    /// The service of a running app, from what the system knows about it: the process name first,
    /// then the executable's product name, then its publisher (Anthropic, Anysphere, SpaceXAI).
    /// </summary>
    public UsageProvider? ProviderForApp(string? processName, string? productName, string? companyName)
    {
        if (TryMatch(processName, out var byProcess)) return byProcess;
        if (TryMatch(productName, out var byProduct)) return byProduct;
        return ProviderForCompany(companyName);
    }

    /// <summary>The service that publishes apps as <paramref name="companyName"/>, while any of its apps is on.</summary>
    public UsageProvider? ProviderForCompany(string? companyName)
    {
        if (string.IsNullOrWhiteSpace(companyName)) return null;
        var words = Words(companyName);
        while (words.Count > 1 && CompanySuffixes.Contains(words[^1])) words.RemoveAt(words.Count - 1);
        var key = string.Concat(words);
        return key.Length > 0 && KnownCompanies.TryGetValue(key, out var provider) && followed.Contains(provider) ? provider : null;
    }

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
}
