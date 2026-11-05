using System.Text.Json.Nodes;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;
using P = CodexMeter.Core.Parsing.ProviderUsageParsers;

namespace CodexMeter.Core.Tests;

/// <summary>
/// Mirrors iOS's ProviderParsersTests and Android's ProviderParsersSelfTest: catalog coverage,
/// device-code parsing, and one usage response per added provider. Samples are shapes, not real
/// account data.
/// </summary>
public class ProviderParserTests
{
    private const long NowSeconds = 1_790_000_000; // 2026-09-21T13:33:20Z
    private static readonly DateTimeOffset Now = DateTimeOffset.FromUnixTimeSeconds(NowSeconds);

    private static long ResetSeconds(UsageWindow? window) => window?.ResetAt?.ToUnixTimeSeconds() ?? 0;

    // Catalog

    [Fact]
    public void CatalogCoversEveryProviderOnce()
    {
        var all = UsageProvider.All;
        Assert.Equal(all.Count, all.Distinct().Count());
        foreach (var provider in all)
        {
            Assert.NotEmpty(provider.Meters);
            Assert.NotEmpty(provider.SignInSummary);
            if (provider.SignIn == SignInStyle.ApiKey) Assert.True(provider.AcceptsApiKey, provider.Id);
        }
        Assert.Equal(["codex", "claude_code", "cursor", "copilot", "kimi", "factory", "grok"], all.Take(7).Select(p => p.Id));
        foreach (var provider in new[] { UsageProvider.Grok, UsageProvider.Devin, UsageProvider.MiniMax, UsageProvider.Cline, UsageProvider.OpencodeGo, UsageProvider.Kiro })
            Assert.True(provider.Inferred, provider.Id);
        Assert.False(UsageProvider.Copilot.Inferred);
        Assert.False(UsageProvider.Copilot.IsBuiltIn);
        Assert.True(UsageProvider.Cursor.IsBuiltIn);
        Assert.Equal("Add API key", UsageProvider.Zai.SignInLabel);
        Assert.Equal("Sign in with GitHub", UsageProvider.Copilot.SignInLabel);
    }

    [Fact]
    public void EveryConnectableProviderHasAClient()
    {
        foreach (var provider in UsageProvider.All.Where(p => !p.IsBuiltIn))
        {
            var client = Core.Services.ProviderClients.For(provider);
            if (!provider.IsAvailable) { Assert.Null(client); continue; }
            Assert.NotNull(client);
            Assert.Equal(provider, client!.Provider);
            switch (provider.SignIn)
            {
                case SignInStyle.DeviceCode: Assert.True(client.HasDeviceFlow, provider.Id); break;
                case SignInStyle.CodePaste: Assert.NotNull(client.PasteUrl(new Core.Services.SignInAttempt())); break;
                case SignInStyle.Loopback: Assert.NotNull(client.LoopbackAuthUrl("http://localhost:1/callback", new Core.Services.SignInAttempt())); break;
            }
        }
    }

    [Fact]
    public void PickerHidesConnectedAndSearches()
    {
        var picker = UsageProvider.PickerProviders([UsageProvider.Codex, UsageProvider.Cursor], "");
        Assert.DoesNotContain(UsageProvider.Codex, picker);
        Assert.Equal(UsageProvider.ClaudeCode, picker[0]);
        // Antigravity needs a client secret that this build doesn't have.
        Assert.DoesNotContain(UsageProvider.Antigravity, picker);
        Assert.Equal(UsageProvider.All.Count - 3, picker.Count);
        Assert.Equal([UsageProvider.Copilot], UsageProvider.PickerProviders([], "github"));
        Assert.Equal([UsageProvider.Kimi], UsageProvider.PickerProviders([], "  KIMI "));
        var cognition = UsageProvider.PickerProviders([], "cognition");
        Assert.Contains(UsageProvider.Devin, cognition);
        Assert.Contains(UsageProvider.Windsurf, cognition);
        Assert.Empty(UsageProvider.PickerProviders([], "zzzz"));
    }

    // JSON

    [Fact]
    public void JsonKeepsKeyOrder()
    {
        var o = ProviderJson.Object("""{"b":1,"a":{"z":true,"y":null},"c":[1,"x"],"d":"é\n"}""");
        Assert.Equal(["b", "a", "c", "d"], o.Select(p => p.Key));
        Assert.Equal(["z", "y"], ProviderJson.Obj(o, "a")!.Select(p => p.Key));
        Assert.Equal("1", ProviderJson.String(o, "b"));
        Assert.Equal("é\n", ProviderJson.String(o, "d"));
        Assert.Empty(ProviderJson.Object("not json"));
        Assert.Empty(ProviderJson.Object(""));
        Assert.Equal(12, ProviderJson.Int(ProviderJson.Object("""{"n":12.50}"""), "n", 0));
    }

    // Device sign-in

    [Fact]
    public void DeviceAuthParsing()
    {
        var github = DeviceAuth.ParseDeviceCode("""{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":899,"interval":5}""");
        Assert.True(github.IsValid);
        Assert.Equal("ABCD-1234", github.UserCode);
        Assert.EndsWith("/login/device", github.OpenUrl);
        Assert.Equal(5, github.IntervalSeconds);
        Assert.Equal(899, github.ExpiresInSeconds);
        var aws = DeviceAuth.ParseDeviceCode("""{"deviceCode":"d","userCode":"WXYZ-9876","verificationUri":"https://view.awsapps.com/start/#/device","verificationUriComplete":"https://view.awsapps.com/start/#/device?user_code=WXYZ-9876","expiresIn":600,"interval":1}""");
        Assert.True(aws.IsValid && aws.OpenUrl.Contains("user_code=WXYZ-9876"));
        Assert.Equal(DevicePollState.Pending, DeviceAuth.ParsePoll(200, """{"error":"authorization_pending"}""").State);
        Assert.Equal(DevicePollState.SlowDown, DeviceAuth.ParsePoll(400, """{"error":"slow_down"}""").State);
        Assert.Equal(DevicePollState.Denied, DeviceAuth.ParsePoll(400, """{"error":"access_denied"}""").State);
        Assert.Equal(DevicePollState.Expired, DeviceAuth.ParsePoll(400, """{"error":"expired_token"}""").State);
        Assert.Equal(DevicePollState.Pending, DeviceAuth.ParsePoll(202, "").State);
        Assert.Equal(DevicePollState.Expired, DeviceAuth.ParsePoll(410, "").State);
        var done = DeviceAuth.ParsePoll(200, """{"access_token":"at","refresh_token":"rt","expires_in":3600,"user":{"email":"dev@example.com"}}""");
        Assert.Equal(DevicePollState.Done, done.State);
        Assert.Equal("rt", done.Tokens!.RefreshToken);
        Assert.Equal("dev@example.com", done.Tokens.Email);
        Assert.Equal(3600, done.Tokens.ExpiresInSeconds);
        Assert.Equal("abc", DeviceAuth.ParseTokens("""{"token":"abc"}""").AccessToken);
        Assert.Equal("cl", DeviceAuth.ParseTokens("""{"success":true,"data":{"accessToken":"cl","refreshToken":"r","userInfo":{"email":"a@b.co"}}}""").AccessToken);
        Assert.Equal("tok", DeviceAuth.ExtractPasted("  tok\n", "access_token"));
        Assert.Equal("x y", DeviceAuth.ExtractPasted("https://example.com/cb?state=a&access_token=x%20y", "access_token"));
        Assert.Equal("c1", DeviceAuth.ExtractPasted("""{"code":"c1","state":"s"}""", "code"));
        Assert.Equal("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.S256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        Assert.Equal(43, DeviceAuth.RandomToken(32).Length);
        var stored = done.Tokens.Stored(Now);
        Assert.Equal(Now.AddHours(1), stored.ExpiresAt);
        var forever = new DeviceTokens("key").Stored(Now);
        Assert.Equal("key", forever.RefreshToken);
        Assert.Equal(AuthTokens.NeverExpires, forever.ExpiresAt);
    }

    // Timestamps

    [Fact]
    public void Timestamps()
    {
        var iso = P.EpochSeconds("2026-10-01T00:00:00Z");
        Assert.True(iso > 0);
        Assert.Equal(iso, P.EpochSeconds(JsonValue.Create(iso)));
        Assert.Equal(iso, P.EpochSeconds(JsonValue.Create(iso * 1000)));
        Assert.Equal(iso, P.EpochSeconds("2026-10-01"));
        Assert.Equal(iso, P.EpochSeconds((iso * 1000).ToString()));
        Assert.Equal(iso, P.EpochSeconds("2026-10-01T02:00:00+02:00"));
        Assert.Equal(iso, P.EpochSeconds("2026-10-01T00:00:00.123456Z"));
        Assert.Equal(0, P.EpochSeconds((string?)null));
        Assert.Equal(0, P.EpochSeconds("soon"));
        Assert.Equal("Weekly", P.DurationName(P.Week));
        Assert.Equal("5-hour", P.DurationName(5 * 3600));
        Assert.Equal("Daily", P.DurationName(86_400));
        Assert.Equal("Monthly", P.DurationName(P.Month));
    }

    // Providers

    [Fact]
    public void Copilot()
    {
        const string body = """{"copilot_plan":"individual","quota_reset_date":"2026-10-01","quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":75,"percent_remaining":25.0,"unlimited":false},"chat":{"entitlement":0,"remaining":0,"percent_remaining":100,"unlimited":true},"completions":{"entitlement":0,"remaining":0,"unlimited":false}}}""";
        var snapshot = P.Copilot(body, Now);
        Assert.Equal("Pro", snapshot.PlanType);
        Assert.Equal(75, snapshot.Monthly?.UsedPercent);
        Assert.Equal(P.EpochSeconds("2026-10-01"), ResetSeconds(snapshot.Monthly));
        Assert.Empty(snapshot.AdditionalLimits);
        var free = P.Copilot("""{"access_type_sku":"free_limited_copilot","limited_user_reset_date":"2026-10-05","monthly_quotas":{"chat":50,"completions":2000},"limited_user_quotas":{"chat":40,"completions":500}}""", Now);
        Assert.Equal("Free", free.PlanType);
        Assert.Equal(20, free.Monthly?.UsedPercent);
        Assert.Single(free.AdditionalLimits);
        Assert.Equal(75, free.AdditionalLimits[0].Primary?.UsedPercent);
        Assert.Equal("Pro+", P.CopilotPlan("individual_pro"));
        Assert.Equal("Business", P.CopilotPlan("business"));
    }

    [Fact]
    public void Kimi()
    {
        const string body = """{"user":{"membership":{"level":"LEVEL_INTERMEDIATE"}},"usage":{"limit":"2048","used":"512","resetTime":"2026-09-25T00:00:00Z"},"limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"200","remaining":"150","resetTime":"2026-09-21T15:00:00Z"}}]}""";
        var snapshot = P.Kimi(body, Now);
        Assert.Equal("Allegretto", snapshot.PlanType);
        Assert.Equal(25, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(18_000, snapshot.FiveHour?.WindowSeconds);
        Assert.Equal(25, snapshot.Weekly?.UsedPercent);
        var ratios = P.Kimi("""{"usages":{"limit_5h":{"used_ratio":0.4},"limit_7d":{"used_ratio":0.1,"reset_time":1790500000}}}""", Now);
        Assert.Equal(40, ratios.FiveHour?.UsedPercent);
        Assert.Equal(10, ratios.Weekly?.UsedPercent);
        Assert.Equal(1_790_500_000, ResetSeconds(ratios.Weekly));
    }

    [Fact]
    public void Factory()
    {
        var body = """{"usesTokenRateLimitsBilling":true,"limits":{"standard":{"fiveHour":{"usedPercent":12.5,"windowEnd":@0@},"weekly":{"usedPercent":40,"secondsRemaining":86400},"monthly":{"usedPercent":90,"windowEnd":@1@}},"core":{"weekly":{"usedPercent":5,"windowEnd":"2026-09-28T00:00:00Z"}}},"extraUsageBalanceCents":1250}""".Replace("@0@", (NowSeconds + 3600).ToString()).Replace("@1@", (NowSeconds - 60).ToString());
        var snapshot = P.Factory(body, "Pro", Now);
        Assert.Equal(13, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(40, snapshot.Weekly?.UsedPercent);
        Assert.Equal(NowSeconds + 86_400, ResetSeconds(snapshot.Weekly));
        Assert.Equal(0, snapshot.Monthly?.UsedPercent);
        Assert.Equal(["Core weekly limit"], snapshot.AdditionalLimits.Select(l => l.Name));
        Assert.Equal("$12.50", snapshot.UsageCredits?.Balance);
        var legacy = P.Factory("""{"usage":{"startDate":"2026-09-01T00:00:00Z","endDate":"2026-10-01T00:00:00Z","standard":{"usedRatio":0.3}}}""", "", Now);
        Assert.Equal(30, legacy.Monthly?.UsedPercent);
        Assert.Equal("Max", P.FactoryPlan("""{"organization":{"subscription":{"orbSubscription":{"plan":{"name":"Factory Max"}}}}}"""));
    }

    [Fact]
    public void Grok()
    {
        const string body = """{"config":{"creditUsagePercent":37.5,"currentPeriod":{"start":"2026-09-01T00:00:00Z","end":"2026-10-01T00:00:00Z"},"subscriptionTier":"SUBSCRIPTION_TIER_SUPER_GROK_PRO","productUsage":[{"product":"grok_code","usagePercent":20}],"prepaidBalance":{"val":"500"}}}""";
        var snapshot = P.Grok(body, Now);
        Assert.Equal("SuperGrok", snapshot.PlanType);
        Assert.Equal(38, snapshot.Monthly?.UsedPercent);
        Assert.Equal(["Grok Code"], snapshot.AdditionalLimits.Select(l => l.Name));
        Assert.Equal("$5.00", snapshot.UsageCredits?.Balance);
    }

    [Fact]
    public void Antigravity()
    {
        const string body = """{"groups":[{"name":"Gemini","buckets":[{"bucketId":"gemini-5h","remainingFraction":0.75,"resetTime":"2026-09-21T16:00:00Z"},{"bucketId":"gemini-weekly","remainingFraction":0.5}]},{"name":"Third party","buckets":[{"bucketId":"3p-5h","resetTime":"2026-09-21T15:00:00Z"},{"bucketId":"3p-weekly","remainingFraction":0.9}]}]}""";
        var snapshot = P.Antigravity(body, "Pro", Now);
        Assert.Equal(25, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(50, snapshot.Weekly?.UsedPercent);
        var third = snapshot.AdditionalLimits[0];
        Assert.Equal("Claude and other models", third.Name);
        Assert.Equal(100, third.Primary?.UsedPercent);
        Assert.Equal(10, third.Secondary?.UsedPercent);
        Assert.True(third.LimitReached);
        Assert.Equal("Ultra", P.AntigravityPlan("""{"paidTier":{"id":"g1-ultra-tier"}}"""));
        Assert.Equal("Ultra Lite", P.AntigravityPlan("""{"paidTier":{"id":"g1-ultra-lite-tier"}}"""));
        Assert.Equal("Free", P.AntigravityPlan("""{"currentTier":{"name":"Free"}}"""));
    }

    [Fact]
    public void Codeium()
    {
        var body = """{"userStatus":{"planStatus":{"planInfo":{"planName":"Pro"},"dailyQuotaRemainingPercent":80,"dailyQuotaResetAtUnix":"@0@","weeklyQuotaResetAtUnix":"@1@","overageBalanceMicros":"2500000"}}}""".Replace("@0@", (NowSeconds + 3600).ToString()).Replace("@1@", (NowSeconds + 86400).ToString());
        var snapshot = P.Codeium(body, Now);
        Assert.Equal("Pro", snapshot.PlanType);
        Assert.Equal(20, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(86_400, snapshot.FiveHour?.WindowSeconds);
        Assert.Equal(100, snapshot.Weekly?.UsedPercent);
        Assert.Equal("$2.50", snapshot.UsageCredits?.Balance);
    }

    [Fact]
    public void AugmentClineOpenCodeGo()
    {
        var augment = P.Augment("""{"plan_name":"Developer","usage_unit":"credits","amount_remaining":30000,"amount_included_per_cycle":40000,"billing_cycle_end_date_iso":"2026-10-01T00:00:00Z"}""", Now);
        Assert.Equal("Developer", augment.PlanType);
        Assert.Equal(25, augment.Monthly?.UsedPercent);
        var cline = P.Cline("""{"success":true,"data":{"limits":[{"type":"five_hour","percentUsed":61,"resetsAt":"2026-09-21T17:00:00Z"},{"type":"weekly","percentUsed":20},{"type":"monthly","percentUsed":7}]}}""", Now);
        Assert.Equal(61, cline.FiveHour?.UsedPercent);
        Assert.Equal(20, cline.Weekly?.UsedPercent);
        Assert.Equal(7, cline.Monthly?.UsedPercent);
        var go = P.OpencodeGo("""{"usage":{"rolling":{"usagePercent":33,"resetInSec":600},"weekly":{"usedPercent":12,"resetAt":"2026-09-27T00:00:00Z"},"monthly":{"percent":4}}}""", Now);
        Assert.Equal(33, go.FiveHour?.UsedPercent);
        Assert.Equal(NowSeconds + 600, ResetSeconds(go.FiveHour));
        Assert.Equal(12, go.Weekly?.UsedPercent);
        Assert.Equal(4, go.Monthly?.UsedPercent);
        Assert.True(Core.Services.AugmentClient.IsAugmentTenant("https://d1.api.augmentcode.com/"));
        Assert.False(Core.Services.AugmentClient.IsAugmentTenant("https://evil.example.com/"));
        Assert.False(Core.Services.AugmentClient.IsAugmentTenant("http://d1.api.augmentcode.com/"));
    }

    [Fact]
    public void MiniMax()
    {
        var start = NowSeconds * 1000 - 3_600_000;
        var end = start + 18_000_000;
        var snapshot = P.MiniMax($$"""{"base_resp":{"status_code":0},"model_remains":[{"model_name":"MiniMax-M2","start_time":{{start}},"end_time":{{end}},"current_interval_total_count":300,"current_interval_usage_count":240,"current_weekly_total_count":3000,"current_weekly_usage_count":2700}]}""", Now);
        Assert.Equal(20, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(18_000, snapshot.FiveHour?.WindowSeconds);
        Assert.Equal(10, snapshot.Weekly?.UsedPercent);
        Assert.False(P.MiniMax("""{"base_resp":{"status_code":1004}}""", Now).HasDisplayableData);
    }

    [Fact]
    public void Zai()
    {
        var snapshot = P.Zai("""{"code":200,"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":44,"nextResetTime":@0@},{"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":9},{"type":"TIME_LIMIT","unit":5,"number":1,"usage":100,"currentValue":30}],"planName":"pro"}}""".Replace("@0@", (NowSeconds * 1000 + 3_600_000).ToString()), Now);
        Assert.Equal("Pro", snapshot.PlanType);
        Assert.Equal(44, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(NowSeconds + 3600, ResetSeconds(snapshot.FiveHour));
        Assert.Equal(9, snapshot.Weekly?.UsedPercent);
        Assert.Single(snapshot.AdditionalLimits);
        Assert.Equal(30, snapshot.AdditionalLimits[0].Primary?.UsedPercent);
    }

    [Fact]
    public void KiroOpenRouterAmp()
    {
        var kiro = P.Kiro("""{"subscriptionInfo":{"subscriptionTitle":"KIRO PRO"},"nextDateReset":1791000000,"usageBreakdownList":[{"resourceType":"CREDIT","currentUsage":250,"usageLimit":1000}]}""", Now);
        Assert.Equal("PRO", kiro.PlanType);
        Assert.Equal(25, kiro.Monthly?.UsedPercent);
        Assert.Equal(1_791_000_000, ResetSeconds(kiro.Monthly));

        var router = P.OpenRouter("""{"data":{"limit":20,"usage":30,"usage_monthly":5,"limit_reset":"monthly","is_free_tier":false}}""",
            """{"data":{"total_credits":50,"total_usage":12.345}}""", Now);
        Assert.Equal(25, router.Monthly?.UsedPercent);
        Assert.Equal(P.EpochSeconds("2026-10-01"), ResetSeconds(router.Monthly));
        Assert.Equal("$37.66", router.UsageCredits?.Balance);
        Assert.False(P.OpenRouter("""{"data":{"limit":null,"usage":3}}""", "", Now).HasDisplayableData);

        var amp = P.Amp("""{"result":{"displayText":"Amp Free: $7.50/$10 remaining (replenishes +$0.42/hour) - Individual credits: $5.00 credits"}}""", Now);
        Assert.Equal("Free", amp.PlanType);
        Assert.Equal(25, amp.FiveHour?.UsedPercent);
        Assert.Equal("$5.00", amp.UsageCredits?.Balance);
    }

    [Fact]
    public void BalancesAndKeyQuota()
    {
        Assert.Equal("$4.20", P.Kilo("""{"balance":4.2}""", Now).UsageCredits?.Balance);
        Assert.Equal(25, P.Codebuff("""{"usage":250,"quota":1000,"next_quota_reset":"2026-10-01T00:00:00Z"}""", Now).Monthly?.UsedPercent);
        var synthetic = P.KeyQuota("""{"subscription":{"limit":135,"requests":20},"rollingFiveHourLimit":{"limit":200,"used":50,"resetAt":"2026-09-21T15:00:00Z"},"weeklyTokenLimit":{"limit":1000,"remaining":900}}""", 5 * 3600, Now);
        Assert.Equal(25, synthetic.FiveHour?.UsedPercent);
        Assert.Equal(10, synthetic.Weekly?.UsedPercent);
        var warp = P.KeyQuota("""{"data":{"user":{"user":{"requestLimitInfo":{"isUnlimited":false,"nextRefreshTime":"2026-10-01T00:00:00Z","requestLimit":150,"requestsUsedSinceLastRefresh":30}}}}}""", P.Month, Now);
        Assert.Equal(20, warp.Monthly?.UsedPercent);
        Assert.False(P.KeyQuota("""{"ok":true}""", 3600, Now).HasDisplayableData);
    }
}
