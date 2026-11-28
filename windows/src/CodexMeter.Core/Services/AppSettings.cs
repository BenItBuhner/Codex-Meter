using System.Text.Json;
using CodexMeter.Core.Formatting;

namespace CodexMeter.Core.Services;

public enum AppTheme { System, Light, Dark }

public enum BackdropKind { Mica, MicaAlt, Acrylic }

/// <summary>Everything the user can change in Settings. Stored as JSON next to the cache.</summary>
public sealed record AppSettings
{
    public AppTheme Theme { get; init; } = AppTheme.System;
    public BackdropKind Backdrop { get; init; } = BackdropKind.Mica;
    /// <summary>Provider ids shown in the navigation pane. Empty means never chosen (Codex + signed in).</summary>
    public List<string> EnabledProviders { get; init; } = [];
    public List<string> ProviderOrder { get; init; } = [];
    public string SelectedProvider { get; init; } = "codex";
    public UsageDisplayMode DisplayMode { get; init; } = UsageDisplayMode.Remaining;
    public ResetDisplayMode ResetDisplay { get; init; } = ResetDisplayMode.Both;
    public bool Use24HourClock { get; init; }
    public bool ShowPace { get; init; } = true;
    /// <summary>Background refresh spacing in minutes (providers may enforce a longer minimum).</summary>
    public int RefreshMinutes { get; init; } = 15;
    public bool ShowTrayIcon { get; init; } = true;
    /// <summary>Live usage: the provider the tray icon follows, or empty for automatic (first in the sidebar).</summary>
    public string LiveProvider { get; init; } = LiveUsage.AutomaticProvider;
    /// <summary>Live usage: show the provider of the AI app in front (ChatGPT, Claude, Cursor, ...) while it's in front.</summary>
    public bool LiveFollowApps { get; init; } = true;
    /// <summary>"While using AI apps": apps the user added, or built-in ones they pointed at another service. See <see cref="AppMappings"/>.</summary>
    public List<AppMapping> LiveAppsAdded { get; init; } = [];
    /// <summary>"While using AI apps": apps switched off, by <see cref="AppMappings.Key"/>.</summary>
    public List<string> LiveAppsOff { get; init; } = [];
    /// <summary>"While using AI apps": built-in apps the user removed, by <see cref="AppMappings.Key"/>.</summary>
    public List<string> LiveAppsRemoved { get; init; } = [];
    /// <summary>Live usage: 5-hour, weekly, or whichever has less left.</summary>
    public LiveWindowChoice LiveWindow { get; init; } = LiveWindowChoice.Auto;
    public LiveIconStyle LiveIconStyle { get; init; } = LiveIconStyle.Ring;
    public bool CloseToTray { get; init; } = true;
    public bool StartWithWindows { get; init; }
    public bool StartMinimized { get; init; }
    public bool NotifyResets { get; init; } = true;
    public bool NotifyLow { get; init; } = true;
    public int LowThreshold { get; init; } = 15;
    /// <summary>Notifications → Low allowance → Alert for: both limits, or only the 5-hour or weekly one.</summary>
    public Models.LowUsageAutoUseMetric LowAlertMetric { get; init; } = Models.LowUsageAutoUseMetric.Both;
    /// <summary>Notifications → New reset credits.</summary>
    public bool NotifyNewResets { get; init; } = true;
    /// <summary>Notifications → Expiring reset credits: a reminder a day before one expires unused.</summary>
    public bool NotifyExpiringResets { get; init; } = true;
    public bool OnboardingDone { get; init; }
    /// <summary>The release whose What's new page was last shown (<see cref="WhatsNew.Release"/>).</summary>
    public string? WhatsNewSeen { get; init; }

    /// <summary>Edit dashboard: card order per provider id (missing means default order).</summary>
    public Dictionary<string, List<string>> SectionOrder { get; init; } = [];
    /// <summary>Edit dashboard: hidden cards per provider id.</summary>
    public Dictionary<string, List<string>> HiddenSections { get; init; } = [];
    /// <summary>Usage history highlights switched away from their default.</summary>
    public List<string> HistoryOverrides { get; init; } = [];

    /// <summary>Use a Codex reset credit shortly before it expires instead of losing it.</summary>
    public bool AutoUseExpiringResets { get; init; }
    public int AutoUseLeadMinutes { get; init; } = Models.CreditExpiryAutoApply.DefaultLeadMinutes;
    /// <summary>Use a Codex reset credit when a window runs low.</summary>
    public bool AutoUseWhenLow { get; init; }
    public Models.LowUsageAutoUseMetric AutoUseMetric { get; init; } = Models.LowUsageAutoUseMetric.Both;
    public int AutoUseThreshold { get; init; } = Models.LowUsageAutoUse.DefaultThreshold;
    /// <summary>The window the low-usage rule last fired for, so it fires once per window.</summary>
    public string? AutoUseLastToken { get; init; }
    /// <summary>Running-low use waits when the 5-hour limit refills on its own within this many minutes (0 never waits).</summary>
    public int AutoUseFiveHourWaitMinutes { get; init; } = Models.LowUsageAutoUse.DefaultWaitMinutes;
    /// <summary>The same for the weekly (or monthly) limit.</summary>
    public int AutoUseWeeklyWaitMinutes { get; init; } = Models.LowUsageAutoUse.DefaultWaitMinutes;
    /// <summary>The last running-low use held back for a refill, shown until that limit refills.</summary>
    public Models.RefillWait? AutoUseRefillWait { get; init; }

    /// <summary>Settings → Updates: channel, automatic checks and notifications (as on Android).</summary>
    public Updates.UpdatePreferences Updates { get; init; } = new();
    /// <summary>Notify when a limit that was used up has some left again ("usage is back"). On by default, as on Android.</summary>
    public bool NotifyUsageBack { get; init; } = true;
    /// <summary>Notify when a limit refills well before its reset time ("surprise refills").</summary>
    public bool NotifySurpriseRefills { get; init; } = true;
    /// <summary>Used-up limits waiting for "usage is back": "provider:window" to the reset time (unix seconds, or -1 when unknown).</summary>
    public Dictionary<string, long> UsedUpLimits { get; init; } = [];
    /// <summary>Notify when a new model from a maker you use is listed (Android: Notifications → New models).</summary>
    public bool NotifyNewModels { get; init; } = true;
    /// <summary>Show the Priority page first in the sidebar (Android: the Priority tab).</summary>
    public bool ShowPriority { get; init; } = true;
    /// <summary>
    /// Service status: official status pages (OpenAI, Anthropic, Cursor, xAI) and public OpenRouter
    /// speed figures, shown on Priority and as a card on a provider's page when something is wrong.
    /// </summary>
    public bool ShowServiceStatus { get; init; } = true;
    /// <summary>Dashboard → Model slowdown warnings: public OpenRouter speed figures for your providers' newest models.</summary>
    public bool ShowSlowdowns { get; init; } = true;
    /// <summary>Settings → Reset credits → Claude Code saved resets: the automatic-use rules.</summary>
    public ClaudeResetRules ClaudeResets { get; init; } = new();
    /// <summary>Privacy &amp; data: keep an on-device, privacy-filtered diagnostic log (off by default).</summary>
    public bool DiagnosticLogging { get; init; }
    /// <summary>Dashboard → Fast-usage warnings: how readily "at this pace, runs out" appears.</summary>
    public Formatting.PaceSensitivity PaceSensitivity { get; init; }

    public List<string> SectionOrderFor(string providerId) => SectionOrder.GetValueOrDefault(providerId) ?? [];
    public List<string> HiddenSectionsFor(string providerId) => HiddenSections.GetValueOrDefault(providerId) ?? [];

    public AppSettings WithSections(string providerId, List<string> order, List<string> hidden) => this with
    {
        SectionOrder = new Dictionary<string, List<string>>(SectionOrder) { [providerId] = order },
        HiddenSections = new Dictionary<string, List<string>>(HiddenSections) { [providerId] = hidden },
    };

    public static readonly int[] RefreshChoices = [5, 15, 30, 60];
    /// <summary>Low allowance → Alert when remaining reaches.</summary>
    public static readonly int[] LowThresholdChoices = [10, 15, 25, 50];
}

/// <summary>Automatic use of Claude Code saved resets, as on Android.</summary>
public sealed record ClaudeResetRules
{
    public bool AutoUseExpiring { get; init; }
    public int LeadMinutes { get; init; } = Models.CreditExpiryAutoApply.DefaultLeadMinutes;
    public bool AutoUseWhenLow { get; init; }
    public Models.LowUsageAutoUseMetric Metric { get; init; } = Models.LowUsageAutoUseMetric.Both;
    public int Threshold { get; init; } = Models.LowUsageAutoUse.DefaultThreshold;
    /// <summary>The grant the expiry rule last tried, so it tries once.</summary>
    public string? LastExpiryKey { get; init; }
    /// <summary>The window the low-usage rule last fired for, so it fires once per window.</summary>
    public string? LastLowToken { get; init; }
    /// <summary>Running-low use waits when the 5-hour limit refills on its own within this many minutes (0 never waits).</summary>
    public int FiveHourWaitMinutes { get; init; } = Models.LowUsageAutoUse.DefaultWaitMinutes;
    /// <summary>The same for the weekly limit.</summary>
    public int WeeklyWaitMinutes { get; init; } = Models.LowUsageAutoUse.DefaultWaitMinutes;
    /// <summary>The last running-low use held back for a refill, shown until that limit refills.</summary>
    public Models.RefillWait? RefillWait { get; init; }
}

public sealed class SettingsStore(string? path)
{
    private AppSettings? current;

    public AppSettings Current => current ??= Load();

    public event EventHandler<AppSettings>? Changed;

    private AppSettings Load()
    {
        if (path is null || !File.Exists(path)) return new AppSettings();
        try
        {
            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllBytes(path), Storage.Options) ?? new AppSettings();
        }
        catch (JsonException)
        {
            return new AppSettings();
        }
    }

    public void Update(Func<AppSettings, AppSettings> change)
    {
        var updated = change(Current);
        if (updated == current) return;
        current = updated;
        if (path is not null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temp = path + ".tmp";
            File.WriteAllBytes(temp, JsonSerializer.SerializeToUtf8Bytes(updated, Storage.Options));
            File.Move(temp, path, overwrite: true);
        }
        Changed?.Invoke(this, updated);
    }
}
