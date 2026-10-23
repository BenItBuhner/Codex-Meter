using System.Text.Json.Nodes;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// ChatGPT sign-in (OAuth + PKCE with a localhost callback on port 1455/1457, the Codex CLI's
/// flow) and the Codex usage and reset-credit routes. A port of Android's <c>OAuthService</c> +
/// <c>UsageApi</c> + <c>ResetCreditApi</c> and iOS <c>LiveCodexService</c>.
/// </summary>
public sealed class CodexService(ProviderContext context) : ProviderService(UsageProvider.Codex, context)
{
    public const string ClientId = "app_EMoamEEZ73f0CkXaXp7hrann";
    public const string AuthorizeUrl = "https://auth.openai.com/oauth/authorize";
    public const string TokenUrl = "https://auth.openai.com/oauth/token";
    public const string RevokeUrl = "https://auth.openai.com/oauth/revoke";
    public const string UsageUrl = "https://chatgpt.com/backend-api/wham/usage";
    public const string CreditsUrl = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits";
    public const string ConsumeUrl = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume";
    public const string Scope = "openid profile email offline_access";
    public const string Originator = "codex-meter-windows";
    public static readonly int[] Ports = [1455, 1457];

    public override bool SupportsResetCredits => true;

    public static Uri BuildAuthorizeUrl(string redirectUri, Pkce pkce) => new(AuthorizeUrl + "?" + FormEncoding.Encode(
    [
        new("response_type", "code"),
        new("client_id", ClientId),
        new("redirect_uri", redirectUri),
        new("scope", Scope),
        new("code_challenge", pkce.Challenge),
        new("code_challenge_method", "S256"),
        new("id_token_add_organizations", "true"),
        new("codex_cli_simplified_flow", "true"),
        new("state", pkce.State),
        new("originator", Originator),
    ]));

    protected override async Task<AuthTokens> RunSignInAsync(Func<Uri, Task> openBrowser, CancellationToken cancellationToken)
    {
        var pkce = Pkce.Generate();
        using var server = LoopbackCallbackServer.Start(Ports);
        var redirect = $"http://localhost:{server.Port}/auth/callback";
        return await RunLoopbackAsync(server, "/auth/callback", pkce.State, BuildAuthorizeUrl(redirect, pkce), openBrowser,
            code => ExchangeCodeAsync(code, redirect, pkce.Verifier, cancellationToken), cancellationToken).ConfigureAwait(false);
    }

    internal async Task<AuthTokens> ExchangeCodeAsync(string code, string redirectUri, string verifier, CancellationToken cancellationToken)
    {
        var payload = await SendAsync(ProviderHttp.Form(TokenUrl,
        [
            new("grant_type", "authorization_code"),
            new("code", code),
            new("redirect_uri", redirectUri),
            new("client_id", ClientId),
            new("code_verifier", verifier),
        ]), cancellationToken).ConfigureAwait(false);
        if (!payload.Ok)
            throw new ProviderException(ProviderHttp.ServerMessage(payload.Body, $"Authentication failed (HTTP {payload.Status})."));
        return ParseTokens(payload.Body, null);
    }

    private AuthTokens ParseTokens(string body, AuthTokens? previous)
    {
        var root = Json.TryParseObject(body) ?? throw new ProviderException("The authorization server returned an unreadable answer.");
        var access = Json.Str(Json.Get(root, "access_token"));
        var refresh = Json.Str(Json.Get(root, "refresh_token"));
        var id = Json.Str(Json.Get(root, "id_token"));
        if (previous is not null)
        {
            if (access.Length == 0) access = previous.AccessToken;
            if (refresh.Length == 0) refresh = previous.RefreshToken;
            if (id.Length == 0) id = previous.IdToken;
        }
        var expiresIn = Math.Max(60, Json.Int64(Json.Get(root, "expires_in"), 3600));
        var claims = JwtClaims.FromTokens(id, access);
        var tokens = new AuthTokens(access, refresh, id, Now.AddSeconds(expiresIn),
            claims.AccountId.Length == 0 ? previous?.AccountId ?? "" : claims.AccountId,
            claims.Email.Length == 0 ? previous?.Email ?? "" : claims.Email);
        return tokens.IsUsable ? tokens : throw new ProviderException("The authorization server returned incomplete credentials.");
    }

    private async Task<AuthTokens> RefreshTokensAsync(AuthTokens previous)
    {
        var payload = await SendAsync(ProviderHttp.Json(HttpMethod.Post, TokenUrl, new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = previous.RefreshToken,
            ["client_id"] = ClientId,
        })).ConfigureAwait(false);
        if (payload.Status is 400 or 401)
            throw new ProviderException("ChatGPT rejected the saved sign-in. Sign in with ChatGPT again.", signedOut: true);
        if (!payload.Ok)
            throw new ProviderException(ProviderHttp.ServerMessage(payload.Body, $"ChatGPT session refresh failed (HTTP {payload.Status})."));
        var refreshed = ParseTokens(payload.Body, previous);
        await SaveTokensAsync(refreshed).ConfigureAwait(false);
        return refreshed;
    }

    /// <summary>Sends one backend request and, only for a 401, refreshes credentials and retries once.</summary>
    private async Task<HttpPayload> AuthenticatedAsync(HttpMethod method, string url, object? body = null)
    {
        var tokens = await Context.Tokens.LoadAsync(Provider.Id).ConfigureAwait(false)
                     ?? throw new ProviderException("Sign in with ChatGPT first.", signedOut: true);
        if (tokens.ShouldRefresh(Now)) tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
        var payload = await SendAsync(BackendRequest(method, url, body, tokens)).ConfigureAwait(false);
        if (payload.Status == 401)
        {
            tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
            payload = await SendAsync(BackendRequest(method, url, body, tokens)).ConfigureAwait(false);
        }
        return payload;
    }

    private static HttpRequestMessage BackendRequest(HttpMethod method, string url, object? body, AuthTokens tokens)
    {
        var request = body is null ? new HttpRequestMessage(method, url) : ProviderHttp.Json(method, url, body);
        request.Headers.Authorization = new("Bearer", tokens.AccessToken);
        request.Headers.Add("originator", Originator);
        if (tokens.AccountId.Length > 0) request.Headers.Add("ChatGPT-Account-Id", tokens.AccountId);
        return request;
    }

    private static string Require(HttpPayload payload, string operation)
    {
        if (payload.Ok) return payload.Body;
        var fallback = payload.Status switch
        {
            401 => "ChatGPT rejected the saved sign-in. Sign in with ChatGPT again.",
            403 => $"{operation}: this account is not allowed to use this Codex feature.",
            404 => $"{operation}: the private Codex endpoint is unavailable or has changed.",
            429 => "ChatGPT is rate-limiting usage checks. Try again in a few minutes.",
            _ => $"{operation} (HTTP {payload.Status}).",
        };
        throw new ProviderException(payload.Status is 401 ? fallback : ProviderHttp.ServerMessage(payload.Body, fallback),
            signedOut: payload.Status == 401);
    }

    protected override async Task<UsageSnapshot> FetchAsync(AuthTokens tokens)
    {
        var body = Require(await AuthenticatedAsync(HttpMethod.Get, UsageUrl).ConfigureAwait(false), "Usage refresh failed");
        var usage = CodexUsageParser.Parse(body, Now).Resolved();
        if (!usage.HasDisplayableData)
            throw new ProviderException("OpenAI returned no recognizable Codex usage data.");

        // Reset credits enrich the snapshot; a failure there never fails usage.
        try
        {
            var credits = await FetchCreditsAsync().ConfigureAwait(false);
            await Context.Cache.UpdateAsync(Provider.Id, e => e with { Credits = credits, CreditsError = null }).ConfigureAwait(false);
            usage = usage.WithResetCredits(credits.AvailableCount);
        }
        catch (ProviderException error)
        {
            await Context.Cache.UpdateAsync(Provider.Id, e => e with { CreditsError = error.Message }).ConfigureAwait(false);
        }
        return usage;
    }

    public async Task<ResetCreditsSnapshot> FetchCreditsAsync()
    {
        var body = Require(await AuthenticatedAsync(HttpMethod.Get, CreditsUrl).ConfigureAwait(false), "Could not load Codex reset credits");
        return ResetCreditsParser.Parse(body, Now);
    }

    /// <summary>
    /// Redeems one banked reset (the requested credit, or the one that expires first) and reloads usage.
    /// </summary>
    public async Task<ResetConsumeResult> ConsumeResetAsync(string? creditId = null)
    {
        var credits = (await CachedAsync().ConfigureAwait(false))?.Credits;
        if (credits is null || credits.AvailableCount == 0 || Now - credits.FetchedAt > TimeSpan.FromMinutes(5))
            credits = await FetchCreditsAsync().ConfigureAwait(false);
        if (credits.AvailableCount == 0) return new ResetConsumeResult(ResetConsumeOutcome.NoCredit, 0);

        string? preferred;
        if (!string.IsNullOrEmpty(creditId))
        {
            preferred = credits.Credits.Any(c => c.Id == creditId && c.IsAvailable && (c.ExpiresAt is null || c.ExpiresAt > Now))
                ? creditId : null;
            if (preferred is null) return new ResetConsumeResult(ResetConsumeOutcome.NoCredit, 0);
        }
        else preferred = credits.NextExpiringAvailable(Now)?.Id;

        var body = new Dictionary<string, string> { ["redeem_request_id"] = Guid.NewGuid().ToString() };
        if (!string.IsNullOrEmpty(preferred)) body["credit_id"] = preferred;
        var response = Require(await AuthenticatedAsync(HttpMethod.Post, ConsumeUrl, body).ConfigureAwait(false),
            "Could not apply the Codex reset");
        var root = Json.TryParseObject(response) ?? throw new ProviderException("OpenAI returned an unreadable reset result.");
        var outcome = ResetConsumeResult.ParseOutcome(Json.Str(Json.Get(root, "code")));
        var windows = Json.Int(Json.Get(root, "windows_reset"), 0);

        string? warning = null;
        if (outcome == ResetConsumeOutcome.Reset)
        {
            try { await RefreshAsync().ConfigureAwait(false); }
            catch (ProviderException) { warning = "The reset succeeded, but the new usage values could not be loaded yet."; }
        }
        return new ResetConsumeResult(outcome, windows, warning);
    }

    protected override async Task RevokeAsync(AuthTokens tokens)
    {
        if (tokens.RefreshToken.Length == 0) return;
        await SendAsync(ProviderHttp.Json(HttpMethod.Post, RevokeUrl, new Dictionary<string, string>
        {
            ["token"] = tokens.RefreshToken,
            ["token_type_hint"] = "refresh_token",
            ["client_id"] = ClientId,
        })).ConfigureAwait(false);
    }
}
