using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>"While using AI apps" with the user's own app list, end to end through saved settings.</summary>
public class AppMappingsTests
{
    private static readonly UsageProvider Claude = UsageProvider.ClaudeCode, Cursor = UsageProvider.Cursor, Grok = UsageProvider.Grok;
    private static readonly IReadOnlyList<UsageProvider> Candidates = [Claude, Cursor, Grok, UsageProvider.Codex];

    /// <summary>What the tray shows while <paramref name="process"/> is in front, with the settings saved at <paramref name="path"/>.</summary>
    private static UsageProvider? TrayShows(string path, string process, string? product = null, string? company = null)
    {
        var settings = new SettingsStore(path).Current;
        var front = AppMatcher.For(settings).ProviderForApp(process, product, company);
        return LiveUsage.ChooseProvider("codex", front, Candidates);
    }

    private static void Change(string path, Func<AppSettings, AppSettings> change) => new SettingsStore(path).Update(change);

    [Fact]
    public void AddedAppCountsForItsServiceAndASwitchedOffOneDoesNot()
    {
        var path = Path.Combine(Path.GetTempPath(), $"cm-apps-{Guid.NewGuid():N}.json");
        try
        {
            // Out of the box an unknown app leaves the chosen service, and Grok Bot shows Grok.
            Assert.Equal(UsageProvider.Codex, TrayShows(path, "Zed"));
            Assert.Equal(Grok, TrayShows(path, "Grok Bot", "Grok Bot", "SpaceXAI"));

            // A brand-new app, and the built-in Grok Bot pointed at Cursor.
            Change(path, s => AppMappings.Set(s, "Zed", Claude));
            Change(path, s => AppMappings.Set(s, "Grok Bot", Cursor));
            Assert.Equal(Claude, TrayShows(path, "zed.exe"));
            Assert.Equal(Cursor, TrayShows(path, "Grok Bot", "Grok Bot", "SpaceXAI"));
            Assert.Equal(Cursor, TrayShows(path, "GrokBot.exe"));
            // Plain "Grok" keeps its own built-in mapping.
            Assert.Equal(Grok, TrayShows(path, "Grok"));

            // Switched off: the app no longer counts, and its publisher doesn't bring it back.
            Change(path, s => AppMappings.SetOn(s, "Grok Bot", false));
            Change(path, s => AppMappings.SetOn(s, "Zed", false));
            Assert.Equal(UsageProvider.Codex, TrayShows(path, "Grok Bot", "Grok Bot", "SpaceXAI"));
            Assert.Equal(UsageProvider.Codex, TrayShows(path, "Zed"));

            // Back on, after a fresh load of the file.
            Change(path, s => AppMappings.SetOn(s, "Zed", true));
            Assert.Equal(Claude, TrayShows(path, "Zed"));

            var rows = AppMappings.Resolve(new SettingsStore(path).Current);
            var grokBot = Assert.Single(rows, r => r.App == "Grok Bot");
            Assert.Equal((Cursor, false, true, true), (grokBot.Provider, grokBot.On, grokBot.BuiltIn, grokBot.Edited));
            var zed = Assert.Single(rows, r => r.App == "Zed");
            Assert.Equal((Claude, true, false), (zed.Provider, zed.On, zed.BuiltIn));
            Assert.Equal("Zed", rows[^1].App);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void BuiltInAppsCanBeRemovedRenamedAndRestored()
    {
        var path = Path.Combine(Path.GetTempPath(), $"cm-apps-{Guid.NewGuid():N}.json");
        try
        {
            Assert.Equal(Claude, TrayShows(path, "Claude"));

            // Removing the built-in Claude also stops matching it by its publisher, Anthropic.
            Change(path, s => AppMappings.Remove(s, "Claude"));
            Assert.Equal(UsageProvider.Codex, TrayShows(path, "Claude"));
            Assert.Equal(UsageProvider.Codex, TrayShows(path, "SomeHost", null, "Anthropic, PBC"));
            Assert.DoesNotContain(AppMappings.Resolve(new SettingsStore(path).Current), r => r.App == "Claude");

            // Renaming a row: "Cursor" becomes "Cursor Nightly", still on Cursor.
            Change(path, s => AppMappings.Set(s, "Cursor Nightly", Cursor, replacing: "Cursor"));
            Assert.Equal(Cursor, TrayShows(path, "Cursor Nightly"));
            Assert.Equal(UsageProvider.Codex, TrayShows(path, "Cursor"));

            // Restore defaults brings every built-in app back and drops the rest.
            Change(path, AppMappings.RestoreDefaults);
            var settings = new SettingsStore(path).Current;
            Assert.False(AppMappings.IsCustomized(settings));
            Assert.Equal(AppMappings.Defaults.Select(d => d.App), AppMappings.Resolve(settings).Select(r => r.App));
            Assert.Equal(Claude, TrayShows(path, "Claude"));
            Assert.Equal(Cursor, TrayShows(path, "Cursor"));
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void PointingABuiltInAppBackAtItsServiceClearsTheEdit()
    {
        var settings = AppMappings.Set(new AppSettings(), "Grok Bot", Cursor);
        settings = AppMappings.Set(settings, "grok bot", Grok);
        Assert.False(AppMappings.IsCustomized(settings));
        Assert.Equal(Grok, AppMatcher.For(settings).ProviderForApp("Grok Bot"));
    }

    [Fact]
    public void LongestLeadingWordsWinAndUnknownServicesAreSkipped()
    {
        var settings = AppMappings.Set(new AppSettings(), "Grok Bot", Cursor) with
        {
            LiveAppsAdded = [new("Grok Bot", "cursor"), new("Old Tool", "no_such_service")],
        };
        var matcher = AppMatcher.For(settings);
        Assert.Equal(Cursor, matcher.ProviderForApp("Grok Bot Helper"));
        Assert.Equal(Grok, matcher.ProviderForApp("Grok Desktop"));
        Assert.Null(matcher.ProviderForApp("Old Tool"));
        Assert.DoesNotContain(AppMappings.Resolve(settings), r => r.App == "Old Tool");
        Assert.Same(matcher, AppMatcher.For(settings));
    }

    [Fact]
    public void BlankNamesAreIgnored()
    {
        var settings = AppMappings.Set(new AppSettings(), "  ", Cursor);
        Assert.False(AppMappings.IsCustomized(settings));
        Assert.Null(AppMatcher.For(settings).ProviderForApp(""));
    }
}
