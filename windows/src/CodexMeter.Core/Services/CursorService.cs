using System.Text.Json.Nodes;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// Cursor sign-in (the editor's PKCE <c>loginDeepControl</c> page plus <c>auth/poll</c>; no
/// callback server) and <c>DashboardService/GetCurrentPeriodUsage</c>, the in-editor usage meter.
/// </summary>
public sealed class CursorService(ProviderContext context) : ProviderService(UsageProvider.Cursor, context)
{
    public const string LoginUrl = "https://cursor.com/loginDeepControl";
    public const string PollUrl = "https://api2.cursor.sh/auth/poll";
    public const string TokenUrl = "https://api2.cursor.sh/oauth/token";
    public const string UsageUrl = "https://api2.cursor.sh/aiserver.v1.DashboardService/GetCurrentPeriodUsage";
    public const string ProfileUrl = "https://api2.cursor.sh/auth/full_stripe_profile";
    public const string ClientId = "KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB";

    /// <summary>Access tokens last about 60 days; refresh when fewer than seven remain.</summary>
    public static readonly TimeSpan RefreshMargin = TimeSpan.FromDays(7);

    public TimeSpan PollInterval { get; init; } = TimeSpan.FromSeconds(1);
    public TimeSpan PollTimeout { get; init; } = TimeSpan.FromMinutes(5);

    public static Uri BuildLoginUrl(string challenge, string uuid) => new($"{LoginUrl}?challenge={challenge}&uuid={uuid}&mode=login");

    protected override async Task<AuthTokens> RunSignInAsync(Func<Uri, Task> openBrowser, CancellationToken cancellationToken)
    {
        var pkce = Pkce.Generate(32);
        var uuid = Guid.NewGuid().ToString();
        await openBrowser(BuildLoginUrl(pkce.Challenge, uuid)).ConfigureAwait(false);
        var deadline = Now + PollTimeout;
        var failures = 0;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (Now >= deadline) throw new ProviderException("Sign-in timed out. Start again from the app.");
            try
            {
                var payload = await SendAsync(new HttpRequestMessage(HttpMethod.Get, $"{PollUrl}?uuid={uuid}&verifier={pkce.Verifier}"),
                    cancellationToken).ConfigureAwait(false);
                switch (payload.Status)
                {
                    case 200:
                        var response = ParseTokenResponse(payload.Body);
                        if (response.Access.Length > 0) return MakeTokens(response, null);
                        failures++;
                        break;
                    case 404:
                        failures = 0; // Still waiting for the user to approve.
                        break;
                    case 403:
                        throw new ProviderException("Cursor refused this sign-in. Start again from the app.");
                    default:
                        failures++;
                        break;
                }
            }
            catch (ProviderException error) when (error.InnerException is HttpRequestException or TaskCanceledException)
            {
                failures++;
            }
            if (failures >= 5)
                throw new ProviderException("Cursor sign-in could not be confirmed. Check your connection and try again.");
            await Task.Delay(PollInterval, cancellationToken).ConfigureAwait(false);
        }
    }

    internal sealed record TokenResponse(string Access, string Refresh, string AuthId, bool ShouldLogout);

    internal static TokenResponse ParseTokenResponse(string body)
    {
        var root = Json.TryParseObject(body);
        string First(string a, string b)
        {
            var value = Json.Str(Json.Get(root, a));
            return value.Length > 0 ? value : Json.Str(Json.Get(root, b));
        }
        var access = First("accessToken", "access_token");
        var refresh = First("refreshToken", "refresh_token");
        return new TokenResponse(access, refresh.Length == 0 ? access : refresh, First("authId", "auth_id"),
            Json.Bool(Json.Get(root, "shouldLogout"), false));
    }

    private AuthTokens MakeTokens(TokenResponse response, AuthTokens? previous)
    {
        var payload = Json.JwtPayload(response.Access);
        var exp = Json.Finite(Json.Get(payload, "exp"));
        var email = Json.Str(Json.Get(payload, "email"));
        if (!email.Contains('@')) email = "";
        return new AuthTokens(response.Access,
            response.Refresh.Length == 0 ? previous?.RefreshToken ?? response.Access : response.Refresh, "",
            exp is > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)exp.Value) : Now.AddDays(1),
            response.AuthId.Length == 0 ? previous?.AccountId ?? "" : response.AuthId,
            email.Length == 0 ? previous?.Email ?? "" : email);
    }

    /// <summary>Cursor answers some rejected sessions with HTTP 200 and an error body.</summary>
    public static bool IsUnauthenticated(int status, string body)
    {
        if (status == 401) return true;
        var root = Json.TryParseObject(body);
        if (root is null) return false;
        if (Json.Bool(Json.Get(root, "shouldLogout"), false)) return true;
        var code = (Json.Str(Json.Get(root, "code")) + " " + Json.Str(Json.Get(root, "error"))).ToLowerInvariant();
        return code.Contains("not_authenticated") || code.Contains("unauthenticated");
    }

    public static bool IsBotCheckpoint(int status, string body) => status == 403 && body.Contains("Vercel Security Checkpoint");

    private async Task<AuthTokens> RefreshTokensAsync(AuthTokens previous)
    {
        var payload = await SendAsync(PostRequest(TokenUrl, new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["client_id"] = ClientId,
            ["refresh_token"] = previous.RefreshToken,
        }, null)).ConfigureAwait(false);
        var response = ParseTokenResponse(payload.Body);
        if (response.ShouldLogout || payload.Status == 401)
            throw new ProviderException("Your Cursor sign-in expired. Sign in with Cursor again.", signedOut: true);
        if (!payload.Ok || response.Access.Length == 0)
            throw new ProviderException(ProviderHttp.ServerMessage(payload.Body, $"Cursor sign-in refresh failed (HTTP {payload.Status})."));
        var refreshed = MakeTokens(response, previous);
        await SaveTokensAsync(refreshed).ConfigureAwait(false);
        return refreshed;
    }

    private static HttpRequestMessage PostRequest(string url, object body, string? bearer)
    {
        var request = ProviderHttp.Json(HttpMethod.Post, url, body);
        request.Headers.Add("Connect-Protocol-Version", "1");
        request.Headers.Add("X-Request-Id", Guid.NewGuid().ToString());
        if (bearer is not null) request.Headers.Authorization = new("Bearer", bearer);
        return request;
    }

    protected override async Task<UsageSnapshot> FetchAsync(AuthTokens tokens)
    {
        if (tokens.ExpiresAt <= Now + RefreshMargin)
        {
            try { tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false); }
            catch (ProviderException) when (tokens.ExpiresAt > Now) { /* Still valid; keep using it. */ }
        }
        var usage = await SendAsync(PostRequest(UsageUrl, new Dictionary<string, string>(), tokens.AccessToken)).ConfigureAwait(false);
        if (IsUnauthenticated(usage.Status, usage.Body))
        {
            tokens = await RefreshTokensAsync(tokens).ConfigureAwait(false);
            usage = await SendAsync(PostRequest(UsageUrl, new Dictionary<string, string>(), tokens.AccessToken)).ConfigureAwait(false);
        }
        if (IsUnauthenticated(usage.Status, usage.Body))
            throw new ProviderException("Cursor rejected the saved sign-in. Sign in with Cursor again.", signedOut: true);
        if (!usage.Ok) throw new ProviderException(UsageErrorMessage(usage));

        var plan = "";
        try
        {
            var request = new HttpRequestMessage(HttpMethod.Get, ProfileUrl);
            request.Headers.Authorization = new("Bearer", tokens.AccessToken);
            var profile = await SendAsync(request).ConfigureAwait(false);
            if (profile.Ok)
            {
                plan = CursorUsageParser.PlanLabel(profile.Body);
                var email = CursorUsageParser.Email(profile.Body);
                if (email.Length > 0 && email != tokens.Email) await SaveTokensAsync(tokens with { Email = email }).ConfigureAwait(false);
            }
        }
        catch (ProviderException) { }
        if (plan.Length == 0) plan = (await CachedAsync().ConfigureAwait(false))?.Usage?.PlanType ?? "";

        var snapshot = CursorUsageParser.Parse(usage.Body, plan, Now);
        if (snapshot.Weekly is null && snapshot.Monthly is null && snapshot.AdditionalLimits.Count == 0)
            throw new ProviderException("Cursor returned no recognizable usage data.");
        return snapshot;
    }

    public static string UsageErrorMessage(HttpPayload payload)
    {
        if (IsBotCheckpoint(payload.Status, payload.Body))
            return "Cursor's bot protection blocked the usage check. Try again in a few minutes.";
        return payload.Status switch
        {
            403 => "Cursor denied usage access for this account.",
            404 => "The Cursor usage endpoint is unavailable or has changed.",
            429 => "Cursor is rate-limiting usage checks. Try again in a few minutes.",
            _ => ProviderHttp.ServerMessage(payload.Body, $"Cursor usage refresh failed (HTTP {payload.Status})."),
        };
    }
}
