using CodexMeter.Core.Auth;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>Device-code, paste and API-key sign-in for added providers, against scripted HTTP.</summary>
public class AddedProviderFlowTests
{
    [Fact]
    public async Task CopilotDeviceSignInThenRefresh()
    {
        var http = new FakeHttp()
            .On("POST", "https://github.com/login/device/code", (_, body) =>
            {
                Assert.Contains("client_id=Iv1.b507a08c87ecfe98", body);
                return (200, """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","interval":1}""");
            })
            .Once("POST", "https://github.com/login/oauth/access_token", 200, """{"error":"authorization_pending"}""")
            .On("POST", "https://github.com/login/oauth/access_token", 200, """{"access_token":"gho_1","token_type":"bearer"}""")
            .On("GET", "https://api.github.com/user", 200, """{"login":"octo"}""")
            .On("GET", "https://api.github.com/copilot_internal/user", (request, _) =>
            {
                Assert.Equal("token gho_1", request.Headers.Authorization!.ToString());
                return (200, """{"copilot_plan":"individual","quota_reset_date":"2026-10-01","quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":75}}}""");
            });
        var context = http.Context();
        var service = AddedProviderService.Create(UsageProvider.Copilot, context)!;
        DeviceCode? shown = null;
        var tokens = await service.SignInAsync(new SignInPrompt(_ => Task.CompletedTask)
        {
            ShowDeviceCode = code => { shown = code; return Task.CompletedTask; },
        }, CancellationToken.None);
        Assert.Equal("ABCD-1234", shown?.UserCode);
        Assert.Equal("octo", tokens.Email);
        Assert.Equal(AuthTokens.NeverExpires, tokens.ExpiresAt);
        var usage = await service.RefreshAsync();
        Assert.Equal(75, usage.Monthly?.UsedPercent);
        Assert.Equal("Pro", usage.PlanType);
    }

    [Fact]
    public async Task ApiKeyIsCheckedBeforeSaving()
    {
        var http = new FakeHttp()
            .On("GET", "https://api.z.ai/api/monitor/usage/quota/limit", (request, _) =>
                request.Headers.Authorization?.Parameter == "good"
                    ? (200, """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":44}]}}""")
                    : (401, """{"message":"bad key"}"""));
        var store = new MemoryTokenStore();
        var service = AddedProviderService.Create(UsageProvider.Zai, http.Context(tokens: store))!;
        var error = await Assert.ThrowsAsync<ProviderUnauthorizedException>(() => service.SignInWithApiKeyAsync(" bad ", CancellationToken.None));
        Assert.True(error.SignedOut);
        Assert.Null(await store.LoadAsync("zai"));
        var tokens = await service.SignInWithApiKeyAsync(" good\n", CancellationToken.None);
        Assert.True(tokens.IsApiKey);
        Assert.Equal("good", (await store.LoadAsync("zai"))!.AccessToken);
        Assert.Equal(44, (await service.RefreshAsync()).FiveHour?.UsedPercent);
    }

    [Fact]
    public async Task AugmentPasteSignInChecksTenantAndState()
    {
        var http = new FakeHttp()
            .On("POST", "https://d1.api.augmentcode.com/token", (_, body) =>
            {
                Assert.Contains("\"code\":\"c1\"", body);
                return (200, """{"access_token":"aug"}""");
            })
            .On("POST", "https://d1.api.augmentcode.com/get-billing-summary", 200,
                """{"amount_remaining":30000,"amount_included_per_cycle":40000}""");
        var service = AddedProviderService.Create(UsageProvider.Augment, http.Context())!;
        Uri? page = null;
        var tokens = await service.SignInAsync(new SignInPrompt(_ => Task.CompletedTask)
        {
            RequestPaste = (url, hint, _) =>
            {
                page = url;
                Assert.Contains("{\"code\"", hint);
                return Task.FromResult<string?>("""{"code":"c1","tenant_url":"https://d1.api.augmentcode.com"}""");
            },
        }, CancellationToken.None);
        Assert.StartsWith("https://auth.augmentcode.com/authorize?", page!.ToString());
        Assert.Equal("https://d1.api.augmentcode.com/", tokens.AccountId);

        var evil = AddedProviderService.Create(UsageProvider.Augment, new FakeHttp().Context())!;
        var error = await Assert.ThrowsAsync<ProviderException>(() => evil.SignInAsync(new SignInPrompt(_ => Task.CompletedTask)
        {
            RequestPaste = (_, _, _) => Task.FromResult<string?>("""{"code":"c1","tenant_url":"https://evil.example.com"}"""),
        }, CancellationToken.None));
        Assert.Contains("unexpected server", error.Message);
    }

    [Fact]
    public async Task ExpiredTokensRefreshBeforeFetching()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_790_000_000);
        var http = new FakeHttp()
            .On("POST", "https://auth.kimi.com/api/oauth/token", (_, body) =>
            {
                Assert.Contains("grant_type=refresh_token", body);
                Assert.Contains("refresh_token=r1", body);
                return (200, """{"access_token":"a2","refresh_token":"r2","expires_in":3600}""");
            })
            .On("GET", "https://api.kimi.com/coding/v1/usages", (request, _) =>
            {
                Assert.Equal("a2", request.Headers.Authorization!.Parameter);
                return (200, """{"usages":{"limit_5h":{"used_ratio":0.4}}}""");
            });
        var store = new MemoryTokenStore();
        await store.SaveAsync("kimi", new AuthTokens("a1", "r1", "", now.AddMinutes(-1)));
        var service = AddedProviderService.Create(UsageProvider.Kimi, http.Context(now, store))!;
        Assert.Equal(40, (await service.RefreshAsync()).FiveHour?.UsedPercent);
        var saved = (await store.LoadAsync("kimi"))!;
        Assert.Equal("r2", saved.RefreshToken);
        Assert.Equal(now.AddHours(1), saved.ExpiresAt);
    }

    [Fact]
    public void LiveHubConnectsEveryAvailableProvider()
    {
        var hub = ProviderHub.Live(new FakeHttp().Context());
        Assert.Equal(UsageProvider.All.Where(p => p.IsAvailable), hub.Providers);
        Assert.False(hub.Supports(UsageProvider.Antigravity));
        Assert.IsType<AddedProviderService>(hub[UsageProvider.Copilot]);
    }
}
