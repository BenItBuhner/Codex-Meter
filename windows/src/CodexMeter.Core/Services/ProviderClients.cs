using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

// Sign-in and usage routes for every provider added through Accounts → Add account. A port of
// Android's and iOS's ProviderClients: client ids, endpoints and headers are the ones each
// provider's own CLI or editor sends, and none of these usage routes is a documented public API.
// Codex, Claude Code and Cursor keep their own services.

/// <summary>One request; headers keep insertion order. Bodies and headers are never logged.</summary>
public sealed class ProviderRequest
{
    private ProviderRequest(string url, HttpMethod method)
    {
        Url = url;
        Method = method;
    }

    public string Url { get; }
    public HttpMethod Method { get; private set; }
    public List<KeyValuePair<string, string>> Headers { get; } = [];
    public string? Body { get; private set; }
    public string? ContentType { get; private set; }

    public static ProviderRequest Get(string url) => new(url, HttpMethod.Get);
    public static ProviderRequest Post(string url) => new(url, HttpMethod.Post);

    public ProviderRequest Header(string name, string value)
    {
        Headers.RemoveAll(h => h.Key.Equals(name, StringComparison.OrdinalIgnoreCase));
        Headers.Add(new(name, value));
        return this;
    }

    public ProviderRequest Bearer(string token) => Header("Authorization", "Bearer " + token);

    public ProviderRequest Json(JsonObject payload)
    {
        Method = HttpMethod.Post;
        Body = payload.ToJsonString();
        ContentType = "application/json";
        return this;
    }

    /// <summary>Form fields in order; null values are left out.</summary>
    public ProviderRequest Form(params (string Name, string? Value)[] fields)
    {
        Method = HttpMethod.Post;
        Body = FormEncoding.Encode(fields.Where(f => f.Value is not null).Select(f => new KeyValuePair<string, string>(f.Name, f.Value!)));
        ContentType = "application/x-www-form-urlencoded";
        return this;
    }

    public HttpRequestMessage ToMessage()
    {
        var message = new HttpRequestMessage(Method, Url);
        foreach (var (name, value) in Headers)
        {
            if (name.Equals("Accept", StringComparison.OrdinalIgnoreCase)) message.Headers.Accept.Clear();
            message.Headers.TryAddWithoutValidation(name, value);
        }
        if (Body is not null)
        {
            message.Content = new StringContent(Body, Encoding.UTF8, ContentType ?? "application/json");
            message.Content.Headers.ContentType!.CharSet = null;
        }
        return message;
    }

    /// <summary>Query string from ordered pairs, for authorize URLs.</summary>
    public static string Query(params (string Name, string Value)[] fields) =>
        string.Join('&', fields.Select(f => FormEncoding.Escape(f.Name) + "=" + FormEncoding.Escape(f.Value)));
}

/// <summary>The provider turned the stored credential down; the user has to sign in again.</summary>
public sealed class ProviderUnauthorizedException(string message) : ProviderException(message, signedOut: true);

/// <summary>One sign-in attempt's secrets: PKCE values, the device code, and provider extras.</summary>
public sealed class SignInAttempt
{
    public string Verifier { get; } = Pkce.RandomToken(48);
    public string Challenge => Pkce.S256(Verifier);
    public string State { get; } = Pkce.RandomToken(24);
    public DeviceCode? Device { get; set; }
    public Dictionary<string, string> Extra { get; } = [];
    public string ExtraValue(string key) => Extra.GetValueOrDefault(key, "");
}

/// <summary>Usage plus an account email the usage call discovered along the way.</summary>
public sealed record ProviderFetch(UsageSnapshot Snapshot, string Email = "");

/// <summary>Sign-in and usage calls for one added provider. Stateless; one instance per provider.</summary>
public abstract class ProviderClient(UsageProvider provider)
{
    public UsageProvider Provider { get; } = provider;

    protected static async Task<HttpPayload> SendAsync(ProviderContext context, string operation, ProviderRequest request,
        CancellationToken cancellationToken)
    {
        using var message = request.ToMessage();
        var payload = await ProviderHttp.SendAsync(context.Http, message, cancellationToken).ConfigureAwait(false);
        context.Log?.Invoke("network", $"{operation} HTTP {payload.Status}");
        return payload;
    }

    /// <summary>Like <see cref="SendAsync"/>, but a network failure yields null.</summary>
    protected static async Task<HttpPayload?> TrySendAsync(ProviderContext context, string operation, ProviderRequest request,
        CancellationToken cancellationToken)
    {
        try { return await SendAsync(context, operation, request, cancellationToken).ConfigureAwait(false); }
        catch (ProviderException) { return null; }
    }

    // Device-code sign-in

    public virtual bool HasDeviceFlow => false;

    public virtual Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken) =>
        throw new ProviderException($"No device sign-in for {Provider.DisplayName}.");

    public virtual Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken) =>
        throw new ProviderException($"No device sign-in for {Provider.DisplayName}.");

    public virtual Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken) => Task.FromResult(tokens.Stored(context.Now()));

    // Paste sign-in

    public virtual string? PasteUrl(SignInAttempt attempt) => null;

    public virtual string PasteHint => "Paste what the page shows after you sign in.";

    public virtual Task<AuthTokens> FinishPasteAsync(SignInAttempt attempt, string pasted, ProviderContext context,
        CancellationToken cancellationToken) =>
        throw new ProviderException($"No paste sign-in for {Provider.DisplayName}.");

    // Browser sign-in that returns to a local port (OpenRouter)

    public virtual string? LoopbackAuthUrl(string redirectUri, SignInAttempt attempt) => null;

    public virtual Task<AuthTokens> FinishLoopbackAsync(SignInAttempt attempt, string code, ProviderContext context,
        CancellationToken cancellationToken) =>
        throw new ProviderException($"No browser sign-in for {Provider.DisplayName}.");

    /// <summary>Stores a pasted key after checking it against the usage route.</summary>
    public virtual async Task<AuthTokens> FromApiKeyAsync(string key, ProviderContext context, CancellationToken cancellationToken)
    {
        var clean = (key ?? "").Trim();
        if (clean.Length == 0) throw new ProviderException("Paste a key first.");
        var tokens = new AuthTokens(clean, clean, "", AuthTokens.NeverExpires, AuthTokens.ApiKeyAccount);
        await FetchAsync(tokens, context, cancellationToken).ConfigureAwait(false);
        return tokens;
    }

    public virtual bool CanRefresh(AuthTokens tokens) => false;

    public virtual Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken) =>
        Task.FromResult(tokens);

    public abstract Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken);

    /// <summary>Throws <see cref="ProviderUnauthorizedException"/> on 401/403, a readable error on other failures.</summary>
    protected HttpPayload Check(HttpPayload response, string what)
    {
        if (response.Status == 401) throw new ProviderUnauthorizedException($"{Provider.DisplayName} rejected the saved sign-in.");
        if (response.Ok) return response;
        throw response.Status switch
        {
            403 => new ProviderUnauthorizedException($"{Provider.DisplayName} denied {what} for this account."),
            404 => new ProviderException($"The {Provider.DisplayName} {what} endpoint is unavailable or has changed."),
            429 => new ProviderException($"{Provider.DisplayName} is rate-limiting checks. Try again in a few minutes."),
            _ => new ProviderException(ProviderHttp.ServerMessage(response.Body,
                $"{Provider.DisplayName} {what} failed (HTTP {response.Status}).")),
        };
    }

    /// <summary>Fails a refresh when the server found nothing to show.</summary>
    protected ProviderFetch NonEmpty(UsageSnapshot snapshot, string email = "")
    {
        if (!snapshot.HasDisplayableData)
            throw new ProviderException($"{Provider.DisplayName} returned no recognizable usage data.");
        return new ProviderFetch(snapshot, email);
    }

    protected static string BodyString(HttpPayload response, string key) => ProviderJson.String(ProviderJson.Object(response.Body), key);
}

/// <summary>Token helpers shared by the clients.</summary>
public static class ProviderTokens
{
    /// <summary>A refresh reply merged over the stored tokens; empty fields keep their old values.</summary>
    public static AuthTokens MergingRefresh(this AuthTokens current, AuthTokens fresh) => new(
        fresh.AccessToken.Length == 0 ? current.AccessToken : fresh.AccessToken,
        fresh.RefreshToken.Length == 0 ? current.RefreshToken : fresh.RefreshToken,
        fresh.IdToken.Length == 0 ? current.IdToken : fresh.IdToken,
        fresh.ExpiresAt == AuthTokens.NeverExpires ? current.ExpiresAt : fresh.ExpiresAt,
        fresh.AccountId.Length == 0 ? current.AccountId : fresh.AccountId,
        fresh.Email.Length == 0 ? current.Email : fresh.Email);

    /// <summary>Fills a missing expiry and email from the access token's JWT claims.</summary>
    public static AuthTokens WithJwtExpiry(this AuthTokens tokens)
    {
        var result = tokens;
        if (result.ExpiresAt == AuthTokens.NeverExpires && ProviderJson.JwtExpiry(result.AccessToken) is { } expiry)
            result = result with { ExpiresAt = expiry };
        if (result.Email.Length == 0 && ProviderJson.JwtEmail(result.AccessToken) is { Length: > 0 } email)
            result = result with { Email = email };
        return result;
    }
}

public static class ProviderClients
{
    /// <summary>The client for <paramref name="provider"/>, or null for Codex, Claude Code, Cursor and Antigravity.</summary>
    public static ProviderClient? For(UsageProvider provider) => provider.Id switch
    {
        "copilot" => new CopilotClient(),
        "kimi" => new KimiClient(),
        "factory" => new FactoryClient(),
        "grok" => new GrokClient(),
        "devin" => new CodeiumClient(UsageProvider.Devin, "devin"),
        "windsurf" => new CodeiumClient(UsageProvider.Windsurf, "windsurf"),
        "augment" => new AugmentClient(),
        "cline" => new ClineClient(),
        "opencode_go" => new OpenCodeGoClient(),
        "minimax" => new MiniMaxClient(),
        "zai" => new ZaiClient(),
        "kiro" => new KiroClient(),
        "amp" => new AmpClient(),
        "openrouter" => new OpenRouterClient(),
        "kilo" => new KiloClient(),
        "codebuff" => new CodebuffClient(),
        "synthetic" => new KeyQuotaClient(UsageProvider.Synthetic, "https://api.synthetic.new/v2/quotas", 5 * ProviderUsageParsers.Hour),
        "chutes" => new KeyQuotaClient(UsageProvider.Chutes, "https://api.chutes.ai/users/me/subscription_usage", ProviderUsageParsers.Day),
        "v0" => new KeyQuotaClient(UsageProvider.V0, "https://api.v0.dev/v1/rate-limits", ProviderUsageParsers.Day),
        "warp" => new WarpClient(),
        _ => null,
    };
}

// ---------------------------------------------------------------------- Shared flows

/// <summary>RFC 8628 device flow with form-encoded requests (GitHub, Kimi, xAI, WorkOS).</summary>
public abstract class StandardDeviceClient(UsageProvider provider) : ProviderClient(provider)
{
    protected abstract string DeviceUrl { get; }
    protected abstract string TokenUrl { get; }
    protected abstract string ClientId { get; }
    protected virtual string? Scope => null;

    protected virtual ProviderRequest Decorate(ProviderRequest request, SignInAttempt? attempt) => request;

    public override bool HasDeviceFlow => true;

    public override async Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, Provider.Id + "_device",
            Decorate(ProviderRequest.Post(DeviceUrl).Form(("client_id", ClientId), ("scope", Scope)), attempt), cancellationToken).ConfigureAwait(false);
        var code = DeviceAuth.ParseDeviceCode(response.Body);
        if (!response.Ok || !code.IsValid)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body,
                $"{Provider.DisplayName} could not start sign-in (HTTP {response.Status})."));
        attempt.Device = code;
        return code;
    }

    public override async Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, Provider.Id + "_poll", Decorate(ProviderRequest.Post(TokenUrl).Form(
            ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ("device_code", attempt.Device?.Code ?? ""),
            ("client_id", ClientId)), attempt), cancellationToken).ConfigureAwait(false);
        return DeviceAuth.ParsePoll(response.Status, response.Body, context.Now());
    }

    public override bool CanRefresh(AuthTokens tokens) =>
        !tokens.IsApiKey && tokens.RefreshToken.Length > 0 && tokens.RefreshToken != tokens.AccessToken;

    public override async Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, Provider.Id + "_token_refresh", Decorate(ProviderRequest.Post(TokenUrl).Form(
            ("grant_type", "refresh_token"),
            ("refresh_token", tokens.RefreshToken),
            ("client_id", ClientId)), null), cancellationToken).ConfigureAwait(false);
        return MergeRefresh(response, tokens, context);
    }

    protected AuthTokens MergeRefresh(HttpPayload response, AuthTokens tokens, ProviderContext context)
    {
        var parsed = DeviceAuth.ParseTokens(response.Body, context.Now());
        if (response.Status is 400 or 401)
            throw new ProviderUnauthorizedException($"Your {Provider.DisplayName} sign-in expired. Sign in again from Accounts.");
        if (!response.Ok || parsed.AccessToken.Length == 0)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body,
                $"{Provider.DisplayName} sign-in refresh failed (HTTP {response.Status})."));
        return tokens.MergingRefresh(parsed.Stored(context.Now()).WithJwtExpiry());
    }
}

// ---------------------------------------------------------------------- GitHub Copilot

public sealed class CopilotClient() : StandardDeviceClient(UsageProvider.Copilot)
{
    protected override string DeviceUrl => "https://github.com/login/device/code";
    protected override string TokenUrl => "https://github.com/login/oauth/access_token";
    protected override string ClientId => "Iv1.b507a08c87ecfe98";
    protected override string? Scope => "read:user";

    public override async Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken)
    {
        var login = "";
        if (await TrySendAsync(context, "copilot_user", ProviderRequest.Get("https://api.github.com/user")
                .Header("Authorization", "token " + tokens.AccessToken), cancellationToken).ConfigureAwait(false) is { Ok: true } user)
        {
            var email = BodyString(user, "email");
            login = email.Contains('@') ? email : BodyString(user, "login");
        }
        return new AuthTokens(tokens.AccessToken, tokens.AccessToken, "", AuthTokens.NeverExpires, "", login);
    }

    public override bool CanRefresh(AuthTokens tokens) => false;

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "copilot_usage", ProviderRequest.Get("https://api.github.com/copilot_internal/user")
            .Header("Authorization", "token " + tokens.AccessToken)
            .Header("Editor-Version", "vscode/1.96.2")
            .Header("Editor-Plugin-Version", "copilot-chat/0.26.7")
            .Header("User-Agent", "GitHubCopilotChat/0.26.7")
            .Header("X-Github-Api-Version", "2025-04-01"), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Copilot(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Kimi Code

public sealed class KimiClient() : StandardDeviceClient(UsageProvider.Kimi)
{
    protected override string DeviceUrl => "https://auth.kimi.com/api/oauth/device_authorization";
    protected override string TokenUrl => "https://auth.kimi.com/api/oauth/token";
    protected override string ClientId => "17e5f671-d194-4dfb-9706-5516cb48c098";

    protected override ProviderRequest Decorate(ProviderRequest request, SignInAttempt? attempt)
    {
        var deviceId = attempt?.ExtraValue("device_id") ?? "";
        if (deviceId.Length == 0)
        {
            deviceId = Guid.NewGuid().ToString().ToLowerInvariant();
            if (attempt is not null) attempt.Extra["device_id"] = deviceId;
        }
        var (model, os) = OperatingSystem.IsWindows() ? ("Windows PC", "Windows")
            : OperatingSystem.IsMacOS() ? ("Mac", "macOS") : ("Linux PC", "Linux");
        return request
            .Header("X-Msh-Platform", "kimi_cli")
            .Header("X-Msh-Version", "1.0.0")
            .Header("X-Msh-Device-Name", "Codex Meter")
            .Header("X-Msh-Device-Model", model)
            .Header("X-Msh-Os-Version", $"{os} {Environment.OSVersion.Version.Major}")
            .Header("X-Msh-Device-Id", deviceId);
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "kimi_usage",
            ProviderRequest.Get("https://api.kimi.com/coding/v1/usages").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Kimi(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- WorkOS (Factory, Cline)

internal static class WorkOs
{
    public const string Device = "https://api.workos.com/user_management/authorize/device";
    public const string Token = "https://api.workos.com/user_management/authenticate";
}

public sealed class FactoryClient() : StandardDeviceClient(UsageProvider.Factory)
{
    protected override string DeviceUrl => WorkOs.Device;
    protected override string TokenUrl => WorkOs.Token;
    protected override string ClientId => "client_01HNM792M5G5G1A2THWPXKFMXB";

    public override Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken) => Task.FromResult(tokens.Stored(context.Now()).WithJwtExpiry());

    public override async Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken) =>
        MergeRefresh(await SendAsync(context, "factory_token_refresh", ProviderRequest.Post(WorkOs.Token).Json(new JsonObject
        {
            ["client_id"] = ClientId, ["grant_type"] = "refresh_token", ["refresh_token"] = tokens.RefreshToken,
        }), cancellationToken).ConfigureAwait(false), tokens, context);

    private static ProviderRequest Factory(ProviderRequest request, AuthTokens tokens) => request.Bearer(tokens.AccessToken)
        .Header("x-factory-client", "web-app")
        .Header("Origin", "https://app.factory.ai")
        .Header("Referer", "https://app.factory.ai/");

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        string plan = "", email = "";
        if (await TrySendAsync(context, "factory_profile", Factory(ProviderRequest.Get("https://app.factory.ai/api/app/auth/me"), tokens),
                cancellationToken).ConfigureAwait(false) is { Ok: true } me)
        {
            plan = ProviderUsageParsers.FactoryPlan(me.Body);
            email = ProviderUsageParsers.FactoryEmail(me.Body);
        }
        var limits = await SendAsync(context, "factory_usage", Factory(ProviderRequest.Get("https://api.factory.ai/api/billing/limits"), tokens),
            cancellationToken).ConfigureAwait(false);
        if (limits.Status == 404)
            limits = await SendAsync(context, "factory_usage_legacy", Factory(
                ProviderRequest.Get("https://app.factory.ai/api/organization/subscription/usage?useCache=true"), tokens), cancellationToken).ConfigureAwait(false);
        Check(limits, "usage");
        return NonEmpty(ProviderUsageParsers.Factory(limits.Body, plan, context.Now()), email);
    }
}

public sealed class ClineClient() : StandardDeviceClient(UsageProvider.Cline)
{
    protected override string DeviceUrl => WorkOs.Device;
    protected override string TokenUrl => WorkOs.Token;
    protected override string ClientId => "client_01K3A541FN8TA3EPPHTD2325AR";

    /// <summary>Cline swaps the WorkOS session for its own tokens.</summary>
    public override async Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "cline_register", ProviderRequest.Post("https://api.cline.bot/api/v1/auth/register").Json(new JsonObject
        {
            ["accessToken"] = tokens.AccessToken, ["refreshToken"] = tokens.RefreshToken,
        }), cancellationToken).ConfigureAwait(false);
        // Fall back to the WorkOS token; the usage call retries it with Cline's prefix.
        if (!response.Ok) return tokens.Stored(context.Now(), "workos").WithJwtExpiry();
        return DeviceAuth.ParseTokens(response.Body, context.Now()).Stored(context.Now()).WithJwtExpiry();
    }

    public override async Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        if (tokens.AccountId == "workos")
            return MergeRefresh(await SendAsync(context, "cline_workos_refresh", ProviderRequest.Post(WorkOs.Token).Json(new JsonObject
            {
                ["client_id"] = ClientId, ["grant_type"] = "refresh_token", ["refresh_token"] = tokens.RefreshToken,
            }), cancellationToken).ConfigureAwait(false), tokens, context);
        return MergeRefresh(await SendAsync(context, "cline_token_refresh", ProviderRequest.Post("https://api.cline.bot/api/v1/auth/refresh").Json(new JsonObject
        {
            ["refreshToken"] = tokens.RefreshToken, ["grantType"] = "refresh_token",
        }), cancellationToken).ConfigureAwait(false), tokens, context);
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        const string url = "https://api.cline.bot/api/v1/users/me/plan/usage-limits";
        var response = await SendAsync(context, "cline_usage", ProviderRequest.Get(url).Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        if (response.Status == 401 && !tokens.AccessToken.StartsWith("workos:", StringComparison.Ordinal))
            response = await SendAsync(context, "cline_usage_workos", ProviderRequest.Get(url).Bearer("workos:" + tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        Check(response, "usage");
        return NonEmpty(ProviderUsageParsers.Cline(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Grok

public sealed class GrokClient() : StandardDeviceClient(UsageProvider.Grok)
{
    protected override string DeviceUrl => "https://auth.x.ai/oauth2/device/code";
    protected override string TokenUrl => "https://auth.x.ai/oauth2/token";
    protected override string ClientId => "b1a00492-073a-47ea-816f-4c329264a828";
    protected override string? Scope => "openid profile email offline_access grok-cli:access api:access";

    public override Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken)
    {
        var stored = tokens.Stored(context.Now()).WithJwtExpiry();
        if (stored.Email.Length == 0 && tokens.IdToken.Length > 0) stored = stored with { Email = ProviderJson.JwtEmail(tokens.IdToken) };
        return Task.FromResult(stored);
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "grok_usage", ProviderRequest.Get("https://cli-chat-proxy.grok.com/v1/billing?format=credits")
            .Bearer(tokens.AccessToken).Header("x-xai-token-auth", "xai-grok-cli"), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Grok(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Devin and Windsurf

public sealed class CodeiumClient(UsageProvider provider, string ideName) : ProviderClient(provider)
{
    public override string? PasteUrl(SignInAttempt attempt) => "https://www.codeium.com/profile?" + ProviderRequest.Query(
        ("response_type", "token"),
        ("redirect_uri", "vim-show-auth-token"),
        ("state", attempt.State),
        ("scope", "openid profile email"),
        ("redirect_parameters_type", "query"));

    public override string PasteHint =>
        $"Sign in with your {Provider.DisplayName} account, then copy the token the page shows and paste it here.";

    public override async Task<AuthTokens> FinishPasteAsync(SignInAttempt attempt, string pasted, ProviderContext context,
        CancellationToken cancellationToken)
    {
        var token = DeviceAuth.ExtractPasted(pasted, "access_token", "token", "auth_token", "firebase_id_token");
        if (token.Length == 0) throw new ProviderException("Paste the token first.");
        var response = await SendAsync(context, Provider.Id + "_register", ProviderRequest.Post("https://api.codeium.com/register_user/")
            .Json(new JsonObject { ["firebase_id_token"] = token }), cancellationToken).ConfigureAwait(false);
        var apiKey = BodyString(response, "api_key");
        if (!response.Ok || apiKey.Length == 0)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, "That token wasn't accepted. Copy it again and paste the whole thing."));
        var tokens = new AuthTokens(apiKey, apiKey, "", AuthTokens.NeverExpires, AuthTokens.ApiKeyAccount, BodyString(response, "name"));
        await FetchAsync(tokens, context, cancellationToken).ConfigureAwait(false);
        return tokens;
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var metadata = new JsonObject
        {
            ["apiKey"] = tokens.AccessToken, ["ideName"] = ideName, ["ideVersion"] = "1.108.2",
            ["extensionName"] = ideName, ["extensionVersion"] = "1.108.2", ["locale"] = "en",
        };
        var response = Check(await SendAsync(context, Provider.Id + "_usage",
            ProviderRequest.Post("https://server.codeium.com/exa.seat_management_pb.SeatManagementService/GetUserStatus")
                .Header("Connect-Protocol-Version", "1")
                .Json(new JsonObject { ["metadata"] = metadata }), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Codeium(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Augment

public sealed class AugmentClient() : ProviderClient(UsageProvider.Augment)
{
    private const string ClientId = "auggie-cli-json-paste";

    public override string? PasteUrl(SignInAttempt attempt) => "https://auth.augmentcode.com/authorize?" + ProviderRequest.Query(
        ("response_type", "code"),
        ("code_challenge", attempt.Challenge),
        ("code_challenge_method", "S256"),
        ("client_id", ClientId),
        ("state", attempt.State),
        ("prompt", "login"));

    public override string PasteHint =>
        "After you sign in, Augment shows a short block of text that starts with {\"code\". Copy all of it and paste it here.";

    public override async Task<AuthTokens> FinishPasteAsync(SignInAttempt attempt, string pasted, ProviderContext context,
        CancellationToken cancellationToken)
    {
        var reply = ProviderJson.Object(pasted);
        var code = ProviderJson.String(reply, "code");
        var state = ProviderJson.String(reply, "state");
        var tenant = ProviderJson.String(reply, "tenant_url");
        if (code.Length == 0 || tenant.Length == 0)
            throw new ProviderException("Paste the whole text block Augment shows, including the braces.");
        if (state.Length > 0 && state != attempt.State)
            throw new ProviderException("That code is from a different sign-in. Open the page again.");
        if (!IsAugmentTenant(tenant))
            throw new ProviderException("That sign-in points to an unexpected server, so it was not used.");
        if (!tenant.EndsWith('/')) tenant += "/";
        var response = await SendAsync(context, "augment_exchange", ProviderRequest.Post(tenant + "token").Json(new JsonObject
        {
            ["grant_type"] = "authorization_code", ["client_id"] = ClientId, ["code_verifier"] = attempt.Verifier,
            ["redirect_uri"] = "", ["code"] = code,
        }), cancellationToken).ConfigureAwait(false);
        var tokens = DeviceAuth.ParseTokens(response.Body, context.Now());
        if (!response.Ok || tokens.AccessToken.Length == 0)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"Augment sign-in failed (HTTP {response.Status})."));
        var stored = new AuthTokens(tokens.AccessToken, tokens.AccessToken, "", AuthTokens.NeverExpires, tenant, tokens.Email);
        await FetchAsync(stored, context, cancellationToken).ConfigureAwait(false);
        return stored;
    }

    /// <summary>Only https tenants under augmentcode.com receive the code.</summary>
    public static bool IsAugmentTenant(string tenant) =>
        tenant.StartsWith("https://", StringComparison.Ordinal)
        && Uri.TryCreate(tenant, UriKind.Absolute, out var uri)
        && uri.Host.ToLowerInvariant().EndsWith(".augmentcode.com", StringComparison.Ordinal);

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var tenant = tokens.AccountId;
        if (!tenant.StartsWith("https://", StringComparison.Ordinal))
            throw new ProviderUnauthorizedException("Sign in to Augment again from Accounts.");
        var response = Check(await SendAsync(context, "augment_usage",
            ProviderRequest.Post(tenant + "get-billing-summary").Bearer(tokens.AccessToken).Json([]), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Augment(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- OpenCode Go

public sealed class OpenCodeGoClient() : ProviderClient(UsageProvider.OpencodeGo)
{
    private const string Base = "https://opencode.ai/console/auth/device/";

    public override bool HasDeviceFlow => true;

    public override async Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "opencode_device", ProviderRequest.Post(Base + "code").Json(new JsonObject
        {
            ["client_id"] = "opencode-cli", ["supports_org_scope"] = true,
        }), cancellationToken).ConfigureAwait(false);
        var code = DeviceAuth.ParseDeviceCode(response.Body);
        if (!response.Ok || !code.IsValid)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"OpenCode could not start sign-in (HTTP {response.Status})."));
        attempt.Device = code;
        return code;
    }

    public override async Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "opencode_poll", ProviderRequest.Post(Base + "token").Json(new JsonObject
        {
            ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code",
            ["device_code"] = attempt.Device?.Code ?? "",
            ["client_id"] = "opencode-cli",
        }), cancellationToken).ConfigureAwait(false);
        return DeviceAuth.ParsePoll(response.Status, response.Body, context.Now());
    }

    public override Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken) => Task.FromResult(tokens.Stored(context.Now()).WithJwtExpiry());

    public override bool CanRefresh(AuthTokens tokens) => !tokens.IsApiKey && tokens.RefreshToken != tokens.AccessToken;

    public override async Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "opencode_refresh", ProviderRequest.Post(Base + "token").Json(new JsonObject
        {
            ["grant_type"] = "refresh_token", ["refresh_token"] = tokens.RefreshToken, ["client_id"] = "opencode-cli",
        }), cancellationToken).ConfigureAwait(false);
        var parsed = DeviceAuth.ParseTokens(response.Body, context.Now());
        if (!response.Ok || parsed.AccessToken.Length == 0)
            throw new ProviderUnauthorizedException("Your OpenCode sign-in expired. Sign in again.");
        return tokens.MergingRefresh(parsed.Stored(context.Now()).WithJwtExpiry());
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "opencode_usage",
            ProviderRequest.Get("https://opencode.ai/zen/go/v1/usage").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.OpencodeGo(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- MiniMax

public sealed class MiniMaxClient() : ProviderClient(UsageProvider.MiniMax)
{
    private const string ClientId = "78257093-7e40-4613-99e0-527b14b39113";
    private const string Account = "https://account.minimax.io/oauth2/";

    public override bool HasDeviceFlow => true;

    public override async Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "minimax_device", ProviderRequest.Post(Account + "device/code").Form(
            ("response_type", "code"),
            ("client_id", ClientId),
            ("scope", "group_id profile model.completion"),
            ("code_challenge", attempt.Challenge),
            ("code_challenge_method", "S256"),
            ("state", attempt.State)), cancellationToken).ConfigureAwait(false);
        var parsed = DeviceAuth.ParseDeviceCode(response.Body);
        // MiniMax polls with the user code itself.
        var code = parsed with { Code = parsed.Code.Length == 0 ? parsed.UserCode : parsed.Code };
        if (!response.Ok || !code.IsValid)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"MiniMax could not start sign-in (HTTP {response.Status})."));
        attempt.Device = code;
        return code;
    }

    public override async Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "minimax_poll", ProviderRequest.Post(Account + "token").Form(
            ("grant_type", "urn:ietf:params:oauth:grant-type:user_code"),
            ("client_id", ClientId),
            ("user_code", attempt.Device?.UserCode ?? ""),
            ("code_verifier", attempt.Verifier)), cancellationToken).ConfigureAwait(false);
        return DeviceAuth.ParsePoll(response.Status, response.Body, context.Now());
    }

    public override bool CanRefresh(AuthTokens tokens) => !tokens.IsApiKey && tokens.RefreshToken != tokens.AccessToken;

    public override async Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "minimax_refresh", ProviderRequest.Post(Account + "token").Form(
            ("grant_type", "refresh_token"), ("client_id", ClientId), ("refresh_token", tokens.RefreshToken)), cancellationToken).ConfigureAwait(false);
        var parsed = DeviceAuth.ParseTokens(response.Body, context.Now());
        if (!response.Ok || parsed.AccessToken.Length == 0)
            throw new ProviderUnauthorizedException("Your MiniMax sign-in expired. Sign in again.");
        return tokens.MergingRefresh(parsed.Stored(context.Now()));
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "minimax_usage",
            ProviderRequest.Get("https://api.minimax.io/v1/token_plan/remains").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        if (response.Status == 404)
            response = await SendAsync(context, "minimax_usage_legacy",
                ProviderRequest.Get("https://api.minimax.io/v1/api/openplatform/coding_plan/remains").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        Check(response, "usage");
        if (ProviderJson.Obj(ProviderJson.Object(response.Body), "base_resp") is { } baseResp && ProviderJson.Int(baseResp, "status_code", 0) == 1004)
            throw new ProviderUnauthorizedException("MiniMax rejected the saved sign-in.");
        return NonEmpty(ProviderUsageParsers.MiniMax(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Z.ai

public sealed class ZaiClient() : ProviderClient(UsageProvider.Zai)
{
    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        const string url = "https://api.z.ai/api/monitor/usage/quota/limit";
        var response = await SendAsync(context, "zai_usage", ProviderRequest.Get(url).Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        if (response.Status == 401)
            response = await SendAsync(context, "zai_usage_raw", ProviderRequest.Get(url).Header("Authorization", tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        Check(response, "usage");
        var root = ProviderJson.Object(response.Body);
        if (ProviderJson.Has(root, "success") && !ProviderJson.Bool(root, "success", true))
        {
            var code = ProviderJson.Int(root, "code", 0);
            if (code is 401 or 1001 or 1002) throw new ProviderUnauthorizedException("Z.ai rejected this API key.");
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"Z.ai returned an error (code {code})."));
        }
        return NonEmpty(ProviderUsageParsers.Zai(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Kiro (AWS Builder ID)

public sealed class KiroClient() : ProviderClient(UsageProvider.Kiro)
{
    private const string Oidc = "https://oidc.us-east-1.amazonaws.com/";

    public override bool HasDeviceFlow => true;

    public override async Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var client = await SendAsync(context, "kiro_register", ProviderRequest.Post(Oidc + "client/register").Json(new JsonObject
        {
            ["clientName"] = "Codex Meter",
            ["clientType"] = "public",
            ["scopes"] = new JsonArray("codewhisperer:completions", "codewhisperer:analysis", "codewhisperer:conversations"),
        }), cancellationToken).ConfigureAwait(false);
        var clientId = BodyString(client, "clientId");
        var clientSecret = BodyString(client, "clientSecret");
        if (!client.Ok || clientId.Length == 0) throw new ProviderException($"AWS could not start sign-in (HTTP {client.Status}).");
        attempt.Extra["clientId"] = clientId;
        attempt.Extra["clientSecret"] = clientSecret;
        var response = await SendAsync(context, "kiro_device", ProviderRequest.Post(Oidc + "device_authorization").Json(new JsonObject
        {
            ["clientId"] = clientId, ["clientSecret"] = clientSecret, ["startUrl"] = "https://view.awsapps.com/start",
        }), cancellationToken).ConfigureAwait(false);
        var code = DeviceAuth.ParseDeviceCode(response.Body);
        if (!response.Ok || !code.IsValid)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"AWS could not start sign-in (HTTP {response.Status})."));
        attempt.Device = code;
        return code;
    }

    public override async Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "kiro_poll", ProviderRequest.Post(Oidc + "token").Json(new JsonObject
        {
            ["clientId"] = attempt.ExtraValue("clientId"),
            ["clientSecret"] = attempt.ExtraValue("clientSecret"),
            ["grantType"] = "urn:ietf:params:oauth:grant-type:device_code",
            ["deviceCode"] = attempt.Device?.Code ?? "",
        }), cancellationToken).ConfigureAwait(false);
        return DeviceAuth.ParsePoll(response.Status, response.Body, context.Now());
    }

    public override Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken)
    {
        // The refresh needs the registered client, so it rides along in the id-token slot.
        var client = new JsonObject { ["clientId"] = attempt.ExtraValue("clientId"), ["clientSecret"] = attempt.ExtraValue("clientSecret") };
        return Task.FromResult(tokens.Stored(context.Now()) with { IdToken = client.ToJsonString(), AccountId = "" });
    }

    public override bool CanRefresh(AuthTokens tokens) => tokens.IdToken.StartsWith('{') && tokens.RefreshToken != tokens.AccessToken;

    public override async Task<AuthTokens> RefreshAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var client = ProviderJson.Object(tokens.IdToken);
        var response = await SendAsync(context, "kiro_refresh", ProviderRequest.Post(Oidc + "token").Json(new JsonObject
        {
            ["clientId"] = ProviderJson.String(client, "clientId"),
            ["clientSecret"] = ProviderJson.String(client, "clientSecret"),
            ["grantType"] = "refresh_token",
            ["refreshToken"] = tokens.RefreshToken,
        }), cancellationToken).ConfigureAwait(false);
        var parsed = DeviceAuth.ParseTokens(response.Body, context.Now());
        if (!response.Ok || parsed.AccessToken.Length == 0)
            throw new ProviderUnauthorizedException("Your AWS Builder ID sign-in expired. Sign in again.");
        var refreshed = parsed.Stored(context.Now());
        return tokens.MergingRefresh(new AuthTokens(refreshed.AccessToken, parsed.RefreshToken, "", refreshed.ExpiresAt));
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "kiro_usage",
            ProviderRequest.Get("https://codewhisperer.us-east-1.amazonaws.com/getUsageLimits?origin=AI_EDITOR&resourceType=AGENTIC_REQUEST")
                .Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Kiro(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Amp

public sealed class AmpClient() : ProviderClient(UsageProvider.Amp)
{
    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "amp_usage",
            ProviderRequest.Post("https://ampcode.com/api/internal?userDisplayBalanceInfo").Bearer(tokens.AccessToken)
                .Json(new JsonObject { ["method"] = "userDisplayBalanceInfo", ["params"] = new JsonObject() }), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Amp(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- OpenRouter

/// <summary>Browser sign-in through a local callback (as on Android), or a pasted key.</summary>
public sealed class OpenRouterClient() : ProviderClient(UsageProvider.OpenRouter)
{
    public override string? LoopbackAuthUrl(string redirectUri, SignInAttempt attempt) => "https://openrouter.ai/auth?" + ProviderRequest.Query(
        ("callback_url", redirectUri + "?state=" + attempt.State),
        ("code_challenge", attempt.Challenge),
        ("code_challenge_method", "S256"));

    public override async Task<AuthTokens> FinishLoopbackAsync(SignInAttempt attempt, string code, ProviderContext context,
        CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "openrouter_exchange", ProviderRequest.Post("https://openrouter.ai/api/v1/auth/keys").Json(new JsonObject
        {
            ["code"] = code, ["code_verifier"] = attempt.Verifier, ["code_challenge_method"] = "S256",
        }), cancellationToken).ConfigureAwait(false);
        var key = BodyString(response, "key");
        if (!response.Ok || key.Length == 0)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"OpenRouter sign-in failed (HTTP {response.Status})."));
        return new AuthTokens(key, key, "", AuthTokens.NeverExpires, AuthTokens.ApiKeyAccount);
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var key = Check(await SendAsync(context, "openrouter_key",
            ProviderRequest.Get("https://openrouter.ai/api/v1/key").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false), "usage");
        // Only management keys can read credits; the key limit still shows.
        var credits = await TrySendAsync(context, "openrouter_credits",
            ProviderRequest.Get("https://openrouter.ai/api/v1/credits").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false);
        return NonEmpty(ProviderUsageParsers.OpenRouter(key.Body, credits is { Ok: true } ? credits.Body : "", context.Now()));
    }
}

// ---------------------------------------------------------------------- Kilo

public sealed class KiloClient() : ProviderClient(UsageProvider.Kilo)
{
    private const string Base = "https://api.kilo.ai/api/device-auth/codes";

    public override bool HasDeviceFlow => true;

    public override async Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "kilo_device", ProviderRequest.Post(Base).Json([]), cancellationToken).ConfigureAwait(false);
        var code = DeviceAuth.ParseDeviceCode(response.Body);
        if (!response.Ok || code.Code.Length == 0)
            throw new ProviderException(ProviderHttp.ServerMessage(response.Body, $"Kilo could not start sign-in (HTTP {response.Status})."));
        var page = code.VerificationUri.Length == 0 ? "https://app.kilo.ai/device-auth?code=" + code.Code : code.VerificationUri;
        var device = new DeviceCode(code.Code, code.UserCode, page, code.VerificationUriComplete, 3, code.ExpiresInSeconds);
        attempt.Device = device;
        return device;
    }

    public override async Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "kilo_poll", ProviderRequest.Get(Base + "/" + Uri.EscapeDataString(attempt.Device?.Code ?? "")),
            cancellationToken).ConfigureAwait(false);
        return DeviceAuth.ParsePoll(response.Status, response.Body, context.Now());
    }

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "kilo_usage",
            ProviderRequest.Get("https://api.kilo.ai/api/profile/balance").Bearer(tokens.AccessToken), cancellationToken).ConfigureAwait(false), "balance");
        return NonEmpty(ProviderUsageParsers.Kilo(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Codebuff

public sealed class CodebuffClient() : ProviderClient(UsageProvider.Codebuff)
{
    private const string Base = "https://www.codebuff.com/api/";

    public override bool HasDeviceFlow => true;

    public override async Task<DeviceCode> StartDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var fingerprint = "codex-meter-" + Pkce.RandomToken(12);
        var response = await SendAsync(context, "codebuff_device", ProviderRequest.Post(Base + "auth/cli/code").Json(new JsonObject
        {
            ["fingerprintId"] = fingerprint,
        }), cancellationToken).ConfigureAwait(false);
        var body = ProviderJson.Object(response.Body);
        var login = ProviderJson.String(body, "loginUrl");
        if (!response.Ok || login.Length == 0) throw new ProviderException($"Codebuff could not start sign-in (HTTP {response.Status}).");
        attempt.Extra["fingerprintId"] = fingerprint;
        attempt.Extra["fingerprintHash"] = ProviderJson.String(body, "fingerprintHash");
        attempt.Extra["expiresAt"] = ProviderJson.String(body, "expiresAt");
        var device = new DeviceCode(fingerprint, "", login, login, 3, 900);
        attempt.Device = device;
        return device;
    }

    public override async Task<DevicePoll> PollDeviceAsync(SignInAttempt attempt, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = await SendAsync(context, "codebuff_poll", ProviderRequest.Get(Base + "auth/cli/status?" + ProviderRequest.Query(
            ("fingerprintId", attempt.ExtraValue("fingerprintId")),
            ("fingerprintHash", attempt.ExtraValue("fingerprintHash")),
            ("expiresAt", attempt.ExtraValue("expiresAt")))), cancellationToken).ConfigureAwait(false);
        var user = ProviderJson.Obj(ProviderJson.Object(response.Body), "user");
        var token = ProviderJson.String(user, "authToken");
        if (response.Ok && token.Length > 0)
            return new DevicePoll(DevicePollState.Done, new DeviceTokens(token, token, Email: ProviderJson.String(user, "email")));
        // Codebuff answers 401 until the browser approves the login.
        return new DevicePoll(DevicePollState.Pending);
    }

    public override Task<AuthTokens> FinishDeviceAsync(SignInAttempt attempt, DeviceTokens tokens, ProviderContext context,
        CancellationToken cancellationToken) =>
        Task.FromResult(new AuthTokens(tokens.AccessToken, tokens.AccessToken, "", AuthTokens.NeverExpires,
            attempt.ExtraValue("fingerprintId"), tokens.Email));

    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, "codebuff_usage", ProviderRequest.Post(Base + "v1/usage").Bearer(tokens.AccessToken)
            .Json(new JsonObject { ["fingerprintId"] = tokens.AccountId, ["authToken"] = tokens.AccessToken }), cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.Codebuff(response.Body, context.Now()));
    }
}

// ---------------------------------------------------------------------- Key-only providers

public sealed class KeyQuotaClient(UsageProvider provider, string url, long defaultSeconds) : ProviderClient(provider)
{
    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var response = Check(await SendAsync(context, Provider.Id + "_usage", ProviderRequest.Get(url).Bearer(tokens.AccessToken),
            cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.KeyQuota(response.Body, defaultSeconds, context.Now()));
    }
}

public sealed class WarpClient() : ProviderClient(UsageProvider.Warp)
{
    public override async Task<ProviderFetch> FetchAsync(AuthTokens tokens, ProviderContext context, CancellationToken cancellationToken)
    {
        var query = new JsonObject
        {
            ["operationName"] = "GetRequestLimitInfo",
            ["variables"] = new JsonObject(),
            ["query"] = "query GetRequestLimitInfo { user { user { requestLimitInfo { isUnlimited nextRefreshTime requestLimit requestsUsedSinceLastRefresh } } } }",
        };
        var response = Check(await SendAsync(context, "warp_usage",
            ProviderRequest.Post("https://app.warp.dev/graphql/v2?op=GetRequestLimitInfo").Bearer(tokens.AccessToken).Json(query),
            cancellationToken).ConfigureAwait(false), "usage");
        return NonEmpty(ProviderUsageParsers.KeyQuota(response.Body, ProviderUsageParsers.Month, context.Now()));
    }
}
