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
        Assert.StartsWith("Codex: ", lines[0]);
        Assert.Contains("% left", lines[0]);
    }

    [Fact]
    public async Task SummarySkipsSignedOutProviders()
    {
        var state = await DemoStateAsync(UsageProvider.All.Select(p => p.Id));
        Assert.Empty(TrayIcon.SummaryLines(state));
    }

    [Fact]
    public void BadgeIsStraightAlphaArgbInNetworkOrder()
    {
        var bytes = TrayIcon.ToArgb(TrayIcon.Draw(62, 62, 32), 32);
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
        var bytes = TrayIcon.ToArgb(TrayIcon.Draw(7, 7, 32), 32);
        var pixel = (16 * 32 + 3) * 4;
        Assert.Equal(0xC0, bytes[pixel + 1]);
    }
}
