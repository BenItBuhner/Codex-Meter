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
        // Every route is confirmed, so no provider carries the experimental note.
        foreach (var provider in all) Assert.False(provider.Inferred, provider.Id);
        Assert.Equal("Devin session", UsageProvider.Devin.SignInSummary);
        Assert.Equal("Windsurf token", UsageProvider.Windsurf.SignInSummary);
        Assert.Equal("Included usage · Weekly", UsageProvider.Cursor.MeterTitle("Weekly"));
        Assert.Equal("Weekly", UsageProvider.Copilot.MeterTitle("Weekly"));
        Assert.Equal("Included usage and extra spend", UsageProvider.Cursor.Meters);
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
            Assert.NotNull(client);
            if (!provider.IsAvailable)
            {
                // Antigravity without its build-time sign-in client: the client says so instead of opening Google.
                Assert.Throws<Core.Services.ProviderException>(() => client!.LoopbackAuthUrl("http://localhost:1/callback", new Core.Services.SignInAttempt()));
                continue;
            }
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
    public async Task OpenCodeFreeResetsAtMidnightUtcAndNeedsNoAccount()
    {
        var midnight = DateTimeOffset.FromUnixTimeSeconds(20_000L * 86_400);
        Assert.Equal(midnight, OpenCodeFree.NextReset(midnight.AddSeconds(-1)));
        Assert.Equal(midnight.AddDays(1), OpenCodeFree.NextReset(midnight));
        Assert.Equal(midnight.AddDays(1), OpenCodeFree.NextReset(midnight.AddSeconds(4_856)));
        // A local offset doesn't move the reset: it is midnight UTC everywhere.
        Assert.Equal(midnight.AddDays(1), OpenCodeFree.NextReset(midnight.AddHours(3).ToOffset(TimeSpan.FromHours(-6))));
        var snapshot = OpenCodeFree.Snapshot(midnight);
        Assert.Null(snapshot.FiveHour);
        Assert.Null(snapshot.Weekly);
        Assert.Null(snapshot.Monthly);
        Assert.Empty(snapshot.AdditionalLimits);
        Assert.False(UsageProvider.OpencodeFree.HasMeters);
        Assert.True(UsageProvider.OpencodeGo.HasMeters);
        var all = UsageProvider.All.ToList();
        Assert.Equal(all.IndexOf(UsageProvider.OpencodeGo) + 1, all.IndexOf(UsageProvider.OpencodeFree));
        Assert.Equal(SignInStyle.NoAccount, UsageProvider.OpencodeFree.SignIn);
        var tokens = await new Core.Services.OpenCodeFreeClient().FromApiKeyAsync("", null!, CancellationToken.None);
        Assert.True(tokens.IsUsable);
    }

    [Fact]
    public void PickerHidesConnectedAndSearches()
    {
        var picker = UsageProvider.PickerProviders([UsageProvider.Codex, UsageProvider.Cursor], "");
        Assert.DoesNotContain(UsageProvider.Codex, picker);
        Assert.Equal(UsageProvider.ClaudeCode, picker[0]);
        // Antigravity is offered only when the build has its Google sign-in client.
        Assert.Equal(Core.Services.AntigravityOAuth.Configured, picker.Contains(UsageProvider.Antigravity));
        Assert.Equal(UsageProvider.All.Count - (Core.Services.AntigravityOAuth.Configured ? 2 : 3), picker.Count);
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
        Assert.Equal("https://opencode.ai/console/device?user_code=AB", DeviceAuth.Absolute("https://opencode.ai", "/console/device?user_code=AB"));
        Assert.Equal("https://x.ai/a", DeviceAuth.Absolute("https://o", "https://x.ai/a"));
        Assert.Equal("", DeviceAuth.Absolute("https://o", ""));
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
        // A ratio over 1 is over the limit, not 1%.
        var over = P.Kimi("""{"usages":{"limit_5h":{"used_ratio":1.2},"limit_7d":{"used_ratio":0.01}}}""", Now);
        Assert.Equal(100, over.FiveHour?.UsedPercent);
        Assert.Equal(1, over.Weekly?.UsedPercent);
    }

    [Fact]
    public void ZonelessTimesKeepTheirTimeOfDayAsUtc()
    {
        Assert.Equal(P.EpochSeconds("2026-12-01T15:30:00Z"), P.EpochSeconds("2026-12-01T15:30:00"));
        Assert.Equal(P.EpochSeconds("2026-12-01T15:30:00Z"), P.EpochSeconds("2026-12-01T15:30:00.123456"));
        Assert.Equal(P.EpochSeconds("2026-12-01T00:00:00Z"), P.EpochSeconds("2026-12-01"));
        var naive = P.Chutes("""{"subscription":true,"monthly_price":20,"four_hour":{"usage":1,"cap":4,"remaining":3,"reset_at":"2026-09-21T16:00:00"}}""", Now);
        Assert.Equal(P.EpochSeconds("2026-09-21T16:00:00Z"), ResetSeconds(naive.FiveHour));
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
    public void RecordedRepliesFromCodexBar()
    {
        // Replies as CodexBar's tests record them, which these parsers used to miss (same cases as Android).
        var minimax = P.MiniMax("""{"base_resp":{"status_code":"0"},"data":{"current_subscribe_title":"Max","model_remains":[{"current_interval_total_count":"15000","current_interval_usage_count":"14989","start_time":0,"end_time":0,"remains_time":8941292}]}}""", Now);
        Assert.Equal("Max", minimax.PlanType);
        Assert.Equal(0, minimax.FiveHour?.UsedPercent);
        var tokenPlan = P.MiniMax("""{"model_remains":[{"model_name":"general","current_interval_total_count":0,"current_interval_usage_count":0,"current_interval_remaining_percent":100,"current_weekly_total_count":0,"current_weekly_usage_count":0,"current_weekly_remaining_percent":70}],"base_resp":{"status_code":0}}""", Now);
        Assert.Equal(30, tokenPlan.Weekly?.UsedPercent);
        var copilot = P.Copilot("""{"copilot_plan":"business","token_based_billing":true,"quota_snapshots":{"premium_interactions":{"entitlement":0,"remaining":0,"percent_remaining":100,"credits_used":31}}}""", Now);
        Assert.True(copilot.HasDisplayableData);
        Assert.True(copilot.UsageCredits?.Unlimited);
        Assert.Equal("Business", copilot.PlanType);
        var kilo = P.KiloTrpc("""[{"result":{"data":{"creditBlocks":[{"balance_mUsd":19000000,"amount_mUsd":19000000}],"totalBalance_mUsd":19000000}}},{"result":{"data":{"subscription":{"tier":"tier_19","currentPeriodUsageUsd":7.125,"currentPeriodBaseCreditsUsd":19.0,"currentPeriodBonusCreditsUsd":9.5,"nextBillingAt":"2026-10-28T04:00:00.000Z"}}}}]""", Now);
        Assert.Equal("$19.00", kilo.UsageCredits?.Balance);
        Assert.Equal("Starter", kilo.PlanType);
        Assert.Equal(25, kilo.Monthly?.UsedPercent);
        Assert.Equal("$0.00", P.KiloTrpc("""[{"result":{"data":{"creditBlocks":[],"totalBalance_mUsd":0}}},{"result":{"data":{"subscription":null}}}]""", Now).UsageCredits?.Balance);
        Assert.True(P.KiloUnauthorized("""[{"error":{"json":{"data":{"code":"UNAUTHORIZED"}}}}]"""));
        var codebuff = P.CodebuffSubscription(P.Codebuff("""{"usage":1250,"quota":5000}""", Now),
            """{"subscription":{"status":"active","tier":"pro"},"rateLimit":{"weeklyUsed":2100,"weeklyLimit":7000,"weeklyResetsAt":"2026-09-25T00:00:00Z"}}""", Now);
        Assert.Equal("Pro", codebuff.PlanType);
        Assert.Equal(30, codebuff.Weekly?.UsedPercent);
        Assert.Equal(25, codebuff.Monthly?.UsedPercent);
        Assert.True(P.Grok("""{"config":{"onDemandCap":{"val":1000.0},"onDemandUsed":{"val":250.5}}}""", Now).HasDisplayableData);
    }

    [Fact]
    public void Antigravity()
    {
        // retrieveUserQuotaSummary, as CodexBar's fixtures record it (same cases as Android).
        const string body = """{"response":{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-weekly","displayName":"Weekly Limit","remaining":{"remainingFraction":0.5},"resetTime":"2026-09-25T16:00:00Z"},{"bucketId":"gemini-5h","displayName":"Five Hour Limit","remaining":{"remainingFraction":0.75},"resetTime":"2026-09-21T16:00:00Z"}]},{"displayName":"Claude and GPT models","buckets":[{"bucketId":"3p-weekly","remaining":{"case":"remainingFraction","value":0.9}},{"bucketId":"3p-5h","remaining":{"remainingFraction":0},"resetTime":"2026-09-21T15:00:00Z"}]}]}}""";
        var snapshot = P.Antigravity(body, "Pro", Now);
        Assert.Equal(25, snapshot.FiveHour?.UsedPercent);
        Assert.Equal(5 * 3600L, snapshot.FiveHour?.WindowSeconds);
        Assert.Equal(50, snapshot.Weekly?.UsedPercent);
        var third = snapshot.AdditionalLimits[0];
        Assert.Equal("Claude and GPT models", third.Name);
        Assert.Equal(100, third.Primary?.UsedPercent);
        Assert.Equal(10, third.Secondary?.UsedPercent);
        Assert.True(third.LimitReached);
        // A bucket with no fraction, or a disabled one, is unknown rather than used up.
        Assert.False(P.Antigravity("""{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-weekly","resetTime":"2026-09-25T16:00:00Z"},{"bucketId":"gemini-5h","disabled":true,"remaining":{"remainingFraction":1}}]}]}""", "", Now).HasDisplayableData);
        var models = P.Antigravity("""{"models":{"gemini-3-pro-high":{"displayName":"Gemini 3 Pro","quotaInfo":{"remainingFraction":0.4,"resetTime":"2026-09-21T16:00:00Z"}},"gemini-3-flash":{"quotaInfo":{"remainingFraction":0.8}},"claude-sonnet-4-6":{"quotaInfo":{"remainingFraction":1}}}}""", "", Now);
        Assert.Equal(60, models.FiveHour?.UsedPercent);
        Assert.Equal(0, models.AdditionalLimits[0].Primary?.UsedPercent);
        Assert.Equal(70, P.Antigravity("""{"buckets":[{"modelId":"gemini-2.5-pro","remainingFraction":0.3}]}""", "", Now).FiveHour?.UsedPercent);
        Assert.True(P.AntigravityClientRejected("""{"ineligibleTiers":[{"reasonCode":"GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT"}]}"""));
        Assert.False(P.AntigravityClientRejected("""{"currentTier":{"id":"free-tier"},"cloudaicompanionProject":"p-1"}"""));
        Assert.Equal("p-1", P.AntigravityProject("""{"response":{"cloudaicompanionProject":{"id":"p-1"}}}"""));
        Assert.Equal("standard-tier", P.AntigravityOnboardTier("""{"allowedTiers":[{"id":"free-tier"},{"id":"standard-tier","isDefault":true}]}"""));
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
        // Credits lead even when listed second.
        var mixed = P.Kiro("""{"nextDateReset":1791000000,"usageBreakdownList":[{"resourceType":"AGENTIC_REQUEST","currentUsageWithPrecision":5,"usageLimitWithPrecision":50},{"resourceType":"CREDIT","currentUsageWithPrecision":100.5,"usageLimitWithPrecision":201}]}""", Now);
        Assert.Equal(50, mixed.Monthly?.UsedPercent);
        Assert.Single(mixed.AdditionalLimits);

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
        // 2026-09-21T13:33:20Z is 09:33 in New York, so Amp Free refills at 20:00 EDT that day.
        Assert.Equal(P.EpochSeconds("2026-09-22T00:00:00Z"), ResetSeconds(amp.FiveHour));
        var percent = P.Amp("""{"ok":true,"result":{"displayText":"Signed in as user@example.com\nAmp Free: 61% remaining today (resets daily)\nIndividual credits: $12.50 remaining"}}""", Now);
        Assert.Equal(39, percent.FiveHour?.UsedPercent);
        Assert.Equal("$12.50", percent.UsageCredits?.Balance);
        var tier = P.Amp("""{"ok":true,"result":{"displayText":"Amp Megawatt Tier: agent usage $18.57 of $20 remaining (93%), orb usage 732.8h of 750h a1.small orb hours remaining (98%) - period 2026-09-13 to 2026-10-13, resets upon renewal in 27 days"}}""", Now);
        Assert.Equal("Megawatt", tier.PlanType);
        Assert.Equal(7, tier.Monthly?.UsedPercent);
        Assert.Equal(P.EpochSeconds("2026-10-13"), ResetSeconds(tier.Monthly));
        Assert.Single(tier.AdditionalLimits);
        Assert.Equal("Orb hours", tier.AdditionalLimits[0].Name);
        Assert.Equal(2, tier.AdditionalLimits[0].Primary?.UsedPercent);
    }

    [Fact]
    public void Balances()
    {
        Assert.Equal("$4.20", P.Kilo("""{"balance":4.2}""", Now).UsageCredits?.Balance);
        Assert.Equal(25, P.Codebuff("""{"usage":250,"quota":1000,"next_quota_reset":"2026-10-01T00:00:00Z"}""", Now).Monthly?.UsedPercent);
    }

    [Fact]
    public void Synthetic()
    {
        var current = P.Synthetic("""{"subscription":{"limit":135,"requests":20},"planWeeklyLimit":{"nextRegenAt":"2026-09-21T15:00:00Z","percentRemaining":0.6,"nextRegenPercent":2},"search":{"hourly":{"limit":250,"requests":25}}}""", Now);
        // planWeeklyLimit is in percentage points, and the retired counter is ignored.
        Assert.Equal(99, current.Weekly?.UsedPercent);
        Assert.Null(current.FiveHour);
        Assert.Equal(P.EpochSeconds("2026-09-21T15:00:00Z"), ResetSeconds(current.Weekly));
        Assert.Single(current.AdditionalLimits);
        Assert.Equal(10, current.AdditionalLimits[0].Primary?.UsedPercent);
        var legacy = P.Synthetic("""{"data":{"rollingFiveHourLimit":{"percentRemaining":0.75},"weeklyTokenLimit":{"percentRemaining":0.98}}}""", Now);
        Assert.Equal(25, legacy.FiveHour?.UsedPercent);
        Assert.Equal(5 * 3600, legacy.FiveHour?.WindowSeconds);
        Assert.Equal(2, legacy.Weekly?.UsedPercent);
        var counter = P.Synthetic("""{"subscription":{"limit":135,"requests":27,"renewsAt":"2026-09-21T15:00:00Z"}}""", Now);
        Assert.Equal(20, counter.FiveHour?.UsedPercent);
        Assert.False(P.Synthetic("{}", Now).HasDisplayableData);
    }

    [Fact]
    public void Chutes()
    {
        var pro = P.Chutes("""{"subscription":true,"custom":false,"monthly_price":20,"anchor_date":"2026-09-01T00:00:00","four_hour":{"usage":2.5,"cap":8.333,"remaining":5.833,"reset_at":"2026-09-21T16:00:00+00:00"},"monthly":{"usage":25,"cap":100,"remaining":75,"reset_at":"2026-10-01T00:00:00+00:00"}}""", Now);
        Assert.Equal("$20/month", pro.PlanType);
        Assert.Equal(30, pro.FiveHour?.UsedPercent);
        Assert.Equal(4 * 3600, pro.FiveHour?.WindowSeconds);
        Assert.Equal(P.EpochSeconds("2026-09-21T16:00:00Z"), ResetSeconds(pro.FiveHour));
        Assert.Equal(25, pro.Monthly?.UsedPercent);
        var custom = P.Chutes("""{"subscription":true,"custom":true,"monthly_price":50,"four_hour":{"usage":1,"cap":4,"remaining":3,"reset_at":"2026-09-21T16:00:00+00:00"},"monthly":{"uncapped":true}}""", Now);
        Assert.Equal("Custom", custom.PlanType);
        Assert.Null(custom.Monthly);
        Assert.Equal(25, custom.FiveHour?.UsedPercent);
        Assert.False(P.Chutes("""{"subscription":false}""", Now).HasDisplayableData);
    }

    [Fact]
    public void V0()
    {
        const long cycleEnd = 1_790_726_400_000; // 2026-09-30T00:00:00Z, in milliseconds
        const long cycleStart = cycleEnd - 30L * 86_400_000;
        const long soon = NowSeconds * 1000 + 3_600_000;
        static string Fill(string json) => json.Replace("@S@", $"{cycleStart}").Replace("@E@", $"{cycleEnd}").Replace("@N@", $"{soon}");
        var token = P.V0(
            Fill("""{"billingType":"token","data":{"balance":{"total":20,"remaining":15},"billingCycle":{"start":@S@,"end":@E@},"onDemand":{"balance":4.5}}}"""),
            """{"object":"plan","plan":"premium"}""",
            Fill("""{"limit":1000,"remaining":900,"reset":@N@}"""), Now);
        Assert.Equal("Premium", token.PlanType);
        Assert.Equal(25, token.Monthly?.UsedPercent);
        Assert.Equal(cycleEnd / 1000, ResetSeconds(token.Monthly));
        Assert.Equal("$4.50", token.UsageCredits?.Balance);
        Assert.Null(token.FiveHour);
        Assert.Single(token.AdditionalLimits);
        Assert.Equal(10, token.AdditionalLimits[0].Primary?.UsedPercent);
        var free = P.V0(
            Fill("""{"billingType":"legacy","data":{"limit":5,"remaining":1,"reset":@E@}}"""), "",
            Fill("""{"limit":100,"remaining":100,"dailyLimit":{"limit":10,"remaining":4,"reset":@N@,"isWithinGracePeriod":false}}"""), Now);
        Assert.Equal(80, free.Monthly?.UsedPercent);
        Assert.Equal(60, free.FiveHour?.UsedPercent);
        Assert.Equal(86_400, free.FiveHour?.WindowSeconds);
        var planOnly = P.V0("{}",
            Fill("""{"plan":"free","balance":{"remaining":3,"total":5},"billingCycle":{"start":@S@,"end":@E@}}"""), "", Now);
        Assert.Equal(40, planOnly.Monthly?.UsedPercent);
    }

    [Fact]
    public void Warp()
    {
        var warp = P.Warp("""{"data":{"user":{"__typename":"UserOutput","user":{"requestLimitInfo":{"isUnlimited":false,"nextRefreshTime":"2026-10-01T00:00:00Z","requestLimit":1500,"requestsUsedSinceLastRefresh":300},"bonusGrants":[{"requestCreditsGranted":100,"requestCreditsRemaining":40}],"workspaces":[{"bonusGrantsInfo":{"grants":[{"requestCreditsGranted":50,"requestCreditsRemaining":10}]}}]}}}}""", Now);
        Assert.Equal(20, warp.Monthly?.UsedPercent);
        Assert.Equal(P.EpochSeconds("2026-10-01"), ResetSeconds(warp.Monthly));
        Assert.Equal("50 add-on credits", warp.UsageCredits?.Balance);
        var unlimited = P.Warp("""{"data":{"user":{"user":{"requestLimitInfo":{"isUnlimited":true}}}}}""", Now);
        Assert.Equal("Unlimited", unlimited.PlanType);
        Assert.Null(unlimited.Monthly);
        Assert.True(unlimited.UsageCredits?.Unlimited);
        Assert.Equal("Not authorized", P.WarpError("""{"errors":[{"message":"Not authorized"}],"data":null}"""));
        Assert.False(P.Warp("""{"data":null}""", Now).HasDisplayableData);
    }

    [Fact]
    public void Devin()
    {
        var devin = P.Devin($$"""{"plan_name":"core","daily_percentage":30,"daily_reset_at":"2026-09-22T00:00:00Z","weekly_percentage":45,"weekly_reset_at":{{NowSeconds + 3 * 86_400}},"overage_balance_cents":1250}""", Now);
        Assert.Equal("Core", devin.PlanType);
        Assert.Equal(30, devin.FiveHour?.UsedPercent);
        Assert.Equal(86_400, devin.FiveHour?.WindowSeconds);
        Assert.Equal(45, devin.Weekly?.UsedPercent);
        Assert.Equal("$12.50", devin.UsageCredits?.Balance);
        Assert.Null(P.Devin("""{"hide_daily_quota":true,"daily_percentage":10,"weekly_percentage":20}""", Now).FiveHour);
        const string curl = "curl 'https://app.devin.ai/api/org-a1b2c3d4e5/billing/quota/usage' \\\n"
            + "  -H 'accept: application/json' \\\n"
            + "  -H 'authorization: Bearer auth1_abcdefghijklmnopqrstuvwxyz012345' \\\n"
            + "  -H 'x-cog-org-id: org-a1b2c3d4e5'";
        Assert.Equal(("auth1_abcdefghijklmnopqrstuvwxyz012345", "org-a1b2c3d4e5"), DeviceAuth.ParseDevinRequest(curl));
        Assert.Equal(("auth1_tokenvalue1234", "org_ZYXWVU98"), DeviceAuth.ParseDevinRequest("auth1_tokenvalue1234 org_ZYXWVU98"));
        Assert.Equal("", DeviceAuth.ParseDevinRequest("hello").Token);
    }
}
