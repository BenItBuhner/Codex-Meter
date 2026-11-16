using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// Claude Code subscription sign-in (Claude OAuth + PKCE with the CLI's loopback <c>/callback</c>)
/// and <c>/api/oauth/usage</c>, the route Claude Code's <c>/usage</c> reads. Anthropic rate-limits
/// this route, so background refresh is spaced 30 minutes apart and backs off after a 429.
/// Usage is read with Claude Code's saved-reset block (<c>cedar_ember</c>) and the CLI's client
/// headers, as on Android; the sign-in asks for Claude Code's own scopes, without which Anthropic
/// reports no saved resets. Older sign-ins gain those scopes on their next token refresh.
/// </summary>
public sealed class ClaudeCodeService(ProviderContext context) : ProviderService(UsageProvider.ClaudeCode, context)
{
    public const string ClientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    public const string AuthorizeUrl = "https://claude.ai/oauth/authorize";
    public const string TokenUrl = "https://platform.claude.com/v1/oauth/token";
    public const string UsageUrl = "https://api.anthropic.com/api/oauth/usage";
    public const string ProfileUrl = "https://api.anthropic.com/api/oauth/profile";
    public const string OAuthBeta = "oauth-2025-04-20";
    /// <summary>Anthropic tells a Claude Code sign-in apart by this scope; without it there are no saved resets.</summary>
    public const string SessionsScope = "user:sessions:claude_code";
    public const string Scope = "user:profile user:inference " + SessionsScope + " user:mcp_servers user:file_upload";
    /// <summary>The usage route plus saved resets; <c>skip_spend</c> leaves out spend data this app never shows.</summary>
    public const string UsageWithSavedResetsUrl = UsageUrl + "?cedar_ember=1&skip_spend=1";
    public const string ResetRateLimitsUrl = "https://api.anthropic.com/api/organizations/{0}/reset_rate_limits";
    /// <summary>
    /// Client headers matching the Claude Code CLI. The server decides saved-reset eligibility per
    /// client surface and CLI version, so without these the block comes back ineligible or empty.
    /// </summary>
    public const string CliUserAgent = "claude-cli/2.1.316 (external, cli)";
    public const string CliApp = "cli";

    public override bool SupportsResetCredits => true;

    public static string ResetUrl(string organization)
    {
        var org = organization.Trim();
        if (!System.Text.RegularExpressions.Regex.IsMatch(org, "^[A-Za-z0-9-]{1,64}$"))
            throw new ProviderException("Couldn't find your Claude organization. Sign in with Claude again.");
        return string.Format(System.Globalization.CultureInfo.InvariantCulture, ResetRateLimitsUrl, org);
    }

    /// <summary>The saved-reset query is optional: when Anthropic refuses it, plain usage still loads.</summary>
    public static bool ShouldRetryPlain(int status) => status is 400 or 403 or 404 or 422 or >= 500;
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

    private async Task<AuthTokens> ExchangeAsync(Dictionary<string, string> body, AuthTokens? previous, string failure,
        bool scopeUpgrade = false)
    {
        var payload = await SendAsync(ProviderHttp.Form(TokenUrl, body.OrderBy(p => p.Key, StringComparer.Ordinal))).ConfigureAwait(false);
        if (ShouldRetryAsJson(payload.Status, payload.Body))
            payload = await SendAsync(ProviderHttp.Json(HttpMethod.Post, TokenUrl, body)).ConfigureAwait(false);
        // A rejected scope request doesn't use up the refresh token; the caller retries without it.
        if (scopeUpgrade && payload.Status == 400 && ShouldRetryAsJson(payload.Status, payload.Body))
            throw new ScopeRejectedException();
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
        var scope = Json.Str(Json.Get(root, "scope")).Trim();
        var tokens = new AuthTokens(access, refresh.Length == 0 ? previous?.RefreshToken ?? "" : refresh, "",
            Now.AddSeconds(Math.Max(60, expiresIn)),
            org.Length == 0 ? previous?.AccountId ?? "" : org,
            email.Length == 0 ? previous?.Email ?? "" : email,
            scope.Length == 0 ? previous?.Scope ?? "" : scope);
        return tokens.IsUsable ? tokens : throw new ProviderException("Claude returned incomplete credentials.");
    }

    private sealed class ScopeRejectedException() : ProviderException("Claude refused the Claude Code scope.");

    private async Task<AuthTokens> RefreshTokensAsync(AuthTokens previous)
    {
        var body = new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = previous.RefreshToken,
            ["client_id"] = ClientId,
        };
        AuthTokens refreshed;
        if (!previous.Scope.Split(' ').Contains(SessionsScope))
        {
            // Like Claude Code: ask for its scopes so an older sign-in gains saved resets without signing in again.
            try
            {
                refreshed = await ExchangeAsync(new Dictionary<string, string>(body) { ["scope"] = Scope }, previous,
                    "Claude session refresh failed", scopeUpgrade: true).ConfigureAwait(false);
            }
            catch (ScopeRejectedException)
            {
                Context.Log?.Invoke("auth", "claude_code scope upgrade rejected");
                refreshed = await ExchangeAsync(body, previous, "Claude session refresh failed").ConfigureAwait(false);
            }
        }
        else refreshed = await ExchangeAsync(body, previous, "Claude session refresh failed").ConfigureAwait(false);
        await SaveTokensAsync(refreshed).ConfigureAwait(false);
        return refreshed;
    }

    private Task<HttpPayload> GetAsync(string url, AuthTokens tokens) => SendAsync(Authorized(HttpMethod.Get, url, tokens));

    private static HttpRequestMessage Authorized(HttpMethod method, string url, AuthTokens tokens, object? json = null)
    {
        var request = json is null ? new HttpRequestMessage(method, url) : ProviderHttp.Json(method, url, json);
        request.Headers.Authorization = new("Bearer", tokens.AccessToken);
        request.Headers.Add("anthropic-beta", OAuthBeta);
        request.Headers.UserAgent.Clear();
        request.Headers.TryAddWithoutValidation("User-Agent", CliUserAgent);
        request.Headers.Add("x-app", CliApp);
        return request;
    }

    /// <summary>Usage with saved resets, refreshing the sign-in once on a 401 and falling back to plain usage when refused.</summary>
    private async Task<(HttpPayload Usage, AuthTokens Tokens, bool WithResets)> GetUsageAsync(AuthTokens tokens)
    {
        if (tokens.ShouldRefresh(Now)) tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
        var usage = await GetAsync(UsageWithSavedResetsUrl, tokens).ConfigureAwait(false);
        if (usage.Status == 401)
        {
            tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
            usage = await GetAsync(UsageWithSavedResetsUrl, tokens).ConfigureAwait(false);
        }
        if (!ShouldRetryPlain(usage.Status)) return (usage, tokens, true);
        Context.Log?.Invoke("usage", $"claude_code saved-reset query refused (HTTP {usage.Status}); retrying plain");
        var plain = await GetAsync(UsageUrl, tokens).ConfigureAwait(false);
        return plain.Ok || plain.Status == 429 ? (plain, tokens, false) : (usage, tokens, true);
    }

    protected override async Task<UsageSnapshot> FetchAsync(AuthTokens tokens)
    {
        var (usage, refreshed, withResets) = await GetUsageAsync(tokens).ConfigureAwait(false);
        tokens = refreshed;
        if (!usage.Ok) throw new ProviderException(UsageErrorMessage(usage), signedOut: usage.Status == 401);
        if (withResets) await StoreSavedResetsAsync(ClaudeSavedResets.FromUsageBody(usage.Body, Now)).ConfigureAwait(false);

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
        if (plan.Length == 0) plan = ClaudeUsageParser.CurrentPlanName((await CachedAsync().ConfigureAwait(false))?.Usage?.PlanType);

        var snapshot = ClaudeUsageParser.Parse(usage.Body, plan, Now);
        if (snapshot.FiveHour is null && snapshot.Weekly is null && snapshot.AdditionalLimits.Count == 0)
            throw new ProviderException("Anthropic returned no recognizable Claude Code usage data.");
        return snapshot;
    }

    /// <summary>Caches saved resets, also as reset credits for the shared card and Priority list. Null clears them.</summary>
    private Task StoreSavedResetsAsync(ClaudeSavedResets? saved) =>
        Context.Cache.UpdateAsync(Provider.Id, entry => entry with
        {
            SavedResets = saved,
            Credits = saved is { } s && s.AvailableCount(Now) > 0 ? s.ToResetCredits(Now) : null,
        });

    /// <summary>
    /// Spends one saved reset: <paramref name="creditId"/> (a credit from the cached list) or the one
    /// the server would spend next. Refreshes usage afterwards when it worked.
    /// </summary>
    public async Task<SavedResetClaimResult> UseSavedResetAsync(string? creditId = null)
    {
        var saved = (await CachedAsync().ConfigureAwait(false))?.SavedResets;
        if (saved is null) return new SavedResetClaimResult(SavedResetClaimOutcome.Unavailable);
        var grant = creditId is { Length: > 0 }
            ? saved.Grants.FirstOrDefault(g => g.Id == ClaudeSavedResets.GrantIdOf(creditId) && g.HasResets(Now))
            : saved.UsableGrant(Now) ?? saved.NextGrant(Now);
        if (grant is null) return new SavedResetClaimResult(SavedResetClaimOutcome.Unavailable);
        var result = await ClaimAsync(grant.Id).ConfigureAwait(false);
        Context.Log?.Invoke("reset", $"claude_code saved reset: {result.Outcome}");
        if (result.Applied)
        {
            await StoreSavedResetsAsync(saved.AfterUse(grant.Id)).ConfigureAwait(false);
            try { await RefreshAsync().ConfigureAwait(false); }
            catch (ProviderException) { /* The reset worked; the next refresh shows it. */ }
        }
        return result;
    }

    private async Task<SavedResetClaimResult> ClaimAsync(string grantId)
    {
        try
        {
            var tokens = await AccountAsync().ConfigureAwait(false) ?? throw new ProviderException(Provider.SignInLabel + " first.", signedOut: true);
            if (tokens.ShouldRefresh(Now)) tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
            var url = ResetUrl(tokens.AccountId);
            var body = ClaudeSavedResets.ClaimBody(grantId, Guid.NewGuid().ToString());
            var response = await SendAsync(Authorized(HttpMethod.Post, url, tokens, body)).ConfigureAwait(false);
            if (response.Status == 401)
            {
                tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
                response = await SendAsync(Authorized(HttpMethod.Post, url, tokens, body)).ConfigureAwait(false);
            }
            return response.Status switch
            {
                429 => new SavedResetClaimResult(SavedResetClaimOutcome.RateLimited),
                401 or 403 => new SavedResetClaimResult(SavedResetClaimOutcome.AuthError),
                _ when !response.Ok => new SavedResetClaimResult(SavedResetClaimOutcome.Error, $"http_{response.Status}"),
                _ => SavedResetClaimResult.Parse(response.Body),
            };
        }
        catch (ProviderException error)
        {
            Context.Log?.Invoke("reset", "claude_code saved reset claim failed: " + error.GetType().Name);
            return new SavedResetClaimResult(error.SignedOut ? SavedResetClaimOutcome.AuthError : SavedResetClaimOutcome.Error, error.Message);
        }
    }

    /// <summary>
    /// "Check with Anthropic": asks the usage route for saved resets now and returns a plain report
    /// of the answer (no tokens). A good answer is cached too, so the card updates.
    /// </summary>
    public async Task<string> CheckSavedResetsAsync()
    {
        try
        {
            var tokens = await AccountAsync().ConfigureAwait(false) ?? throw new ProviderException(Provider.SignInLabel + " first.", signedOut: true);
            if (tokens.ShouldRefresh(Now)) tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
            var usage = await GetAsync(UsageWithSavedResetsUrl, tokens).ConfigureAwait(false);
            if (usage.Status == 401)
            {
                tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
                usage = await GetAsync(UsageWithSavedResetsUrl, tokens).ConfigureAwait(false);
            }
            if (usage.Ok) await StoreSavedResetsAsync(ClaudeSavedResets.FromUsageBody(usage.Body, Now)).ConfigureAwait(false);
            var report = ClaudeSavedResets.DescribeCheck(usage.Status, usage.Body, tokens.Scope, Now);
            Context.Log?.Invoke("reset", $"claude_code saved resets checked (HTTP {usage.Status})");
            return report;
        }
        catch (ProviderException error)
        {
            return "The check could not reach Anthropic: " + error.Message;
        }
    }

    public static string UsageErrorMessage(HttpPayload payload) => payload.Status switch
    {
        401 => "Claude rejected the saved sign-in. Sign in with Claude again.",
        403 => "Claude is refusing usage checks for now (HTTP 403). Your sign-in is kept; try again in a few minutes.",
        404 => "The Claude usage endpoint is unavailable or has changed.",
        429 => "Claude is rate-limiting usage checks. Try again in a few minutes.",
        _ => ProviderHttp.ServerMessage(payload.Body, $"Claude usage refresh failed (HTTP {payload.Status})."),
    };
}
