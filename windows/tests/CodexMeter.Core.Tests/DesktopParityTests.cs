using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>
/// Windows and Linux parity with Android: official status feeds, model slowdowns, Claude Code saved
/// resets, settings export/import, diagnostic logs and Antigravity.
/// </summary>
public sealed class DesktopParityTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-16T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture);

    private const string ClaudeSummary = """
        {"page":{"name":"Claude"},
         "components":[{"name":"claude.ai","status":"major_outage"},{"name":"Claude API","status":"operational"},
                       {"name":"Claude Code","status":"degraded_performance"},{"name":"Group","status":"major_outage","group":true}],
         "incidents":[{"name":"Elevated errors on Claude Code","status":"investigating","impact":"major","shortlink":"https://stspg.io/x",
                       "started_at":"2026-11-16T11:30:00.000Z","components":[{"name":"Claude Code","status":"partial_outage"}]},
                      {"name":"Old","status":"resolved","impact":"critical","components":[{"name":"Claude Code"}]}]}
        """;

    [Fact]
    public void StatusCountsOnlyTheProvidersComponentsAndNamesThem()
    {
        var status = ProviderStatusSources.Parse(UsageProvider.ClaudeCode, ClaudeSummary, Now)!;
        // claude.ai's major outage isn't Claude Code's; the open incident raises Claude Code to partial.
        Assert.Equal(ServiceState.PartialOutage, status.State);
        Assert.Equal("Claude Code: partial outage", status.Headline("Claude Code"));
        Assert.Equal("Elevated errors on Claude Code", status.Detail);
        Assert.Equal(Now.AddMinutes(-30), status.Issues[0].StartedAt);
        Assert.True(status.ShouldShowCard);
    }

    [Fact]
    public void StatusIsUnknownNeverDownWhenTheFeedIsWrongOrSilent()
    {
        Assert.Equal(ServiceState.Unknown, ProviderStatusSources.Parse(UsageProvider.ClaudeCode, "<html>", Now)!.State);
        // A feed that answers for another page is not trusted.
        var other = ClaudeSummary.Replace("\"Claude\"}", "\"Someone else\"}");
        Assert.Equal(ServiceState.Unknown, ProviderStatusSources.Parse(UsageProvider.ClaudeCode, other, Now)!.State);
        var unlisted = """{"page":{"name":"OpenAI"},"components":[{"name":"ChatGPT","status":"major_outage"}]}""";
        var codex = ProviderStatusSources.Parse(UsageProvider.Codex, unlisted, Now)!;
        Assert.Equal(ServiceState.Unknown, codex.State);
        Assert.False(codex.ShouldShowCard);
        Assert.Null(ProviderStatusSources.For(UsageProvider.Antigravity));
    }

    [Fact]
    public void GrokReadsTheRssIncidentFeed()
    {
        const string rss = """
            <rss version="2.0"><channel>
            <item><title>[Grok Bot] Slow replies</title><link>https://status.x.ai/i/1</link><pubDate>Mon, 16 Nov 2026 11:00:00 GMT</pubDate><category>disruption</category></item>
            <item><title>[grok.com] Fixed thing</title><category>outage</category><category>resolved</category></item>
            <item><title>[Other] Down</title><category>outage</category></item>
            </channel></rss>
            """;
        var status = ProviderStatusSources.Parse(UsageProvider.Grok, rss, Now)!;
        Assert.Equal(ServiceState.Degraded, status.State);
        Assert.Equal("Grok Bot: degraded performance", status.Headline("Grok"));
        Assert.Equal(Now.AddHours(-1), status.Issues.Single().StartedAt);
        var quiet = ProviderStatusSources.Parse(UsageProvider.Grok, "<rss><channel></channel></rss>", Now)!;
        Assert.Equal("Grok: no active incidents reported", quiet.Headline("Grok"));
    }

    [Fact]
    public void PollPolicyBacksOffAndHonoursRetryAfter()
    {
        Assert.True(StatusPollPolicy.ShouldFetch(Now, null, 0, null, true, 0));
        Assert.False(StatusPollPolicy.ShouldFetch(Now.AddMinutes(4), Now, 0, null, true, 0));
        Assert.True(StatusPollPolicy.ShouldFetch(Now.AddMinutes(5), Now, 0, null, true, 0));
        Assert.False(StatusPollPolicy.ShouldFetch(Now.AddMinutes(29), Now, 0, null, false, 0));
        // Three failures: 5 minutes << 3 = 40 minutes.
        Assert.Equal(Now.AddMinutes(40), StatusPollPolicy.NextAllowedAt(Now, 3, null, true, 0));
        Assert.Equal(Now.Add(StatusPollPolicy.MaxBackoff), StatusPollPolicy.NextAllowedAt(Now, 9, null, true, 0));
        Assert.Equal(Now.Add(StatusPollPolicy.MaxBackoff), StatusPollPolicy.RetryAfterUntil(TimeSpan.FromDays(3), null, Now));
    }

    [Fact]
    public async Task StatusStoreSharesAFeedAndKeepsTheLastStatusThroughAFailure()
    {
        var calls = 0;
        var http = new FakeHttp().On("GET", "https://status.claude.com/", (_, _) => (++calls == 1 ? 200 : 503, ClaudeSummary));
        var store = new ProviderStatusStore(null, ProviderHttp.CreateClient(http));
        Assert.True(await store.RefreshAsync([UsageProvider.ClaudeCode, UsageProvider.Copilot], true, Now));
        Assert.Equal(ServiceState.PartialOutage, store.For(UsageProvider.ClaudeCode, Now)!.State);
        Assert.False(await store.RefreshAsync([UsageProvider.ClaudeCode], true, Now.AddMinutes(1)));
        Assert.Equal(1, calls);
        // The feed fails: the last status stays until it's older than the longest backoff.
        Assert.False(await store.RefreshAsync([UsageProvider.ClaudeCode], true, Now.AddMinutes(7)));
        Assert.Equal(2, calls);
        Assert.Equal(ServiceState.PartialOutage, store.For(UsageProvider.ClaudeCode, Now.AddMinutes(7))!.State);
        Assert.Null(store.For(UsageProvider.ClaudeCode, Now.AddHours(3)));
    }

    private static string N(double? value) => value?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "null";

    private static string Endpoints(double? tps, double? latency, double? uptime = 99.9) =>
        "{\"data\":{\"id\":\"anthropic/claude-fable-5\",\"name\":\"Anthropic: Claude Fable 5\",\"endpoints\":["
        + "{\"provider_name\":\"Google Vertex\",\"tag\":\"google-vertex\",\"throughput_last_30m\":500,\"latency_last_30m\":100},"
        + "{\"provider_name\":\"Anthropic\",\"tag\":\"anthropic\",\"throughput_last_30m\":" + (tps is null ? "null" : "{\"p50\":" + N(tps) + "}")
        + ",\"latency_last_30m\":" + N(latency) + ",\"uptime_last_30m\":" + N(uptime) + "}]}}";

    [Fact]
    public void SlowdownNeedsAHistoryAndASignificantDrop()
    {
        var speed = ModelSpeed.Parse(Endpoints(20, 900), Now)!;
        Assert.Equal("Claude Fable 5", speed.ModelName);
        Assert.Equal("Anthropic", speed.Featured()!.Provider);
        var usual = Enumerable.Repeat((60.0, 800.0), ModelSpeed.MinBaseline).ToList();
        var slow = ModelSpeed.Slowdown(speed, usual)!;
        Assert.Equal("Claude Fable 5 is running slower than usual", slow.Title);
        Assert.StartsWith("20 tokens/s, usually 60 (OpenRouter, via Anthropic", slow.Detail);
        Assert.Contains("not your subscription", slow.Detail);
        // Too little history, or a normal figure, says nothing.
        Assert.Null(ModelSpeed.Slowdown(speed, usual.Take(ModelSpeed.MinBaseline - 1).ToList()));
        Assert.Null(ModelSpeed.Slowdown(ModelSpeed.Parse(Endpoints(55, 850), Now), usual));
        // Missing figures are NaN, never zero.
        Assert.True(double.IsNaN(ModelSpeed.Parse(Endpoints(null, null, null), Now)!.Endpoints[1].Throughput));
        var failing = ModelSpeed.Slowdown(ModelSpeed.Parse(Endpoints(60, 800, 70), Now), []);
        Assert.Equal("Claude Fable 5 is failing more often than usual", failing!.Title);
    }

    [Fact]
    public async Task SpeedStoreFollowsTheNewestModelsAndBuildsAHistory()
    {
        var catalog = new ModelDiscoveryState
        {
            Baseline = true,
            Models = new()
            {
                ["anthropic/claude-fable-5"] = new SeenModel { Id = "anthropic/claude-fable-5", Created = Now.AddDays(-1) },
                ["anthropic/claude-fable-5:free"] = new SeenModel { Id = "anthropic/claude-fable-5:free", Created = Now },
                ["anthropic/claude-old"] = new SeenModel { Id = "anthropic/claude-old", Created = Now.AddYears(-1) },
                ["openai/gpt-9"] = new SeenModel { Id = "openai/gpt-9", Created = Now },
            },
        };
        Assert.Equal(["anthropic/claude-fable-5", "anthropic/claude-old"], ModelSpeedStore.TrackedModels(catalog, UsageProvider.ClaudeCode).Select(m => m.Id));
        Assert.Empty(ModelSpeedStore.TrackedModels(catalog, UsageProvider.Cursor));

        var slowNow = false;
        var http = new FakeHttp().On("GET", "https://openrouter.ai/api/v1/models/", (request, _) =>
        {
            Assert.Null(request.Headers.Authorization);
            return (200, slowNow ? Endpoints(15, 800) : Endpoints(60, 800));
        });
        var store = new ModelSpeedStore(null, ProviderHttp.CreateClient(http));
        var at = Now;
        for (var i = 0; i < ModelSpeed.MinBaseline; i++, at = at.Add(ModelSpeedStore.RefreshInterval))
            Assert.True(await store.RefreshAsync(catalog, [UsageProvider.ClaudeCode], at));
        Assert.Empty(store.Slowdowns(catalog, UsageProvider.ClaudeCode, at));
        slowNow = true;
        Assert.True(await store.RefreshAsync(catalog, [UsageProvider.ClaudeCode], at));
        Assert.False(await store.RefreshAsync(catalog, [UsageProvider.ClaudeCode], at.AddMinutes(10)));
        // Both followed models answer with the same figures here, so both are slow.
        var slowdowns = store.Slowdowns(catalog, UsageProvider.ClaudeCode, at);
        Assert.Equal(2, slowdowns.Count);
        Assert.All(slowdowns, s => Assert.Equal(4, s.Factor));
        // Figures older than two refresh intervals are too old to call current.
        Assert.Empty(store.Slowdowns(catalog, UsageProvider.ClaudeCode, at.AddHours(2)));
    }

    [Fact]
    public void PriorityPutsOutagesFirstAndDegradedWithSlowdowns()
    {
        var outage = ProviderStatusSources.Parse(UsageProvider.ClaudeCode, ClaudeSummary, Now)!;
        var degraded = new ProviderStatus("codex", ServiceState.Degraded, [new StatusIssue("Codex", ServiceState.Degraded)], "https://status.openai.com", Now);
        var report = PriorityReport.Build([], [], Now).WithService(
        [
            (UsageProvider.ClaudeCode, outage, []),
            (UsageProvider.Codex, degraded, [new ModelSlowdown("GPT-9", "GPT-9 is running slower than usual", "detail", 3)]),
            (UsageProvider.Cursor, ProviderStatus.Unknown("cursor", "", Now), []),
        ]);
        var service = Assert.Single(report.Service);
        Assert.Equal("Claude Code: partial outage", service.Title);
        Assert.Equal("https://stspg.io/x", service.Link);
        Assert.Equal(["GPT-9 is running slower than usual", "Codex: degraded performance"], report.Slow.Select(i => i.Title));
        Assert.Equal("Claude Code: partial outage.", report.Headline);
        Assert.False(report.IsEmpty);
    }

    private const string SavedResetsBody = """
        {"five_hour":{"utilization":100,"resets_at":"2026-11-16T14:00:00Z"},"seven_day":{"utilization":40,"resets_at":"2026-11-20T00:00:00Z"},
         "cedar_ember":{"eligible":true,"at_limit":true,"next_grant_id":"g_full","cooldown_until":null,
          "grants":[
            {"id":"g_full","label":"Limit reset","resets_total":1,"resets_left":1,"ends_at":"2026-11-16T12:20:00Z","clears":["five_hour","seven_day"],
             "usable_now":true,"use_requires_limit":true,"type":"full"},
            {"id":"g_fable","label":"Fable reset","resets_total":3,"resets_left":2,"ends_at":"2026-12-01T00:00:00Z","clears":["seven_day_fable"],
             "usable_now":false,"type":"weekly_scoped_fable","unavailable_reason":"needs_limit"},
            {"id":"BAD ID","resets_left":5},
            {"id":"g_gone","resets_left":1,"ends_at":"2026-11-01T00:00:00Z"}]}}
        """;

    [Fact]
    public void SavedResetsParseLikeAndroid()
    {
        var saved = ClaudeSavedResets.FromUsageBody(SavedResetsBody, Now)!;
        Assert.Equal(3, saved.Grants.Count);
        Assert.Equal(3, saved.AvailableCount(Now));
        Assert.Equal("1 Full reset and 2 Fable resets available", saved.AvailableTitle(Now));
        var fable = saved.Grants[1];
        Assert.Equal("Fable", fable.Scope);
        Assert.Equal("Fable reset", fable.Title);
        Assert.False(fable.ClearsWeekly);
        Assert.Equal("Works once you hit your Fable limit", fable.HeldText);
        Assert.Equal("g_full", saved.UsableGrant(Now)!.Id);
        Assert.Equal("g_full", saved.UsableGrantFor("weekly", Now)!.Id);
        var credits = saved.ToResetCredits(Now);
        Assert.Equal(["g_full", "g_fable#1", "g_fable#2"], credits.Credits.Select(c => c.Id));
        Assert.Equal("g_fable", ClaudeSavedResets.GrantIdOf("g_fable#2"));
        Assert.Equal(2, saved.AfterUse("g_full").AvailableCount(Now));
        Assert.Null(ClaudeSavedResets.FromUsageBody("""{"five_hour":{}}""", Now));
        Assert.Throws<ArgumentException>(() => ClaudeSavedResets.ClaimBody("BAD ID", "r1"));
        Assert.True(ClaudeSavedResets.NeedsClaudeCodeSignIn("user:profile user:inference"));
        Assert.False(ClaudeSavedResets.NeedsClaudeCodeSignIn(ClaudeCodeService.Scope));
        Assert.False(ClaudeSavedResets.NeedsClaudeCodeSignIn(""));
        var report = ClaudeSavedResets.DescribeCheck(200, SavedResetsBody, "user:profile", Now);
        Assert.Contains("Resets you can use: 3", report);
        Assert.Contains("lacks Claude Code access", report);
    }

    [Fact]
    public void ClaudeAutoUseSpendsAnExpiringResetOnce()
    {
        var saved = ClaudeSavedResets.FromUsageBody(SavedResetsBody, Now)!;
        var expiring = ClaudeResetAutoUse.Evaluate(saved, null, true, 30, false, 1, LowUsageAutoUseMetric.Both, null, null, Now);
        Assert.Equal(ClaudeAutoUseOutcome.FireExpiring, expiring.Outcome);
        Assert.Equal("g_full", expiring.Grant!.Id);
        Assert.Equal(ClaudeAutoUseOutcome.SkipAlreadyTried,
            ClaudeResetAutoUse.Evaluate(saved, null, true, 30, false, 1, LowUsageAutoUseMetric.Both, expiring.AttemptKey, null, Now).Outcome);
        // A stale status never fires.
        Assert.Equal(ClaudeAutoUseOutcome.Wait,
            ClaudeResetAutoUse.Evaluate(saved, null, true, 30, false, 1, LowUsageAutoUseMetric.Both, null, null, Now.AddMinutes(5)).Outcome);
        var usage = new UsageSnapshot("Pro", false, true, new UsageWindow(100, 18000, 7200, null), new UsageWindow(40, 604800, 300000, null),
            null, null, null, null, null, Now);
        var low = ClaudeResetAutoUse.Evaluate(saved, usage, false, 30, true, 1, LowUsageAutoUseMetric.Both, null, null, Now);
        Assert.Equal(ClaudeAutoUseOutcome.FireLowUsage, low.Outcome);
        Assert.Equal("5-hour", low.WindowLabel);
    }

    [Fact]
    public async Task ClaudeRefreshCachesSavedResetsAndUsesOne()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("claude_code", new AuthTokens("a", "r", "", Now.AddHours(8), "org-1", "me@example.com", ClaudeCodeService.Scope));
        var claimed = false;
        var http = new FakeHttp()
            .On("GET", ClaudeCodeService.UsageWithSavedResetsUrl, (request, _) =>
            {
                Assert.Equal(ClaudeCodeService.CliUserAgent, string.Join(" ", request.Headers.GetValues("User-Agent")));
                Assert.Equal("cli", request.Headers.GetValues("x-app").Single());
                return (200, claimed ? SavedResetsBody.Replace("\"resets_left\":1,\"ends_at\":\"2026-11-16T12:20", "\"resets_left\":0,\"ends_at\":\"2026-11-16T12:20") : SavedResetsBody);
            })
            .On("GET", ClaudeCodeService.ProfileUrl, 200, "{}")
            .On("POST", "https://api.anthropic.com/api/organizations/org-1/reset_rate_limits", (_, body) =>
            {
                Assert.Contains("\"program\":\"cedar_ember\"", body);
                Assert.Contains("\"grant_id\":\"g_full\"", body);
                claimed = true;
                return (200, """{"result":"reset","resets_left":0,"cleared":["five_hour","seven_day"]}""");
            });
        var service = new ClaudeCodeService(http.Context(Now, store));
        await service.RefreshAsync();
        var cache = (await service.CachedAsync())!;
        Assert.Equal(3, cache.Credits!.AvailableCount);
        Assert.Equal("g_full", cache.SavedResets!.NextGrantId);
        var result = await new ProviderHubShim(service).UseAsync();
        Assert.True(result.Applied);
        Assert.Equal("Reset applied to 2 usage windows.", result.UserMessage);
        Assert.Equal(2, (await service.CachedAsync())!.Credits!.AvailableCount);
    }

    /// <summary>Spends through the same conversion the hub uses.</summary>
    private sealed class ProviderHubShim(ClaudeCodeService service)
    {
        public async Task<ResetConsumeResult> UseAsync() => (await service.UseSavedResetAsync()).ToConsumeResult();
    }

    [Fact]
    public async Task OlderClaudeSignInsAskForClaudeCodeScopesOnRefresh()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("claude_code", new AuthTokens("old", "r1", "", Now.AddMinutes(1), "org-1", "", "user:profile user:inference"));
        var asked = new List<string>();
        var http = new FakeHttp()
            .On("POST", ClaudeCodeService.TokenUrl, (_, body) =>
            {
                asked.Add(body);
                return body.Contains("scope", StringComparison.Ordinal)
                    ? (400, """{"error":"invalid_scope"}""")
                    : (200, """{"access_token":"new","refresh_token":"r2","expires_in":3600,"scope":"user:profile user:inference"}""");
            })
            .On("GET", ClaudeCodeService.UsageUrl, 200, """{"five_hour":{"utilization":10,"resets_at":"2026-11-16T14:00:00Z"}}""")
            .On("GET", ClaudeCodeService.ProfileUrl, 200, "{}");
        var service = new ClaudeCodeService(http.Context(Now, store));
        await service.RefreshAsync();
        // Form and JSON with the scope (both refused), then the plain refresh.
        Assert.Contains(asked, b => b.Contains("user%3Asessions%3Aclaude_code", StringComparison.Ordinal) || b.Contains("user:sessions:claude_code", StringComparison.Ordinal));
        Assert.DoesNotContain("scope", asked[^1], StringComparison.Ordinal);
        Assert.Equal(("new", "r2"), ((await store.LoadAsync("claude_code"))!.AccessToken, (await store.LoadAsync("claude_code"))!.RefreshToken));
    }

    [Fact]
    public void SettingsExportLeavesOutAccountsAndDeviceBookkeeping()
    {
        var settings = new AppSettings
        {
            Theme = AppTheme.Dark,
            RefreshMinutes = 30,
            EnabledProviders = ["codex", "claude_code"],
            UsedUpLimits = new() { ["codex:5h"] = 123 },
            AutoUseLastToken = "5-hour:1",
            ClaudeResets = new ClaudeResetRules { AutoUseExpiring = true, LastExpiryKey = "expiry:g:1" },
            ShowServiceStatus = false,
        };
        var file = SettingsTransfer.Export(settings, "linux", Now);
        Assert.Contains("\"format\": \"codex_meter_transfer\"", file);
        Assert.DoesNotContain("codex:5h", file);
        Assert.DoesNotContain("expiry:g:1", file);
        Assert.True(file.Replace(SettingsTransfer.Note, "").IndexOf("token", StringComparison.OrdinalIgnoreCase) < 0, file);

        var here = new AppSettings { UsedUpLimits = new() { ["cursor:monthly"] = 9 }, StartWithWindows = true };
        var imported = SettingsTransfer.Import(file, here);
        Assert.Equal(AppTheme.Dark, imported.Theme);
        Assert.Equal(30, imported.RefreshMinutes);
        Assert.Equal(["codex", "claude_code"], imported.EnabledProviders);
        Assert.False(imported.ShowServiceStatus);
        Assert.True(imported.ClaudeResets.AutoUseExpiring);
        Assert.Equal(9, imported.UsedUpLimits["cursor:monthly"]);
        Assert.True(imported.StartWithWindows);

        Assert.Throws<FormatException>(() => SettingsTransfer.Import("{\"format\":\"codex_meter_transfer\",\"version\":1,\"sections\":{\"app_settings\":{}}}", here));
        Assert.Throws<FormatException>(() => SettingsTransfer.Import("{\"format\":\"codex_meter_transfer\",\"version\":9,\"sections\":{}}", here));
        Assert.Throws<FormatException>(() => SettingsTransfer.Import("not json", here));
        var odd = SettingsTransfer.Import(file.Replace("\"refreshMinutes\": 30", "\"refreshMinutes\": 1").Replace("\"theme\": 2", "\"theme\": 77"), here);
        Assert.Equal(15, odd.RefreshMinutes);
        Assert.Equal(AppTheme.System, odd.Theme);
    }

    [Fact]
    public void DiagnosticsRedactSecretsAndRotate()
    {
        var text = DiagnosticSanitizer.Redact(
            "Authorization: Bearer abc.def-123 refresh_token=r-secret email me@example.com url https://x.test/cb?code=xyz&state=s1 key sk-ant-0123456789abcdefghij");
        Assert.DoesNotContain("abc.def-123", text);
        Assert.DoesNotContain("r-secret", text);
        Assert.DoesNotContain("me@example.com", text);
        Assert.DoesNotContain("xyz", text);
        Assert.DoesNotContain("sk-ant-0123456789abcdefghij", text);
        Assert.Equal("https://api.example.com/v1/usage", DiagnosticSanitizer.SafeUrl("https://user:pw@API.example.com/v1/usage?token=t#frag"));

        var folder = Path.Combine(Path.GetTempPath(), "cm-diag-" + Guid.NewGuid().ToString("N"));
        try
        {
            var log = new DiagnosticLog(folder);
            log.Write("usage", "ignored while off");
            Assert.False(log.HasLogs);
            log.Enabled = true;
            var line = string.Concat(Enumerable.Repeat("usage refreshed ", 95));
            for (var i = 0; i < 2200; i++) log.Write("usage", line);
            Assert.InRange(log.Bytes, DiagnosticLog.MaxFileBytes, (DiagnosticLog.MaxArchives + 1) * DiagnosticLog.MaxFileBytes);
            Assert.Equal(DiagnosticLog.MaxArchives + 1, Directory.GetFiles(Path.Combine(folder, "diagnostic-logs")).Length);
            Assert.StartsWith("# Codex Meter diagnostic log", log.Export("Codex Meter test"));
            log.Clear();
            Assert.False(log.HasLogs);
            Assert.Equal("No logs saved", DiagnosticLog.SizeLabel(0));
            Assert.Equal("1.5 MB saved", DiagnosticLog.SizeLabel(1_572_864));
        }
        finally
        {
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
        }
    }

    [Fact]
    public async Task AntigravityRefreshesWithGoogleAndReadsQuota()
    {
        var http = new FakeHttp()
            .On("POST", AntigravityOAuth.TokenUrl, (_, body) =>
            {
                Assert.Contains("grant_type=refresh_token", body);
                return (200, """{"access_token":"g-new","expires_in":3599}""");
            })
            .On("POST", "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", 200, """{"paidTier":{"id":"g1-ultra-tier"}}""")
            .On("POST", "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary", 200,
                """{"buckets":[{"bucketId":"gemini-5h","remainingFraction":0.75,"resetTime":"2026-11-16T15:00:00Z"}]}""");
        var client = new AntigravityClient();
        var context = http.Context(Now);
        var refreshed = await client.RefreshAsync(new AuthTokens("g-old", "g-refresh", "", Now, "", "me@gmail.com"), context, CancellationToken.None);
        Assert.Equal(("g-new", "g-refresh", "me@gmail.com"), (refreshed.AccessToken, refreshed.RefreshToken, refreshed.Email));
        Assert.True(client.CanRefresh(refreshed));
        var fetched = await client.FetchAsync(refreshed, context, CancellationToken.None);
        Assert.Equal("Ultra", fetched.Snapshot.PlanType);
        Assert.Equal(UsageProvider.Antigravity.IsAvailable, AntigravityOAuth.Configured);
    }
}
