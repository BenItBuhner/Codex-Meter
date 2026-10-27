using System.Security.Cryptography;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

public class ProviderSelectionTests
{
    private static readonly UsageProvider Codex = UsageProvider.Codex, Claude = UsageProvider.ClaudeCode, Cursor = UsageProvider.Cursor;

    [Fact]
    public void IdsAreSharedWithThePhoneApps()
    {
        Assert.Equal(["codex", "claude_code", "cursor"], UsageProvider.All.Select(p => p.Id));
        Assert.Equal(Claude, UsageProvider.Resolve(" CLAUDE_CODE "));
        Assert.Equal(Codex, UsageProvider.Resolve("unknown"));
        Assert.Equal(Codex, UsageProvider.Resolve(null));
        Assert.Equal("Sign in with Claude", Claude.SignInLabel);
    }

    [Fact]
    public void EnabledOrderAndSelection()
    {
        Assert.Equal([Codex], UsageProvider.EnabledFromIds(null, new HashSet<UsageProvider>()));
        Assert.Equal([Codex, Cursor], UsageProvider.EnabledFromIds([], new HashSet<UsageProvider> { Cursor }));
        Assert.Equal([Claude, Cursor], UsageProvider.EnabledFromIds(["cursor", "claude_code", "bogus"], new HashSet<UsageProvider>()));
        Assert.Equal([Cursor, Codex, Claude], UsageProvider.OrderFromIds(["cursor", "cursor", "nope"]));
        Assert.Equal([Codex], UsageProvider.WithEnabled([Codex], Codex, false)); // Last one stays on.
        Assert.Equal([Codex, Cursor], UsageProvider.WithEnabled([Codex], Cursor, true));
        Assert.Equal(Cursor, UsageProvider.VisibleSelection(Claude, [Cursor, Codex]));
        Assert.Equal(Claude, UsageProvider.VisibleSelection(Claude, [Claude]));
    }
}

public class FormattingTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 21, 9, 0, 0, TimeSpan.Zero);

    [Fact]
    public void PercentAndPlanText()
    {
        var window = new UsageWindow(38, 18_000, 0, Now.AddHours(2).AddMinutes(15));
        Assert.Equal("62% left", UsageFormat.Percent(window, UsageDisplayMode.Remaining));
        Assert.Equal("38% used", UsageFormat.Percent(window, UsageDisplayMode.Used));
        Assert.Equal("38% used", UsageFormat.Complement(window, UsageDisplayMode.Remaining));
        Assert.Equal("—", UsageFormat.Percent(null, UsageDisplayMode.Used, compact: true));
        Assert.Equal("Pro 20x", UsageFormat.PlanLabel("pro"));
        Assert.Equal("Pro 5x", UsageFormat.PlanLabel("pro_lite"));
        Assert.Equal("Max 20x", UsageFormat.PlanLabel("Max 20x"));
    }

    [Fact]
    public void ResetText()
    {
        var utc = TimeZoneInfo.Utc;
        var window = new UsageWindow(38, 18_000, 0, Now.AddHours(2).AddMinutes(15));
        Assert.Equal("Resets in 2h 15m", UsageFormat.Reset(window, ResetDisplayMode.Relative, Now, Now, zone: utc));
        Assert.Equal("Resets today at 11:15 AM (in 2h 15m)", UsageFormat.Reset(window, ResetDisplayMode.Both, Now, Now, zone: utc));
        Assert.Equal("Resets tomorrow at 09:30", UsageFormat.ResetText(Now.AddDays(1).AddMinutes(30), ResetDisplayMode.Absolute, Now, true, utc));
        Assert.Equal("Resets Sat, Oct 24 at 4:00 PM", UsageFormat.ResetText(Now.AddDays(3).AddHours(7), ResetDisplayMode.Absolute, Now, false, utc));
        Assert.Equal("in 3d 7h", UsageFormat.Relative(Now.AddDays(3).AddHours(7), Now));
        Assert.Equal("", UsageFormat.Reset(new UsageWindow(0, 18_000), ResetDisplayMode.Both, Now, Now));
        Assert.Equal("Resets in 10m", UsageFormat.Reset(new UsageWindow(5, 18_000, 600), ResetDisplayMode.Relative, Now, Now));
    }

    [Fact]
    public void UpdatedWindowNamesAndCredits()
    {
        Assert.Equal("Updated just now", UsageFormat.Updated(Now, Now.AddSeconds(30)));
        Assert.Equal("Updated 5m ago", UsageFormat.Updated(Now, Now.AddMinutes(5)));
        Assert.Equal("Updated 2h ago", UsageFormat.Updated(Now, Now.AddHours(2)));
        Assert.Equal("Not updated yet", UsageFormat.Updated(null, Now));
        Assert.Equal("5-hour", UsageFormat.WindowName(18_000));
        Assert.Equal("Weekly", UsageFormat.WindowName(604_800));
        Assert.Equal("Monthly", UsageFormat.WindowName(2_592_000));
        Assert.Equal("25,000", UsageFormat.CreditAmount(25_000m));
        Assert.Equal("2,500.5", UsageFormat.CreditAmount(2500.5m));
        var limit = new SpendControlLimit("", "25000", "8000", null, 32);
        Assert.Equal("8,000 of 25,000 credits used", UsageFormat.SpendControlUsage(limit));
        Assert.Equal("17,000 credits remaining", UsageFormat.SpendControlRemaining(limit));
    }

    [Fact]
    public void PaceEstimates()
    {
        // 80% used one hour into a five-hour window: runs out in 15 minutes, well before reset.
        var fast = new UsageWindow(80, 18_000, 0, Now.AddHours(4));
        var pace = UsagePace.Assess(fast, Now, Now);
        Assert.True(pace.IsAccelerated);
        Assert.Equal(TimeSpan.FromMinutes(15), pace.EstimatedRemaining);
        Assert.Equal("At this pace, runs out in 15m", UsagePace.Describe(fast, Now, Now));
        var steady = new UsageWindow(20, 18_000, 0, Now.AddHours(4));
        Assert.Equal("On pace to last until reset", UsagePace.Describe(steady, Now, Now));
        Assert.False(UsagePace.Assess(new UsageWindow(0, 18_000, 0, Now.AddHours(4)), Now, Now).IsAvailable);
        Assert.False(UsagePace.Assess(new UsageWindow(50, 18_000), Now, Now).IsAvailable);
    }
}

public class AlertTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.FromUnixTimeSeconds(1_900_000_000);

    private static UsageSnapshot Snap(int fiveHourUsed, DateTimeOffset at, DateTimeOffset reset) =>
        new("plus", true, false, new UsageWindow(fiveHourUsed, 18_000, 0, reset), null, null, null, null, null, null, at);

    [Fact]
    public void LowExhaustedAndReset()
    {
        var reset = Now.AddHours(1);
        Assert.Empty(UsageAlerts.Evaluate(UsageProvider.Codex, null, Snap(90, Now, reset), 15, true, true));
        var low = UsageAlerts.Evaluate(UsageProvider.Codex, Snap(80, Now, reset), Snap(88, Now.AddMinutes(5), reset), 15, true, true);
        Assert.Equal(UsageAlertKind.Low, Assert.Single(low).Kind);
        Assert.Equal("12% of your 5-hour allowance is left.", low[0].Body);
        var exhausted = UsageAlerts.Evaluate(UsageProvider.Codex, Snap(95, Now, reset), Snap(100, Now.AddMinutes(5), reset), 15, true, true);
        Assert.Equal(UsageAlertKind.Exhausted, Assert.Single(exhausted).Kind);
        var afterReset = UsageAlerts.Evaluate(UsageProvider.Codex, Snap(100, Now, reset), Snap(2, reset.AddMinutes(1), reset.AddHours(5)), 15, true, true);
        Assert.Equal(UsageAlertKind.Reset, Assert.Single(afterReset).Kind);
        Assert.Equal("Codex 5-hour limit reset", afterReset[0].Title);
        Assert.Empty(UsageAlerts.Evaluate(UsageProvider.Codex, Snap(100, Now, reset), Snap(2, reset.AddMinutes(1), reset.AddHours(5)), 15, false, false));
        Assert.Empty(UsageAlerts.Evaluate(UsageProvider.Codex, Snap(88, Now, reset), Snap(90, Now.AddMinutes(5), reset), 15, true, true));
    }
}

public class StorageTests
{
    private sealed class XorProtector : IDataProtector
    {
        public byte[] Protect(byte[] data) => data.Select(b => (byte)(b ^ 0x5A)).ToArray();
        public byte[] Unprotect(byte[] data) => data[0] == (byte)('{' ^ 0x5A) ? Protect(data) : throw new CryptographicException("wrong key");
    }

    [Fact]
    public async Task TokensRoundTripEncryptedAndCorruptionMeansSignedOut()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var store = new FileTokenStore(dir, new XorProtector());
        var tokens = new AuthTokens("a", "r", "i", DateTimeOffset.FromUnixTimeSeconds(2_000_000_000), "acct", "me@example.com");
        await store.SaveAsync("codex", tokens);
        Assert.DoesNotContain("me@example.com", File.ReadAllText(Path.Combine(dir, "tokens-codex.bin")));
        Assert.Equal(tokens, await store.LoadAsync("codex"));
        File.WriteAllText(Path.Combine(dir, "tokens-codex.bin"), "garbage");
        Assert.Null(await store.LoadAsync("codex"));
        await store.DeleteAsync("codex");
        Assert.Null(await store.LoadAsync("codex"));
    }

    [Fact]
    public async Task CacheAndSettingsPersist()
    {
        var dir = Directory.CreateTempSubdirectory().FullName;
        var now = DateTimeOffset.FromUnixTimeSeconds(1_900_000_000);
        await new ProviderCacheStore(dir).UpdateAsync("cursor", e => e with { Usage = DemoData.Snapshot(UsageProvider.Cursor, now), UpdatedAt = now });
        var reloaded = await new ProviderCacheStore(dir).LoadAsync("cursor");
        Assert.Equal(57, reloaded!.Usage!.Weekly!.UsedPercent);
        Assert.Equal("Additional usage ($12.50 of $50)", reloaded.Usage.AdditionalLimits[0].Name);

        var path = Path.Combine(dir, "settings.json");
        var settings = new SettingsStore(path);
        var changes = 0;
        settings.Changed += (_, _) => changes++;
        settings.Update(s => s with { Theme = AppTheme.Dark, EnabledProviders = ["codex", "cursor"], RefreshMinutes = 30 });
        var again = new SettingsStore(path).Current;
        Assert.Equal(AppTheme.Dark, again.Theme);
        Assert.Equal(["codex", "cursor"], again.EnabledProviders);
        Assert.Equal(30, again.RefreshMinutes);
        Assert.Equal(1, changes);
        File.WriteAllText(path, "{broken");
        Assert.Equal(AppTheme.System, new SettingsStore(path).Current.Theme);
    }

    [Fact]
    public async Task DemoResetSpendsACredit()
    {
        var hub = await ProviderHub.DemoAsync(new FakeHttp().Context());
        await hub[UsageProvider.Codex].RefreshAsync();
        var result = await hub.ConsumeResetAsync(null);
        Assert.True(result.Applied);
        var cached = await hub[UsageProvider.Codex].CachedAsync();
        Assert.Equal(1, cached!.Credits!.AvailableCount);
        Assert.Equal(0, cached.Usage!.FiveHour!.UsedPercent);
    }
}
