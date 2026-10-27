using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// Claude Code subscription sign-in (Claude OAuth + PKCE with the CLI's loopback <c>/callback</c>)
/// and <c>/api/oauth/usage</c>, the route Claude Code's <c>/usage</c> reads. Anthropic rate-limits
/// this route, so background refresh is spaced 30 minutes apart and backs off after a 429.
/// </summary>
public sealed class ClaudeCodeService(ProviderContext context) : ProviderService(UsageProvider.ClaudeCode, context)
{
    public const string ClientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    public const string AuthorizeUrl = "https://claude.ai/oauth/authorize";
    public const string TokenUrl = "https://platform.claude.com/v1/oauth/token";
    public const string UsageUrl = "https://api.anthropic.com/api/oauth/usage";
    public const string ProfileUrl = "https://api.anthropic.com/api/oauth/profile";
    public const string OAuthBeta = "oauth-2025-04-20";
    public const string Scope = "user:profile user:inference";
    public const string CallbackPath = "/callback";

    public override TimeSpan BackgroundInterval => TimeSpan.FromMinutes(30);

    public static Uri BuildAuthorizeUrl(string redirectUri, string challenge, string state) => new(AuthorizeUrl + "?" + FormEncoding.Encode(
    [
        new("code", "true"),
        new("client_id", ClientId),
        new("response_type", "code"),
        new("redirect_uri", redirectUri),
        new("scope", Scope),
        new("code_challenge", challenge),
        new("code_challenge_method", "S256"),
        new("state", state),
    ]));

    /// <summary>Claude's manual flow shows <c>code#state</c>; only the part before <c>#</c> is the code.</summary>
    public static string StripStateSuffix(string code) => code.Split('#', 2)[0].Trim();

    protected override async Task<AuthTokens> RunSignInAsync(Func<Uri, Task> openBrowser, CancellationToken cancellationToken)
    {
        var pkce = Pkce.Generate(32);
        using var server = LoopbackCallbackServer.Start([]);
        var redirect = $"http://localhost:{server.Port}{CallbackPath}";
        return await RunLoopbackAsync(server, CallbackPath, pkce.State, BuildAuthorizeUrl(redirect, pkce.Challenge, pkce.State),
            openBrowser, code => ExchangeAsync(new Dictionary<string, string>
            {
                ["grant_type"] = "authorization_code",
                ["code"] = StripStateSuffix(code),
                ["redirect_uri"] = redirect,
                ["client_id"] = ClientId,
                ["code_verifier"] = pkce.Verifier,
                ["state"] = pkce.State,
            }, null, "Claude sign-in failed"), cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Sources disagree on form vs JSON; form goes first and a non-grant 400/415 retries as JSON.</summary>
    public static bool ShouldRetryAsJson(int status, string body) =>
        status is 400 or 415 && !body.Contains("invalid_grant", StringComparison.OrdinalIgnoreCase)
                             && !body.Contains("expired", StringComparison.OrdinalIgnoreCase);

    private async Task<AuthTokens> ExchangeAsync(Dictionary<string, string> body, AuthTokens? previous, string failure)
    {
        var payload = await SendAsync(ProviderHttp.Form(TokenUrl, body.OrderBy(p => p.Key, StringComparer.Ordinal))).ConfigureAwait(false);
        if (ShouldRetryAsJson(payload.Status, payload.Body))
            payload = await SendAsync(ProviderHttp.Json(HttpMethod.Post, TokenUrl, body)).ConfigureAwait(false);
        if (previous is not null && payload.Status is 400 or 401)
            throw new ProviderException("Claude rejected the saved sign-in. Sign in with Claude again.", signedOut: true);
        if (!payload.Ok)
            throw new ProviderException(ProviderHttp.ServerMessage(payload.Body, $"{failure} (HTTP {payload.Status})."));
        var root = Json.TryParseObject(payload.Body);
        var access = Json.Str(Json.Get(root, "access_token"));
        if (root is null || access.Length == 0) throw new ProviderException("Claude returned incomplete credentials.");
        var account = Json.Obj(Json.Get(root, "account"));
        var email = Json.Str(Json.Get(account, "email_address"));
        if (email.Length == 0) email = Json.Str(Json.Get(account, "email"));
        var org = Json.Str(Json.Get(Json.Obj(Json.Get(root, "organization")), "uuid"));
        var refresh = Json.Str(Json.Get(root, "refresh_token"));
        var expiresIn = Json.Finite(Json.Get(root, "expires_in")) ?? 3600;
        var tokens = new AuthTokens(access, refresh.Length == 0 ? previous?.RefreshToken ?? "" : refresh, "",
            Now.AddSeconds(Math.Max(60, expiresIn)),
            org.Length == 0 ? previous?.AccountId ?? "" : org,
            email.Length == 0 ? previous?.Email ?? "" : email);
        return tokens.IsUsable ? tokens : throw new ProviderException("Claude returned incomplete credentials.");
    }

    private async Task<AuthTokens> RefreshTokensAsync(AuthTokens previous)
    {
        var refreshed = await ExchangeAsync(new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = previous.RefreshToken,
            ["client_id"] = ClientId,
        }, previous, "Claude session refresh failed").ConfigureAwait(false);
        await SaveTokensAsync(refreshed).ConfigureAwait(false);
        return refreshed;
    }

    private Task<HttpPayload> GetAsync(string url, AuthTokens tokens)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new("Bearer", tokens.AccessToken);
        request.Headers.Add("anthropic-beta", OAuthBeta);
        return SendAsync(request);
    }

    protected override async Task<UsageSnapshot> FetchAsync(AuthTokens tokens)
    {
        if (tokens.ShouldRefresh(Now)) tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
        var usage = await GetAsync(UsageUrl, tokens).ConfigureAwait(false);
        if (usage.Status == 401)
        {
            tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
            usage = await GetAsync(UsageUrl, tokens).ConfigureAwait(false);
        }
        if (!usage.Ok) throw new ProviderException(UsageErrorMessage(usage), signedOut: usage.Status == 401);

        // The profile only decorates the plan badge and email; never fail usage over it.
        var plan = "";
        try
        {
            var profile = await GetAsync(ProfileUrl, tokens).ConfigureAwait(false);
            if (profile.Ok)
            {
                plan = ClaudeUsageParser.PlanLabel(profile.Body);
                var email = ClaudeUsageParser.Email(profile.Body);
                if (email.Length > 0 && email != tokens.Email) await SaveTokensAsync(tokens with { Email = email }).ConfigureAwait(false);
            }
        }
        catch (ProviderException) { }
        if (plan.Length == 0) plan = (await CachedAsync().ConfigureAwait(false))?.Usage?.PlanType ?? "";

        var snapshot = ClaudeUsageParser.Parse(usage.Body, plan, Now);
        if (snapshot.FiveHour is null && snapshot.Weekly is null && snapshot.AdditionalLimits.Count == 0)
            throw new ProviderException("Anthropic returned no recognizable Claude Code usage data.");
        return snapshot;
    }

    public static string UsageErrorMessage(HttpPayload payload) => payload.Status switch
    {
        401 => "Claude rejected the saved sign-in. Sign in with Claude again.",
        403 => "Claude usage access was denied for this account. Usage limits are only available for Pro, Max, Team, and Enterprise plans.",
        404 => "The Claude usage endpoint is unavailable or has changed.",
        429 => "Claude is rate-limiting usage checks. Try again in a few minutes.",
        _ => ProviderHttp.ServerMessage(payload.Body, $"Claude usage refresh failed (HTTP {payload.Status})."),
    };
}
