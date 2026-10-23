using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// Sample accounts and usage for screenshots, UI tests and trying the app without signing in.
/// Demo mode never contacts any provider.
/// </summary>
public static class DemoData
{
    public static UsageSnapshot Snapshot(UsageProvider provider, DateTimeOffset now)
    {
        if (provider == UsageProvider.ClaudeCode)
        {
            return new UsageSnapshot("Max 20x", true, false,
                new UsageWindow(41, 18_000, 0, now.AddHours(3).AddMinutes(12)),
                new UsageWindow(20, 604_800, 0, now.AddDays(4).AddHours(5)), null, null,
                [
                    new UsageLimit("claude_weekly_scoped_fable", "Fable only", primary: new UsageWindow(38, 604_800, 0, now.AddDays(4).AddHours(5))),
                    new UsageLimit("claude_extra_usage", "Extra usage", primary: new UsageWindow(24, 2_592_000, 0, now.AddDays(18))),
                ], null, null, now);
        }
        if (provider == UsageProvider.Cursor)
        {
            return new UsageSnapshot("Pro", true, false, null, new UsageWindow(57, 604_800, 0, now.AddDays(2).AddHours(9)), null, null,
                [new UsageLimit("cursor_on_demand", "Additional usage ($12.50 of $50)", primary: new UsageWindow(25, 604_800, 0, now.AddDays(2).AddHours(9)))],
                null, null, now);
        }
        return new UsageSnapshot("pro", true, false,
            new UsageWindow(38, 18_000, 0, now.AddHours(2).AddMinutes(15)),
            new UsageWindow(64, 604_800, 0, now.AddDays(3).AddHours(7)), null, 2,
            [
                new UsageLimit("codex_bengalfox-0", "GPT-5.3-Codex-Spark", "codex_bengalfox",
                    primary: new UsageWindow(24, 18_000, 0, now.AddHours(2).AddMinutes(58)),
                    secondary: new UsageWindow(47, 604_800, 0, now.AddDays(3).AddHours(7))),
            ],
            new UsageCredits(true, false, "2500"), null, now);
    }

    public static ResetCreditsSnapshot Credits(DateTimeOffset now) => new(2,
    [
        new RateLimitResetCredit("demo-credit-1", "full", "available", now.AddDays(-9), now.AddDays(5), "Full reset", "Resets your 5-hour and weekly limits."),
        new RateLimitResetCredit("demo-credit-2", "full", "available", now.AddDays(-2), now.AddDays(26), "Full reset", "Resets your 5-hour and weekly limits."),
        new RateLimitResetCredit("demo-credit-0", "full", "redeemed", now.AddDays(-30), now.AddDays(-1), "Full reset", ""),
    ], now);

    public static AuthTokens Account(UsageProvider provider, DateTimeOffset now) => new("demo-access", "demo-refresh", "",
        now.AddYears(1), "demo", provider == UsageProvider.Cursor ? "alex@example.dev" : "alex@example.com");
}

/// <summary>A provider service that serves <see cref="DemoData"/> and never touches the network.</summary>
public sealed class DemoProviderService(UsageProvider provider, ProviderContext context) : ProviderService(provider, context)
{
    public override bool SupportsResetCredits => Provider == UsageProvider.Codex;

    protected override async Task<AuthTokens> RunSignInAsync(Func<Uri, Task> openBrowser, CancellationToken cancellationToken)
    {
        await Task.Delay(600, cancellationToken).ConfigureAwait(false);
        return DemoData.Account(Provider, Now);
    }

    protected override async Task<UsageSnapshot> FetchAsync(AuthTokens tokens)
    {
        await Task.Delay(250).ConfigureAwait(false);
        if (Provider == UsageProvider.Codex)
            await Context.Cache.UpdateAsync(Provider.Id, e => e with { Credits = e.Credits ?? DemoData.Credits(Now) }).ConfigureAwait(false);
        var cached = (await CachedAsync().ConfigureAwait(false))?.Credits;
        var snapshot = DemoData.Snapshot(Provider, Now);
        return cached is null ? snapshot : snapshot.WithResetCredits(cached.AvailableCount);
    }

    /// <summary>Demo redemption: spends a credit and clears the 5-hour and weekly windows.</summary>
    public async Task<ResetConsumeResult> ConsumeResetAsync(string? creditId)
    {
        var entry = await CachedAsync().ConfigureAwait(false);
        var credits = entry?.Credits ?? DemoData.Credits(Now);
        var target = creditId is null ? credits.NextExpiringAvailable(Now) : credits.Credits.FirstOrDefault(c => c.Id == creditId && c.IsAvailable);
        if (target is null) return new ResetConsumeResult(ResetConsumeOutcome.NoCredit, 0);
        var remaining = credits.Credits.Select(c => c.Id == target.Id
            ? new RateLimitResetCredit(c.Id, c.ResetType, "redeemed", c.GrantedAt, c.ExpiresAt, c.Title, c.Description) : c).ToList();
        var updatedCredits = new ResetCreditsSnapshot(remaining.Count(c => c.IsAvailable), remaining, Now);
        var usage = entry?.Usage ?? DemoData.Snapshot(Provider, Now);
        usage = usage with
        {
            FiveHour = usage.FiveHour is null ? null : new UsageWindow(0, usage.FiveHour.WindowSeconds, 0, Now.AddHours(5)),
            Weekly = usage.Weekly is null ? null : new UsageWindow(0, usage.Weekly.WindowSeconds, 0, Now.AddDays(7)),
            ResetCreditsAvailable = updatedCredits.AvailableCount,
            FetchedAt = Now,
        };
        await Context.Cache.UpdateAsync(Provider.Id, e => e with { Credits = updatedCredits, Usage = usage, UpdatedAt = Now }).ConfigureAwait(false);
        return new ResetConsumeResult(ResetConsumeOutcome.Reset, 2);
    }
}
