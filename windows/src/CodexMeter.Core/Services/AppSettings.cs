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
    public bool CloseToTray { get; init; } = true;
    public bool StartWithWindows { get; init; }
    public bool StartMinimized { get; init; }
    public bool NotifyResets { get; init; } = true;
    public bool NotifyLow { get; init; } = true;
    public int LowThreshold { get; init; } = 15;
    public bool OnboardingDone { get; init; }

    public static readonly int[] RefreshChoices = [5, 15, 30, 60];
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
