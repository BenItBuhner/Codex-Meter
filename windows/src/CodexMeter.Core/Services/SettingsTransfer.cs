using System.Text.Json;
using System.Text.Json.Nodes;

namespace CodexMeter.Core.Services;

/// <summary>
/// Settings export and import for Windows and Linux (Privacy &amp; data). The file uses the same
/// envelope as the phone apps' transfer file (<c>format</c>, <c>version</c>, <c>sections</c>) with
/// a <c>desktop_settings</c> section, so either desktop app can read the other's file. Unlike the
/// phone file it never contains sign-ins: accounts, tokens and API keys stay on the device that
/// made them, and the file is safe to keep or move. Bookkeeping that only makes sense on the device
/// that wrote it (which limits are waiting for "usage is back", which window an auto-use rule last
/// fired for, the last update check) is left out of the file and kept as it is on import.
/// </summary>
public static class SettingsTransfer
{
    public const string Format = "codex_meter_transfer";
    public const int Version = 1;
    public const string Section = "desktop_settings";
    public const int MaxBytes = 1024 * 1024;
    public const string FileName = "codex-meter-settings.json";

    public const string Note = "Codex Meter settings. No accounts, tokens or API keys are included.";

    /// <summary>The settings as a transfer file.</summary>
    public static string Export(AppSettings settings, string platform, DateTimeOffset now)
    {
        var portable = Portable(settings);
        var root = new JsonObject
        {
            ["format"] = Format,
            ["version"] = Version,
            ["exported_at"] = now.ToUnixTimeMilliseconds(),
            ["platform"] = platform,
            ["note"] = Note,
            ["sections"] = new JsonObject { [Section] = JsonSerializer.SerializeToNode(portable, Storage.Options) },
        };
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    /// <summary>
    /// Reads a transfer file and returns <paramref name="current"/> with the file's settings, keeping
    /// this device's bookkeeping. Throws <see cref="FormatException"/> with a message for the user.
    /// </summary>
    public static AppSettings Import(string? json, AppSettings current)
    {
        if (string.IsNullOrWhiteSpace(json)) throw new FormatException("The file is empty.");
        if (json.Length > MaxBytes) throw new FormatException("The file is too large to be a Codex Meter settings file.");
        JsonObject? root;
        try { root = JsonNode.Parse(json) as JsonObject; }
        catch (JsonException) { throw new FormatException("The file isn't a Codex Meter settings file."); }
        if (root is null || (string?)root["format"] != Format) throw new FormatException("The file isn't a Codex Meter settings file.");
        var version = root["version"] is JsonValue v && v.TryGetValue<int>(out var n) ? n : 0;
        if (version is < 1 or > Version) throw new FormatException("This settings file is from a newer Codex Meter. Update the app and try again.");
        if (root["sections"]?[Section] is not JsonObject section)
            throw new FormatException("This file has no desktop settings. Files exported from the phone apps can only be imported on a phone.");
        AppSettings? imported;
        try { imported = section.Deserialize<AppSettings>(Storage.Options); }
        catch (JsonException) { throw new FormatException("The settings in this file couldn't be read."); }
        if (imported is null) throw new FormatException("The settings in this file couldn't be read.");
        return KeepLocal(Sanitize(imported), current);
    }

    /// <summary>The settings without this device's bookkeeping.</summary>
    public static AppSettings Portable(AppSettings settings) => settings with
    {
        UsedUpLimits = [],
        AutoUseLastToken = null,
        ClaudeResets = settings.ClaudeResets with { LastExpiryKey = null, LastLowToken = null },
        Updates = settings.Updates with { LastCheck = null, LastNotified = null },
        // Whether the app starts with the OS is registered with the OS, not just a setting.
        StartWithWindows = false,
    };

    private static AppSettings KeepLocal(AppSettings imported, AppSettings current) => imported with
    {
        UsedUpLimits = current.UsedUpLimits,
        AutoUseLastToken = current.AutoUseLastToken,
        ClaudeResets = imported.ClaudeResets with { LastExpiryKey = current.ClaudeResets.LastExpiryKey, LastLowToken = current.ClaudeResets.LastLowToken },
        Updates = imported.Updates with { LastCheck = current.Updates.LastCheck, LastNotified = current.Updates.LastNotified },
        StartWithWindows = current.StartWithWindows,
        StartMinimized = current.StartMinimized,
        OnboardingDone = current.OnboardingDone || imported.OnboardingDone,
    };

    /// <summary>Unknown enum numbers and out-of-range values fall back to the defaults.</summary>
    private static AppSettings Sanitize(AppSettings s)
    {
        var defaults = new AppSettings();
        return s with
        {
            Theme = Enum.IsDefined(s.Theme) ? s.Theme : defaults.Theme,
            Backdrop = Enum.IsDefined(s.Backdrop) ? s.Backdrop : defaults.Backdrop,
            DisplayMode = Enum.IsDefined(s.DisplayMode) ? s.DisplayMode : defaults.DisplayMode,
            ResetDisplay = Enum.IsDefined(s.ResetDisplay) ? s.ResetDisplay : defaults.ResetDisplay,
            LiveWindow = Enum.IsDefined(s.LiveWindow) ? s.LiveWindow : defaults.LiveWindow,
            LiveIconStyle = Enum.IsDefined(s.LiveIconStyle) ? s.LiveIconStyle : defaults.LiveIconStyle,
            AutoUseMetric = Enum.IsDefined(s.AutoUseMetric) ? s.AutoUseMetric : defaults.AutoUseMetric,
            RefreshMinutes = AppSettings.RefreshChoices.Contains(s.RefreshMinutes) ? s.RefreshMinutes : defaults.RefreshMinutes,
            LowThreshold = s.LowThreshold is >= 1 and <= 50 ? s.LowThreshold : defaults.LowThreshold,
            EnabledProviders = s.EnabledProviders ?? [],
            ProviderOrder = s.ProviderOrder ?? [],
            SectionOrder = s.SectionOrder ?? [],
            HiddenSections = s.HiddenSections ?? [],
            HistoryOverrides = s.HistoryOverrides ?? [],
            ClaudeResets = s.ClaudeResets ?? new ClaudeResetRules(),
            Updates = s.Updates ?? new Updates.UpdatePreferences(),
        };
    }
}
