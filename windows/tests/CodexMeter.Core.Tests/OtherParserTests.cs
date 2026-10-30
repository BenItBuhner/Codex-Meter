using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>Mirrors iOS <c>ClaudeUsageParserTests</c> / Android <c>testClaudeUsageParsing</c>.</summary>
public class ClaudeUsageParserTests
{
    private static readonly DateTimeOffset At = DateTimeOffset.FromUnixTimeSeconds(1_000);

    private const string FullBody = """
        {
          "five_hour": {"utilization": 6.0, "resets_at": "2026-10-19T18:59:59.943648+00:00"},
          "seven_day": {"utilization": 35.4, "resets_at": "2026-10-23T03:00:00Z"},
          "seven_day_opus": {"utilization": 0.0, "resets_at": null},
          "seven_day_sonnet": {"utilization": 100, "resets_at": null},
          "iguana_necktie": null,
          "limits": [
            {"kind": "session", "group": "session", "percent": 41, "severity": "normal",
             "resets_at": "2026-10-19T18:59:59.943648+00:00", "scope": null, "is_active": true},
            {"kind": "weekly_all", "group": "weekly", "percent": 20, "severity": "normal",
             "resets_at": "2026-10-23T03:00:00Z", "scope": null, "is_active": false},
            {"kind": "weekly_scoped", "group": "weekly", "percent": 100, "severity": "critical",
             "resets_at": "2026-10-23T03:00:00Z",
             "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}},
            {"kind": "weekly_scoped", "group": "weekly", "percent": 20,
             "scope": {"model": {"id": null, "display_name": "All models"}, "surface": null}},
            {"kind": "weekly_scoped", "group": "weekly", "percent": null,
             "scope": {"model": {"id": null, "display_name": "Other"}, "surface": null}}
          ],
          "extra_usage": {"is_enabled": true, "monthly_limit": 5000, "used_credits": 1200, "utilization": 24.0}
        }
        """;

    [Fact]
    public void LimitsListDrivesTheCards()
    {
        var s = ClaudeUsageParser.Parse(FullBody, "Max 20x", At);
        Assert.Equal(41, s.FiveHour!.UsedPercent);
        Assert.Equal(18_000, s.FiveHour.WindowSeconds);
        Assert.Equal(Fixtures.Utc("2026-10-19T18:59:59Z"), s.FiveHour.ResetAt);
        Assert.Equal(20, s.Weekly!.UsedPercent);
        Assert.Equal(Fixtures.Utc("2026-10-23T03:00:00Z"), s.Weekly.ResetAt);
        Assert.Null(s.Monthly);
        Assert.Equal("Ultra", s.PlanType); // cached "Max 20x" is renamed
        Assert.Equal(["Fable only", "Extra usage"], s.AdditionalLimits.Select(l => l.DisplayName));
        Assert.Equal("claude_weekly_scoped_fable", s.AdditionalLimits[0].Id);
        Assert.True(s.AdditionalLimits[0].LimitReached);
        Assert.Equal("claude_extra_usage", s.AdditionalLimits[1].Id);
        Assert.Equal(2_592_000, s.AdditionalLimits[1].Primary!.WindowSeconds);
        Assert.True(s.LimitReached);
        Assert.False(s.Allowed);
    }

    [Fact]
    public void LegacyBodyIgnoresPerModelKeys()
    {
        var s = ClaudeUsageParser.Parse("""
            {"five_hour": {"utilization": 6.0, "resets_at": null}, "seven_day": {"utilization": 35.4, "resets_at": null},
             "seven_day_opus": {"utilization": 0.0}, "seven_day_sonnet": {"utilization": 100}, "seven_day_fable": {"utilization": 51}}
            """, "", At);
        Assert.Equal(6, s.FiveHour!.UsedPercent);
        Assert.Equal(35, s.Weekly!.UsedPercent);
        Assert.Equal(["Fable only"], s.AdditionalLimits.Select(l => l.DisplayName));
        Assert.False(s.LimitReached);
    }

    [Fact]
    public void NullWindowsAndDisabledExtraUsageAreSkipped()
    {
        var s = ClaudeUsageParser.Parse("""
            {"five_hour": null, "seven_day": {"utilization": 12.6, "resets_at": null}, "extra_usage": {"is_enabled": false, "utilization": null}}
            """, "", At);
        Assert.Null(s.FiveHour);
        Assert.Equal(13, s.Weekly!.UsedPercent);
        Assert.Empty(s.AdditionalLimits);
        Assert.True(s.Allowed);
    }

    [Fact]
    public void UnknownScopedMetersGetGenericCards()
    {
        var s = ClaudeUsageParser.Parse("""
            {"limits": [
              {"kind": "weekly_scoped", "group": "weekly", "percent": 7,
               "scope": {"model": null, "surface": {"id": "claude_design", "display_name": "Claude Design"}}},
              {"kind": "monthly_new_thing", "group": "monthly", "percent": 99.6, "scope": null}
            ], "extra_usage": {"is_enabled": true, "utilization": null}}
            """, "", At);
        Assert.Equal(["claude_weekly_scoped_claude_design", "claude_monthly_new_thing"], s.AdditionalLimits.Select(l => l.Id));
        Assert.Equal(["Claude Design only", "Monthly new thing"], s.AdditionalLimits.Select(l => l.DisplayName));
        Assert.Equal(2_592_000, s.AdditionalLimits[1].Primary!.WindowSeconds);
        Assert.Equal(100, s.AdditionalLimits[1].Primary!.UsedPercent);
        Assert.True(s.LimitReached);
    }

    [Fact]
    public void ResetTimestamps()
    {
        Assert.Null(ClaudeUsageParser.ParseIsoDate("not a date"));
        Assert.Null(ClaudeUsageParser.ParseIsoDate(null));
        Assert.Null(ClaudeUsageParser.ParseIsoDate("2026-10-19T18:59:59"));
        Assert.Equal(Fixtures.Utc("2026-10-19T18:59:59Z"), ClaudeUsageParser.ParseIsoDate("2026-10-19T18:59:59.9+00:00"));
        Assert.Equal(Fixtures.Utc("2026-10-19T18:59:59Z"), ClaudeUsageParser.ParseIsoDate("2026-10-19T20:59:59+02:00"));
    }

    [Fact]
    public void MalformedRootThrows()
    {
        Assert.Throws<UsageParsingException>(() => ClaudeUsageParser.Parse("[]", "", At));
        Assert.Throws<UsageParsingException>(() => ClaudeUsageParser.Parse("not json", "", At));
    }

    [Theory]
    [InlineData("Max 20x", "Ultra")]
    [InlineData("max_20x", "Ultra")]
    [InlineData("default_claude_max_20x", "Ultra")]
    [InlineData(" ultra ", "Ultra")]
    [InlineData("Max 5x", "Max")]
    [InlineData("max-5x", "Max")]
    [InlineData("max", "Max")]
    [InlineData("Pro", "Pro")]
    [InlineData("", "")]
    [InlineData(null, "")]
    public void PlanAliasesNormalize(string? label, string expected) =>
        Assert.Equal(expected, ClaudeUsageParser.NormalizePlanLabel(label));

    [Fact]
    public void VerifiedUltraHasNoFiveHourWindow()
    {
        Assert.Equal("Ultra", ClaudeUsageParser.PlanLabel("""{"organization":{"rate_limit_tier":"default_claude_ultra"}}"""));
        Assert.Equal("Ultra", ClaudeUsageParser.PlanLabel("""{"organization":{"organization_type":"claude_ultra"}}"""));
        var ultra = ClaudeUsageParser.Parse("""{"limits":[{"kind":"weekly_all","group":"weekly","percent":30,"resets_at":"2026-11-02T03:00:00Z"}],"five_hour":null,"seven_day":null}""", "Ultra", At);
        Assert.Null(ultra.FiveHour);
        Assert.Equal(30, ultra.Weekly!.UsedPercent);
        Assert.False(ultra.LimitReached);
        Assert.True(ClaudeUsageParser.NoFiveHourLimit(ultra));

        const string idle = """{"five_hour":{"utilization":0,"resets_at":null},"seven_day":{"utilization":12,"resets_at":null}}""";
        var ultraIdle = ClaudeUsageParser.Parse(idle, "Max 20x", At);
        Assert.Null(ultraIdle.FiveHour);
        Assert.Equal("Ultra", ultraIdle.PlanType);
        var maxIdle = ClaudeUsageParser.Parse(idle, "Max 5x", At);
        Assert.NotNull(maxIdle.FiveHour);
        Assert.Equal("Max", maxIdle.PlanType);
        Assert.False(ClaudeUsageParser.NoFiveHourLimit(maxIdle));

        Assert.Equal(41, ClaudeUsageParser.Parse(FullBody, "Ultra", At).FiveHour!.UsedPercent);
        Assert.True(ClaudeUsageParser.Parse("""{"five_hour":null,"seven_day":{"utilization":100,"resets_at":"2026-11-02T03:00:00Z"}}""", "Ultra", At).LimitReached);
    }

    [Fact]
    public void PlanLabelsFromProfile()
    {
        Assert.Equal("Ultra", ClaudeUsageParser.PlanLabel("""{"organization":{"organization_type":"claude_max","rate_limit_tier":"default_claude_max_20x"}}"""));
        Assert.Equal("Max", ClaudeUsageParser.PlanLabel("""{"organization":{"rate_limit_tier":"default_claude_max_5x"}}"""));
        Assert.Equal("Pro", ClaudeUsageParser.PlanLabel("""{"account":{"has_claude_pro":true},"organization":{}}"""));
        Assert.Equal("Max", ClaudeUsageParser.PlanLabel("""{"account":{"has_claude_max":true}}"""));
        Assert.Equal("Team", ClaudeUsageParser.PlanLabel("""{"organization":{"organization_type":"claude_team"}}"""));
        Assert.Equal("Enterprise", ClaudeUsageParser.PlanLabel("""{"organization":{"organization_type":"claude_enterprise"}}"""));
        Assert.Equal("", ClaudeUsageParser.PlanLabel("not json"));
        Assert.Equal("me@example.com", ClaudeUsageParser.Email("""{"account":{"email_address":"me@example.com"}}"""));
        Assert.Equal("", ClaudeUsageParser.Email("nope"));
    }

    [Fact]
    public void AuthorizeUrlMatchesTheCli()
    {
        var url = ClaudeCodeService.BuildAuthorizeUrl("http://localhost:54545/callback", "challenge-value", "state-value").AbsoluteUri;
        Assert.StartsWith($"https://claude.ai/oauth/authorize?code=true&client_id={ClaudeCodeService.ClientId}&response_type=code&", url);
        Assert.Contains("redirect_uri=http%3A%2F%2Flocalhost%3A54545%2Fcallback", url);
        Assert.Contains("scope=user%3Aprofile+user%3Ainference", url);
        Assert.Contains("code_challenge=challenge-value&code_challenge_method=S256", url);
        Assert.EndsWith("&state=state-value", url);
        Assert.Equal("auth-code", ClaudeCodeService.StripStateSuffix(" auth-code#state "));
    }

    [Fact]
    public void JsonRetryRule()
    {
        Assert.True(ClaudeCodeService.ShouldRetryAsJson(415, ""));
        Assert.True(ClaudeCodeService.ShouldRetryAsJson(400, """{"error":"invalid_request"}"""));
        Assert.False(ClaudeCodeService.ShouldRetryAsJson(400, """{"error":"invalid_grant"}"""));
        Assert.False(ClaudeCodeService.ShouldRetryAsJson(401, ""));
        Assert.False(ClaudeCodeService.ShouldRetryAsJson(200, ""));
    }
}

/// <summary>Mirrors iOS <c>CursorUsageParserTests</c>.</summary>
public class CursorUsageParserTests
{
    private static readonly DateTimeOffset At = DateTimeOffset.FromUnixTimeSeconds(1_000);

    [Fact]
    public void MapsPlanPoolsAndOnDemand()
    {
        var s = CursorUsageParser.Parse("""
            {"billingCycleStart":"1791676800000","billingCycleEnd":"2026-11-11T00:00:00Z",
             "planUsage":{"totalSpend":500,"includedSpend":400,"bonusSpend":100,"remaining":1500,
               "limit":2000,"totalPercentUsed":25,"autoPercentUsed":10,"apiPercentUsed":15},
             "spendLimitUsage":{"totalSpend":300,"individualLimit":1000,"individualUsed":300,
               "individualRemaining":700,"pooledLimit":"20000","pooledUsed":5000,"limitType":"team"},
             "enabled":true,"serverTimeOffsetMs":123}
            """, "Pro", At);
        var end = Fixtures.Utc("2026-11-11T00:00:00Z");
        Assert.Equal(25, s.Monthly!.UsedPercent);
        Assert.Equal(end, s.Monthly.ResetAt);
        Assert.Equal(end.ToUnixTimeSeconds() - 1_791_676_800, s.Monthly.WindowSeconds);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Weekly);
        Assert.Equal(["cursor_auto", "cursor_api", "cursor_on_demand"], s.AdditionalLimits.Select(l => l.Id));
        Assert.Equal("Cursor models", s.AdditionalLimits[0].Name);
        Assert.Equal(10, s.AdditionalLimits[0].Primary!.UsedPercent);
        Assert.Equal("On-demand ($3 of $10)", s.AdditionalLimits[2].Name);
        Assert.Equal(30, s.AdditionalLimits[2].Primary!.UsedPercent);
        Assert.False(s.LimitReached);
    }

    [Fact]
    public void WeeklyPlanUsesWeeklyWindowWithoutPools()
    {
        var s = CursorUsageParser.Parse("""
            {"billingCycleStart":"1791676800000","billingCycleEnd":"1792281600000",
             "planUsage":{"totalPercentUsed":42,"autoPercentUsed":30,"apiPercentUsed":12},
             "spendLimitUsage":{"individualLimit":2000,"individualUsed":420}}
            """, "Pro", At);
        Assert.Equal(42, s.Weekly!.UsedPercent);
        Assert.Equal(604_800, s.Weekly.WindowSeconds);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(1_792_281_600), s.Weekly.ResetAt);
        Assert.Null(s.Monthly);
        Assert.Equal(["cursor_on_demand"], s.AdditionalLimits.Select(l => l.Id));
        Assert.Equal("Additional usage ($4.20 of $20)", s.AdditionalLimits[0].Name);
        Assert.Equal(21, s.AdditionalLimits[0].Primary!.UsedPercent);

        var team = CursorUsageParser.Parse("""
            {"billingCycleStart":1791676800000,"billingCycleEnd":1792281600000,"planUsage":{"totalPercentUsed":5},
             "spendLimitUsage":{"pooledLimit":"5000","pooledUsed":100}}
            """, "", At);
        Assert.Equal(["Team additional usage ($1 of $50)"], team.AdditionalLimits.Select(l => l.Name));
        Assert.True(CursorUsageParser.IsWeeklyCycle(10 * 86_400));
        Assert.False(CursorUsageParser.IsWeeklyCycle(10 * 86_400 + 1));
        Assert.False(CursorUsageParser.IsWeeklyCycle(0));
    }

    [Fact]
    public void ComputesPercentAndFallsBackToTeamPool()
    {
        var s = CursorUsageParser.Parse("""
            {"billingCycleStart":1791676800000,"billingCycleEnd":"1794355200000",
             "planUsage":{"includedSpend":2000,"limit":2000},"spendLimitUsage":{"pooledLimit":"5000","pooledUsed":"5000"}}
            """, "", At);
        Assert.Equal(100, s.Monthly!.UsedPercent);
        Assert.Equal("Team on-demand ($50 of $50)", s.AdditionalLimits[0].Name);
        Assert.True(s.AdditionalLimits[0].LimitReached);
        Assert.True(s.LimitReached);
    }

    [Fact]
    public void LightUsageAndMissingCycle()
    {
        var light = CursorUsageParser.Parse("""{"planUsage":{"totalPercentUsed":0.36}}""", "", At);
        Assert.Equal(1, light.Monthly!.UsedPercent);
        Assert.Equal(CursorUsageParser.DefaultCycleSeconds, light.Monthly.WindowSeconds);
        Assert.Null(light.Monthly.ResetAt);
        var oneSided = CursorUsageParser.Parse("""{"planUsage":{"limit":2000,"autoPercentUsed":40}}""", "", At);
        Assert.Equal(0, oneSided.Monthly!.UsedPercent);
        Assert.Equal(["cursor_auto", "cursor_api"], oneSided.AdditionalLimits.Select(l => l.Id));
        var empty = CursorUsageParser.Parse("""{"enabled":false}""", "", At);
        Assert.Null(empty.Monthly);
        Assert.Equal("$12.50", CursorUsageParser.Dollars(1_250));
        Assert.Equal("$20", CursorUsageParser.Dollars(2_000));
    }

    [Fact]
    public void PlanLabelsTokensAndAuthDetection()
    {
        Assert.Equal("Pro+", CursorUsageParser.PlanLabel("""{"membershipType":"pro_plus"}"""));
        Assert.Equal("Hobby", CursorUsageParser.PlanLabel("""{"membershipType":"free"}"""));
        Assert.Equal("Teams", CursorUsageParser.PlanLabel("""{"membership_type":"team"}"""));
        Assert.Equal("", CursorUsageParser.PlanLabel("nope"));
        Assert.Equal("me@example.com", CursorUsageParser.Email("""{"email":"me@example.com"}"""));
        Assert.Equal("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.S256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));

        var polled = CursorService.ParseTokenResponse("""{"authId":"auth0|user_01ABC","accessToken":"a","refreshToken":"r"}""");
        Assert.Equal(("a", "r", "auth0|user_01ABC"), (polled.Access, polled.Refresh, polled.AuthId));
        Assert.Equal("new", CursorService.ParseTokenResponse("""{"access_token":"new"}""").Refresh);
        Assert.True(CursorService.ParseTokenResponse("""{"shouldLogout":true}""").ShouldLogout);
        Assert.True(CursorService.IsUnauthenticated(401, ""));
        Assert.True(CursorService.IsUnauthenticated(200, """{"code":"unauthenticated"}"""));
        Assert.False(CursorService.IsUnauthenticated(200, """{"planUsage":{}}"""));
        Assert.True(CursorService.IsBotCheckpoint(403, "<html>Vercel Security Checkpoint</html>"));
        Assert.Equal($"https://cursor.com/loginDeepControl?challenge=c&uuid=u&mode=login", CursorService.BuildLoginUrl("c", "u").AbsoluteUri);
    }
}

public class ResetCreditsAndClaimsTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.FromUnixTimeSeconds(1_783_807_200);

    [Fact]
    public void ParsesCreditsAndSelectsSoonestUnexpiredAvailableCredit()
    {
        var s = ResetCreditsParser.Parse(Fixtures.Read("reset-credits"), Now);
        Assert.Equal(4, s.AvailableCount);
        Assert.Equal(5, s.Credits.Count);
        Assert.Equal("soon", s.NextExpiringAvailable(Now)!.Id);
        Assert.Equal("Soon credit", s.NextExpiringAvailable(Now)!.Title);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(1_784_543_400), s.NextExpiringAvailable(Now)!.ExpiresAt);
        Assert.Null(s.Credits[3].GrantedAt);
        Assert.Null(s.Credits[3].ExpiresAt);
    }

    [Fact]
    public void DerivedAndClampedCounts()
    {
        var derived = ResetCreditsParser.Parse(Fixtures.Read("reset-credits-derived-count"), Now);
        Assert.Equal(2, derived.AvailableCount);
        Assert.Equal(3, derived.Credits.Count);
        Assert.Equal(0, ResetCreditsParser.Parse("""{"available_count":-9}""", Now).AvailableCount);
        Assert.Equal(0, ResetCreditsParser.Parse(null, Now).AvailableCount);
    }

    [Fact]
    public void NoExpirySortsAfterPositiveExpiryButRemainsEligible()
    {
        var s = new ResetCreditsSnapshot(2,
            [new RateLimitResetCredit("expired", "", "available", expiresAt: Now.AddSeconds(-1)), new RateLimitResetCredit("none", "", "available")], Now);
        Assert.Equal("none", s.NextExpiringAvailable(Now)!.Id);
    }

    [Fact]
    public void JwtAccountClaimPrecedence()
    {
        var direct = Jwt.Make("""{"chatgpt_account_id":"direct","email":"person@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"nested"},"organizations":[{"id":"organization"}]}""");
        Assert.Equal(new JwtClaims("direct", "person@example.com"), JwtClaims.Parse(direct));
        Assert.Equal("nested", JwtClaims.Parse(Jwt.Make("""{"https://api.openai.com/auth":{"chatgpt_account_id":"nested"},"organizations":[{"id":"organization"}]}""")).AccountId);
        Assert.Equal("organization", JwtClaims.Parse(Jwt.Make("""{"organizations":[{"id":"organization"}]}""")).AccountId);
        var merged = JwtClaims.FromTokens(Jwt.Make("""{"email":"person@example.com"}"""), Jwt.Make("""{"chatgpt_account_id":"acct_123","email":"ignored@example.com"}"""));
        Assert.Equal(new JwtClaims("acct_123", "person@example.com"), merged);
        var empty = new JwtClaims("", "");
        Assert.Equal(empty, JwtClaims.Parse(null));
        Assert.Equal(empty, JwtClaims.Parse("one.two"));
        Assert.Equal(empty, JwtClaims.Parse("one.@@@.three"));
        Assert.Equal(empty, JwtClaims.Parse("one.W10.three"));
    }

    [Fact]
    public void ConsumeOutcomeMessages()
    {
        Assert.Equal(ResetConsumeOutcome.NothingToReset, ResetConsumeResult.ParseOutcome("nothing_to_reset"));
        Assert.Equal("Reset applied to 2 usage windows.", new ResetConsumeResult(ResetConsumeOutcome.Reset, 2).UserMessage);
        Assert.Equal("Reset applied to 1 usage window.", new ResetConsumeResult(ResetConsumeOutcome.Reset, 1).UserMessage);
        Assert.Equal("No reset credit is currently available.", new ResetConsumeResult(ResetConsumeOutcome.NoCredit, 0).UserMessage);
    }
}
