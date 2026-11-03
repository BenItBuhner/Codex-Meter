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
        if (!provider.IsBuiltIn) return Added(provider, now);
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

    /// <summary>Plausible windows for an added provider, shaped like what its usage route returns.</summary>
    private static UsageSnapshot Added(UsageProvider provider, DateTimeOffset now)
    {
        const long hour = 3_600, day = 86_400, week = 604_800, month = 2_592_000;
        // Vary the numbers by provider so a dashboard of several doesn't look copied.
        var seed = provider.Id.Aggregate(7, (sum, c) => (sum * 31 + c) % 997);
        UsageWindow Window(int percent, long seconds, TimeSpan resetIn) =>
            new(Math.Clamp(percent + seed % 17, 0, 100), seconds, 0, now + resetIn);
        UsageWindow FiveHour() => Window(31, 5 * hour, TimeSpan.FromMinutes(134));
        UsageWindow Weekly() => Window(22, week, TimeSpan.FromHours(4 * 24 + 6));
        UsageWindow Monthly() => Window(36, month, TimeSpan.FromDays(17));
        UsageSnapshot Make(string plan, UsageWindow? fiveHour, UsageWindow? weekly, UsageWindow? monthly,
            IReadOnlyList<UsageLimit>? extras = null, UsageCredits? credits = null) =>
            new(plan, true, false, fiveHour, weekly, monthly, null, extras ?? [], credits, null, now);

        return provider.Id switch
        {
            "copilot" => Make("Pro", null, null, Monthly(),
                [new UsageLimit("chat", "Chat", primary: Window(12, month, TimeSpan.FromDays(17)))]),
            "factory" => Make("Pro", FiveHour(), Weekly(), Monthly()),
            "cline" or "opencode_go" => Make("Pro", FiveHour(), Weekly(), Monthly()),
            "grok" => Make("SuperGrok", null, null, Monthly()),
            "devin" or "windsurf" => Make("Pro", Window(18, day, TimeSpan.FromHours(9)), Weekly(), null),
            "augment" or "kiro" or "codebuff" => Make("Pro", null, null, Monthly()),
            "minimax" => Make("Plus", FiveHour(), Weekly(), null),
            "zai" => Make("Pro", FiveHour(), Weekly(), null,
                [new UsageLimit("web_search_and_reader", "Web search and reader", primary: Window(8, month, TimeSpan.FromDays(17)))]),
            "amp" => Make("Free", Window(29, day, TimeSpan.FromHours(14)), null, null, credits: new UsageCredits(true, false, "$5.00")),
            "openrouter" => Make("", null, null, Monthly(), credits: new UsageCredits(true, false, "$18.40")),
            "kilo" => Make("", null, null, null, credits: new UsageCredits(true, false, "$12.75")),
            "chutes" or "v0" => Make("", Window(24, day, TimeSpan.FromHours(11)), null, null),
            "warp" => Make("Pro", null, null, Monthly()),
            _ => Make("Pro", FiveHour(), Weekly(), null),
        };
    }

    /// <summary>
    /// A few past windows plus the current one for each window the demo snapshot has, so the
    /// usage history chart has something to show. Ends at the snapshot's current value.
    /// </summary>
    public static ProviderHistory History(UsageProvider provider, DateTimeOffset now)
    {
        var snapshot = Snapshot(provider, now);
        var seed = provider.Id.Aggregate(3, (sum, c) => (sum * 17 + c) % 101);
        UsageHistory Build(UsageHistoryKind kind, UsageWindow? current, int pastWindows, TimeSpan step)
        {
            var history = UsageHistory.Empty(kind);
            if (current?.ResetAt is not { } reset) return history;
            var length = TimeSpan.FromSeconds(current.WindowSeconds);
            var samples = new List<UsageSample>();
            for (var back = pastWindows; back >= 0; back--)
            {
                var windowReset = reset - length * back;
                var start = windowReset - length;
                var end = back == 0 ? now : windowReset - step;
                // Past windows finish between 55% and 100%; the current one ends at today's value.
                var final = back == 0 ? current.UsedPercent : 55 + (seed * (back + 3)) % 46;
                var count = Math.Max(2, (int)((end - start) / step));
                for (var i = 0; i <= count; i++)
                {
                    var at = start + (end - start) * i / count;
                    if (at <= start) at = start + TimeSpan.FromMinutes(1);
                    // A burst early in the window, a quiet stretch, then steady use.
                    var t = (double)i / count;
                    var shape = t < 0.25 ? t * 1.8 : t < 0.5 ? 0.45 + (t - 0.25) * 0.4 : 0.55 + (t - 0.5) * 0.9;
                    samples.Add(new UsageSample(at, (int)Math.Round(final * Math.Min(1, shape)), windowReset, current.WindowSeconds));
                }
            }
            return new UsageHistory(kind, samples);
        }
        return new ProviderHistory
        {
            FiveHour = Build(UsageHistoryKind.FiveHour, snapshot.FiveHour, 5, TimeSpan.FromMinutes(20)),
            Weekly = Build(UsageHistoryKind.Weekly, snapshot.Weekly, 3, TimeSpan.FromHours(6)),
            Monthly = Build(UsageHistoryKind.Monthly, snapshot.Monthly, 2, TimeSpan.FromDays(1)),
        };
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

    /// <summary>Device-code providers show a sample code first, so the sign-in dialog can be seen in demo mode.</summary>
    protected override async Task<AuthTokens> RunSignInAsync(SignInPrompt prompt, CancellationToken cancellationToken)
    {
        if (Provider.SignIn == SignInStyle.DeviceCode && prompt.ShowDeviceCode is { } show)
        {
            await show(new Auth.DeviceCode("demo-device", "WDJB-MJHT", "https://example.com/device", "", 5, 900)).ConfigureAwait(false);
            await Task.Delay(6000, cancellationToken).ConfigureAwait(false);
        }
        return await RunSignInAsync(prompt.OpenBrowser, cancellationToken).ConfigureAwait(false);
    }

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
