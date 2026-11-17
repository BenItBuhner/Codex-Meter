using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Tests;

public class TrayTests
{
    // Gir.Core maps its library names ("cairo-graphics") to the system's sonames on initialization.
    static TrayTests() => Cairo.Module.Initialize();

    private static async Task<AppState> DemoStateAsync(IEnumerable<string>? signedOut = null)
    {
        var context = new ProviderContext(ProviderHttp.CreateClient(), new MemoryTokenStore(), new ProviderCacheStore(null));
        var hub = await ProviderHub.DemoAsync(context, signedOut);
        var state = new AppState(hub, new SettingsStore(null), new UsageHistoryStore(null));
        await state.InitializeAsync();
        foreach (var provider in hub.Providers) await state.RefreshAsync(provider, force: true);
        return state;
    }

    [Fact]
    public async Task SummaryListsEverySignedInProvider()
    {
        var state = await DemoStateAsync();
        state.Settings.Update(s => s with { EnabledProviders = ["codex", "claude_code", "cursor"] });
        var lines = TrayIcon.SummaryLines(state);
        Assert.Equal(3, lines.Count);
        // The live value leads, then every other service.
        Assert.StartsWith("Codex · ", lines[0]);
        Assert.Contains("% left", lines[0]);
        Assert.StartsWith("Claude Code: ", lines[1]);
    }

    [Fact]
    public async Task SummarySkipsSignedOutProviders()
    {
        var state = await DemoStateAsync(UsageProvider.All.Select(p => p.Id));
        Assert.Empty(TrayIcon.SummaryLines(state));
    }

    private static readonly DateTimeOffset Now = DateTimeOffset.UtcNow;

    private static LiveReading Reading(int used) =>
        new(UsageProvider.Codex, new UsageWindow(used, 18_000, resetAt: Now.AddHours(2)), "5-hour", Now);

    [Fact]
    public async Task TrayFollowsTheChosenService()
    {
        var state = await DemoStateAsync();
        state.Settings.Update(s => s with { EnabledProviders = ["codex", "claude_code", "cursor"], LiveProvider = "cursor" });
        Assert.Equal(UsageProvider.Cursor, TrayIcon.Current(state)?.Provider);
        Assert.StartsWith("Cursor · ", TrayIcon.SummaryLines(state)[0]);
        state.Settings.Update(s => s with { LiveProvider = "" });
        Assert.Equal(UsageProvider.Codex, TrayIcon.Current(state)?.Provider);
    }

    [Fact]
    public async Task TrayFollowsTheAppInFrontByTheUsersAppList()
    {
        var state = await DemoStateAsync();
        state.Settings.Update(s => s with { EnabledProviders = ["codex", "claude_code", "cursor"] });
        // What X11 reports for the SpaceXSI Grok app: process "Grok Bot", window class "Grok Bot".
        var grokBot = new FrontWindow(4242, "Grok Bot", "grok-bot", "Grok Bot", "grok-bot");
        var zed = new FrontWindow(4343, "zed-editor", "zed-editor", "dev.zed.Zed", "zed");
        UsageProvider? Shows(FrontWindow front) => TrayIcon.Current(state, TrayIcon.FrontApp(state, front))?.Provider;

        // Grok isn't connected, so the built-in Grok Bot → Grok leaves the icon on Codex.
        Assert.Equal(UsageProvider.Codex, Shows(grokBot));
        Assert.Equal(UsageProvider.Codex, Shows(zed));

        state.Settings.Update(s => AppMappings.Set(s, "Grok Bot", UsageProvider.Cursor));
        state.Settings.Update(s => AppMappings.Set(s, "zed", UsageProvider.ClaudeCode));
        Assert.Equal(UsageProvider.Cursor, Shows(grokBot));
        Assert.StartsWith("Cursor · ", TrayIcon.SummaryLines(state, TrayIcon.FrontApp(state, grokBot))[0]);
        // Matched by the window instance when the process name doesn't match.
        Assert.Equal(UsageProvider.ClaudeCode, Shows(zed));

        state.Settings.Update(s => AppMappings.SetOn(s, "Grok Bot", false));
        Assert.Equal(UsageProvider.Codex, Shows(grokBot));
        Assert.Equal(UsageProvider.ClaudeCode, Shows(zed));

        state.Settings.Update(s => s with { LiveFollowApps = false });
        Assert.Equal(UsageProvider.Codex, Shows(zed));
    }

    [Fact]
    public void BadgeIsStraightAlphaArgbInNetworkOrder()
    {
        var bytes = TrayIcon.ToArgb(TrayIcon.Draw(Reading(38), LiveIconStyle.Number, UsageDisplayMode.Remaining, 32, Now), 32);
        Assert.Equal(32 * 32 * 4, bytes.Length);
        // The center of the badge is opaque; the corner outside the rounded rectangle is transparent.
        var center = (16 * 32 + 3) * 4;
        Assert.Equal(255, bytes[center]);
        Assert.Equal(0, bytes[0]);
        // Normal level: the indigo fill (0x53, 0x45, 0xF3) at a left-edge pixel.
        Assert.Equal(0x53, bytes[center + 1]);
        Assert.Equal(0x45, bytes[center + 2]);
        Assert.Equal(0xF3, bytes[center + 3]);
    }

    [Fact]
    public void BadgeTurnsRedWhenLow()
    {
        var bytes = TrayIcon.ToArgb(TrayIcon.Draw(Reading(93), LiveIconStyle.Number, UsageDisplayMode.Remaining, 32, Now), 32);
        var pixel = (16 * 32 + 3) * 4;
        Assert.Equal(0xC0, bytes[pixel + 1]);
    }

    [Fact]
    public void RingFillsWithWhatIsLeft()
    {
        // 25% left: the arc covers the top-right quarter only.
        var bytes = TrayIcon.ToArgb(TrayIcon.Draw(Reading(75), LiveIconStyle.Ring, UsageDisplayMode.Remaining, 48, Now), 48);
        int Pixel(int x, int y) => (y * 48 + x) * 4;
        // On the ring: the white arc top right vs. the faint track bottom left, read in the blue channel (the amber disc has none).
        var filled = bytes[Pixel(24 + 13, 24 - 13) + 3];
        var track = bytes[Pixel(24 - 13, 24 + 13) + 3];
        Assert.True(filled > track + 40, $"filled {filled} vs track {track}");
        Assert.Equal(0, bytes[Pixel(0, 0)]);
    }
}
