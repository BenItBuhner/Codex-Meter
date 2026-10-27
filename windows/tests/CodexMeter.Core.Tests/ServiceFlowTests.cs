using System.Net;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>
/// End-to-end sign-in and refresh flows against scripted HTTP, with a real loopback server and a
/// simulated browser hitting it.
/// </summary>
public class ServiceFlowTests
{
    private static readonly HttpClient Browser = new(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false });

    private static Dictionary<string, string> Query(Uri url) => FormEncoding.ParseQuery(url.Query);

    [Fact]
    public async Task CodexBrowserSignInThenRefreshWithCredits()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_783_807_200);
        var idToken = Jwt.Make("""{"email":"dev@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct_42"}}""");
        var http = new FakeHttp()
            .On("POST", CodexService.TokenUrl, (_, body) =>
            {
                Assert.Contains("grant_type=authorization_code", body);
                Assert.Contains("code=the-code", body);
                Assert.Contains("redirect_uri=http%3A%2F%2Flocalhost%3A", body);
                return (200, $$"""{"access_token":"access-1","refresh_token":"refresh-1","id_token":"{{idToken}}","expires_in":3600}""");
            })
            .On("GET", CodexService.UsageUrl, (request, _) =>
            {
                Assert.Equal("Bearer access-1", request.Headers.Authorization!.ToString());
                Assert.Equal("acct_42", request.Headers.GetValues("ChatGPT-Account-Id").Single());
                Assert.Equal(CodexService.Originator, request.Headers.GetValues("originator").Single());
                return (200, Fixtures.Read("usage-standard"));
            })
            .On("GET", CodexService.CreditsUrl, 200, Fixtures.Read("reset-credits"));
        var service = new CodexService(http.Context(now));

        string? browserPage = null;
        var tokens = await service.SignInAsync(async url =>
        {
            var query = Query(url);
            Assert.Equal(CodexService.ClientId, query["client_id"]);
            Assert.Equal("S256", query["code_challenge_method"]);
            Assert.Equal("true", query["codex_cli_simplified_flow"]);
            var redirect = query["redirect_uri"];
            Assert.Matches(@"^http://localhost:(1455|1457)/auth/callback$", redirect);
            // A browser that also asks for a favicon and replays an old state before the real callback.
            _ = Task.Run(async () =>
            {
                var root = redirect.Replace("/auth/callback", "");
                Assert.Equal(HttpStatusCode.NotFound, (await Browser.GetAsync(root + "/favicon.ico")).StatusCode);
                Assert.Equal(HttpStatusCode.BadRequest, (await Browser.GetAsync(redirect + "?code=x&state=stale")).StatusCode);
                var response = await Browser.GetAsync($"{redirect}?code=the-code&state={Uri.EscapeDataString(query["state"])}");
                browserPage = await response.Content.ReadAsStringAsync();
            });
            await Task.CompletedTask;
        }, CancellationToken.None);

        Assert.Equal("access-1", tokens.AccessToken);
        Assert.Equal("acct_42", tokens.AccountId);
        Assert.Equal("dev@example.com", tokens.Email);
        Assert.Equal(now.AddHours(1), tokens.ExpiresAt);
        for (var i = 0; i < 50 && browserPage is null; i++) await Task.Delay(20);
        Assert.Contains("You're signed in", browserPage ?? "", StringComparison.Ordinal);

        var snapshot = await service.RefreshAsync();
        Assert.Equal(37, snapshot.FiveHour!.UsedPercent);
        Assert.Equal(4, snapshot.ResetCreditsAvailable);
        var cached = await service.CachedAsync();
        Assert.Equal(5, cached!.Credits!.Credits.Count);
        Assert.Null(cached.LastError);
    }

    [Fact]
    public async Task CodexUnauthorizedRefreshesOnceAndRetries()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_900_000_000);
        var store = new MemoryTokenStore();
        await store.SaveAsync("codex", new AuthTokens("old", "refresh-old", "", now.AddHours(1), "acct"));
        var http = new FakeHttp()
            .Once("GET", CodexService.UsageUrl, 401, """{"detail":"expired"}""")
            .On("GET", CodexService.UsageUrl, (request, _) =>
            {
                Assert.Equal("Bearer new", request.Headers.Authorization!.ToString());
                return (200, Fixtures.Read("usage-standard"));
            })
            .On("POST", CodexService.TokenUrl, (_, body) =>
            {
                Assert.Contains("\"grant_type\":\"refresh_token\"", body);
                Assert.Contains("\"refresh_token\":\"refresh-old\"", body);
                return (200, """{"access_token":"new","expires_in":600}""");
            })
            .On("GET", CodexService.CreditsUrl, 500, "<html>gateway</html>");
        var service = new CodexService(http.Context(now, store));
        var snapshot = await service.RefreshAsync();
        Assert.Equal(3, snapshot.ResetCreditsAvailable); // From the usage body; the credits route failed.
        var saved = await store.LoadAsync("codex");
        Assert.Equal("new", saved!.AccessToken);
        Assert.Equal("refresh-old", saved.RefreshToken); // Rotation preserved when omitted.
        Assert.Equal("acct", saved.AccountId);
        Assert.Equal("Could not load Codex reset credits (HTTP 500).", (await service.CachedAsync())!.CreditsError);
    }

    [Fact]
    public async Task CodexRejectedRefreshMeansSignedOut()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_900_000_000);
        var store = new MemoryTokenStore();
        await store.SaveAsync("codex", new AuthTokens("old", "refresh-old", "", now.AddMinutes(1)));
        var http = new FakeHttp().On("POST", CodexService.TokenUrl, 400, """{"error":"invalid_grant"}""");
        var error = await Assert.ThrowsAsync<ProviderException>(() => new CodexService(http.Context(now, store)).RefreshAsync());
        Assert.True(error.SignedOut);
        Assert.Contains("Sign in with ChatGPT again", error.Message);
    }

    [Fact]
    public async Task RefreshWithoutAccountAsksToSignIn()
    {
        var service = new ClaudeCodeService(new FakeHttp().Context());
        var error = await Assert.ThrowsAsync<ProviderException>(() => service.RefreshAsync());
        Assert.True(error.SignedOut);
        Assert.Equal("Sign in with Claude first.", error.Message);
        Assert.Equal("Sign in with Claude first.", (await service.CachedAsync())!.LastError);
    }

    [Fact]
    public async Task CodexConsumeResetPicksSoonestCredit()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_783_807_200);
        var store = new MemoryTokenStore();
        await store.SaveAsync("codex", new AuthTokens("a", "r", "", now.AddHours(1)));
        var http = new FakeHttp()
            .On("GET", CodexService.CreditsUrl, 200, Fixtures.Read("reset-credits"))
            .On("POST", CodexService.ConsumeUrl, (_, body) =>
            {
                Assert.Contains("\"credit_id\":\"soon\"", body);
                Assert.Contains("redeem_request_id", body);
                return (200, """{"code":"reset","windows_reset":2}""");
            })
            .On("GET", CodexService.UsageUrl, 200, Fixtures.Read("usage-standard"));
        var result = await new CodexService(http.Context(now, store)).ConsumeResetAsync();
        Assert.True(result.Applied);
        Assert.Equal("Reset applied to 2 usage windows.", result.UserMessage);
    }

    [Fact]
    public async Task ClaudeLoopbackSignInRetriesTokenAsJsonThenLoadsUsage()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_000);
        var formSeen = false;
        var http = new FakeHttp()
            .On("POST", ClaudeCodeService.TokenUrl, (request, body) =>
            {
                if (request.Content!.Headers.ContentType!.MediaType == "application/x-www-form-urlencoded")
                {
                    formSeen = true;
                    Assert.Contains("code=abc", body);
                    Assert.DoesNotContain("abc%23", body);
                    return (415, """{"error":"unsupported_media_type"}""");
                }
                Assert.Contains("\"code_verifier\"", body);
                return (200, """{"access_token":"c-access","refresh_token":"c-refresh","expires_in":28800,"account":{"email_address":"me@example.com"},"organization":{"uuid":"org-1"}}""");
            })
            .On("GET", ClaudeCodeService.UsageUrl, (request, _) =>
            {
                Assert.Equal(ClaudeCodeService.OAuthBeta, request.Headers.GetValues("anthropic-beta").Single());
                return (200, """{"limits":[{"kind":"session","group":"session","percent":12,"scope":null},{"kind":"weekly_all","group":"weekly","percent":3,"scope":null}]}""");
            })
            .On("GET", ClaudeCodeService.ProfileUrl, 200, """{"account":{"email":"me@example.com"},"organization":{"rate_limit_tier":"default_claude_max_5x"}}""");
        var service = new ClaudeCodeService(http.Context(now));
        var tokens = await service.SignInAsync(url =>
        {
            var query = Query(url);
            Assert.Equal("true", query["code"]);
            Assert.Matches(@"^http://localhost:\d+/callback$", query["redirect_uri"]);
            _ = Browser.GetAsync($"{query["redirect_uri"]}?code=abc%23{query["state"]}&state={Uri.EscapeDataString(query["state"])}");
            return Task.CompletedTask;
        }, CancellationToken.None);
        Assert.True(formSeen);
        Assert.Equal(("c-access", "c-refresh", "org-1", "me@example.com"), (tokens.AccessToken, tokens.RefreshToken, tokens.AccountId, tokens.Email));
        var snapshot = await service.RefreshAsync();
        Assert.Equal(12, snapshot.FiveHour!.UsedPercent);
        Assert.Equal("Max 5x", snapshot.PlanType);
    }

    [Fact]
    public async Task ClaudeRateLimitIsReadable()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("claude_code", new AuthTokens("a", "r", "", DateTimeOffset.UtcNow.AddHours(5)));
        var http = new FakeHttp().On("GET", ClaudeCodeService.UsageUrl, 429, "{}");
        var error = await Assert.ThrowsAsync<ProviderException>(() => new ClaudeCodeService(http.Context(null, store)).RefreshAsync());
        Assert.Equal("Claude is rate-limiting usage checks. Try again in a few minutes.", error.Message);
    }

    [Fact]
    public async Task CursorPollsUntilApprovedAndRefreshesRejectedSession()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000);
        var access = Jwt.Make($$"""{"exp":{{now.AddDays(60).ToUnixTimeSeconds()}},"email":"c@example.com"}""");
        var polls = 0;
        var http = new FakeHttp()
            .On("GET", CursorService.PollUrl, (request, _) =>
            {
                Assert.Contains("verifier=", request.RequestUri!.Query);
                return ++polls < 3 ? (404, "") : (200, $$"""{"accessToken":"{{access}}","refreshToken":"rt","authId":"auth0|u"}""");
            })
            .Once("POST", CursorService.UsageUrl, 200, """{"code":"unauthenticated"}""")
            .On("POST", CursorService.UsageUrl, (request, _) =>
            {
                Assert.Equal("1", request.Headers.GetValues("Connect-Protocol-Version").Single());
                return (200, """{"billingCycleStart":"1791676800000","billingCycleEnd":"1792281600000","planUsage":{"totalPercentUsed":42}}""");
            })
            .On("POST", CursorService.TokenUrl, 200, $$"""{"access_token":"{{access}}"}""")
            .On("GET", CursorService.ProfileUrl, 200, """{"membershipType":"pro"}""");
        var service = new CursorService(http.Context(now)) { PollInterval = TimeSpan.Zero };
        Uri? opened = null;
        var tokens = await service.SignInAsync(url => { opened = url; return Task.CompletedTask; }, CancellationToken.None);
        Assert.StartsWith(CursorService.LoginUrl + "?challenge=", opened!.AbsoluteUri);
        Assert.Equal(3, polls);
        Assert.Equal(("rt", "auth0|u", "c@example.com"), (tokens.RefreshToken, tokens.AccountId, tokens.Email));
        Assert.Equal(now.AddDays(60), tokens.ExpiresAt);

        var snapshot = await service.RefreshAsync();
        Assert.Equal(42, snapshot.Weekly!.UsedPercent);
        Assert.Equal("Pro", snapshot.PlanType);
    }

    [Fact]
    public async Task CursorRefusedSignInStops()
    {
        var http = new FakeHttp().On("GET", CursorService.PollUrl, 403, "");
        var service = new CursorService(http.Context()) { PollInterval = TimeSpan.Zero };
        var error = await Assert.ThrowsAsync<ProviderException>(() => service.SignInAsync(_ => Task.CompletedTask, CancellationToken.None));
        Assert.Contains("refused", error.Message);
    }

    [Fact]
    public async Task SignOutDeletesCredentialsAndCache()
    {
        var store = new MemoryTokenStore();
        var context = new FakeHttp().Context(null, store);
        var hub = await ProviderHub.DemoAsync(context);
        var codex = hub[Providers.UsageProvider.Codex];
        await codex.RefreshAsync();
        Assert.NotNull(await codex.CachedAsync());
        await codex.SignOutAsync();
        Assert.Null(await store.LoadAsync("codex"));
        Assert.Null(await codex.CachedAsync());
        Assert.NotNull(await store.LoadAsync("cursor")); // Other providers untouched.
        Assert.Equal(2, (await hub.SignedInAsync()).Count);
    }

    [Fact]
    public async Task ConcurrentRefreshesShareOneRequest()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("codex", new AuthTokens("a", "r", "", DateTimeOffset.UtcNow.AddHours(1)));
        var usageCalls = 0;
        var http = new FakeHttp()
            .On("GET", CodexService.UsageUrl, (_, _) => { Interlocked.Increment(ref usageCalls); Thread.Sleep(100); return (200, Fixtures.Read("usage-standard")); })
            .On("GET", CodexService.CreditsUrl, 200, "{}");
        var service = new CodexService(http.Context(null, store));
        var results = await Task.WhenAll(Enumerable.Range(0, 5).Select(_ => Task.Run(() => service.RefreshAsync())));
        Assert.Equal(1, usageCalls);
        Assert.All(results, r => Assert.Equal(37, r.FiveHour!.UsedPercent));
        await service.RefreshAsync();
        Assert.Equal(2, usageCalls);
    }
}
