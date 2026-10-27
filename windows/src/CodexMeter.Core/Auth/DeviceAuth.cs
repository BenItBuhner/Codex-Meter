using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CodexMeter.Core.Parsing;

namespace CodexMeter.Core.Auth;

/// <summary>What the sign-in dialog shows for a device-code sign-in: a code to type on the provider's page.</summary>
public sealed record DeviceCode
{
    public DeviceCode(string deviceCodeValue, string userCode, string verificationUri, string verificationUriComplete,
        long intervalSeconds, long expiresInSeconds)
    {
        Code = deviceCodeValue ?? "";
        UserCode = userCode ?? "";
        VerificationUri = verificationUri ?? "";
        VerificationUriComplete = verificationUriComplete ?? "";
        IntervalSeconds = Math.Max(1, Math.Min(30, intervalSeconds <= 0 ? 5 : intervalSeconds));
        ExpiresInSeconds = expiresInSeconds <= 0 ? 900 : expiresInSeconds;
    }

    /// <summary>The secret the app polls with (never shown).</summary>
    public string Code { get; init; }
    public string UserCode { get; init; }
    public string VerificationUri { get; init; }
    /// <summary>A page that already has the code filled in, when the provider gives one.</summary>
    public string VerificationUriComplete { get; init; }
    public long IntervalSeconds { get; init; }
    public long ExpiresInSeconds { get; init; }

    /// <summary>The page to open: the pre-filled one when available.</summary>
    public string OpenUrl => VerificationUriComplete.Length == 0 ? VerificationUri : VerificationUriComplete;

    public bool IsValid => Code.Length > 0 && VerificationUri.Length > 0;
}

public enum DevicePollState { Pending, SlowDown, Denied, Expired, Done, Failed }

/// <summary>Tokens from any token endpoint. Missing values are empty; expiry is in seconds.</summary>
public sealed record DeviceTokens(string AccessToken, string RefreshToken = "", string IdToken = "", long ExpiresInSeconds = 0,
    string Email = "")
{
    /// <summary>
    /// Stored form. Tokens that never expire keep the access token as the refresh token too,
    /// because <see cref="AuthTokens.IsUsable"/> needs both.
    /// </summary>
    public AuthTokens Stored(DateTimeOffset now, string accountId = "") => new(
        AccessToken,
        RefreshToken.Length == 0 ? AccessToken : RefreshToken,
        IdToken,
        ExpiresInSeconds > 0 ? now.AddSeconds(ExpiresInSeconds) : AuthTokens.NeverExpires,
        accountId,
        Email);
}

public sealed record DevicePoll(DevicePollState State, DeviceTokens? Tokens = null, string Message = "");

/// <summary>
/// Pure parsing for device-code sign-in (RFC 8628 and the camelCase variants some providers use)
/// and for token responses. Port of Android's and iOS's <c>DeviceAuth</c>.
/// </summary>
public static class DeviceAuth
{
    public static DeviceCode ParseDeviceCode(string? body)
    {
        var o = ProviderJson.Object(body);
        return new DeviceCode(
            Str(o, "device_code", "deviceCode", "code", "device_auth_id"),
            Str(o, "user_code", "userCode", "code"),
            Str(o, "verification_uri", "verificationUri", "verification_url", "verificationUrl"),
            Str(o, "verification_uri_complete", "verificationUriComplete", "verification_url_complete", "verificationUrlComplete"),
            ProviderJson.Int(o, "interval", 5),
            ProviderJson.Int(o, "expires_in", ProviderJson.Int(o, "expiresIn", 900)));
    }

    /// <summary>Reads a token endpoint's reply while the user is still approving the code.</summary>
    public static DevicePoll ParsePoll(int status, string? body, DateTimeOffset? now = null)
    {
        var o = ProviderJson.Object(body);
        var error = Str(o, "error", "code").ToLowerInvariant();
        if (ProviderJson.Obj(o, "error") is { } nested) error = Str(nested, "code", "type", "message").ToLowerInvariant();
        var tokens = ParseTokens(body, now);
        if (status is >= 200 and < 300 && tokens.AccessToken.Length > 0) return new DevicePoll(DevicePollState.Done, tokens);
        if (error.Contains("authorization_pending") || error.Contains("pending") || status == 202 || status == 428)
            return new DevicePoll(DevicePollState.Pending);
        if (error.Contains("slow_down")) return new DevicePoll(DevicePollState.SlowDown);
        if (error.Contains("access_denied") || error.Contains("denied") || status == 403)
            return new DevicePoll(DevicePollState.Denied, Message: "The sign-in was declined.");
        if (error.Contains("expired") || status == 410)
            return new DevicePoll(DevicePollState.Expired, Message: "The code expired. Start again.");
        var description = Str(o, "error_description", "message");
        return new DevicePoll(DevicePollState.Failed, Message: description.Length == 0 ? $"Sign-in failed (HTTP {status})." : description);
    }

    public static DeviceTokens ParseTokens(string? body, DateTimeOffset? now = null)
    {
        var at = now ?? DateTimeOffset.UtcNow;
        var o = ProviderJson.Object(body);
        if (ProviderJson.Obj(o, "data") is { } data && !o.ContainsKey("access_token") && !o.ContainsKey("accessToken")) o = data;
        var email = Str(o, "email");
        if (email.Length == 0 && ProviderJson.Obj(o, "user") is { } user) email = Str(user, "email");
        if (email.Length == 0 && ProviderJson.Obj(o, "userInfo") is { } info) email = Str(info, "email");
        var expiresIn = ProviderJson.Int(o, "expires_in", ProviderJson.Int(o, "expiresIn", 0));
        if (expiresIn <= 0)
        {
            var expiresAt = ProviderUsageParsers.EpochSeconds(ProviderUsageParsers.First(o, "expiresAt", "expires_at"));
            if (expiresAt > 0) expiresIn = Math.Max(1, expiresAt - at.ToUnixTimeSeconds());
        }
        return new DeviceTokens(
            Str(o, "access_token", "accessToken", "token", "key", "api_key"),
            Str(o, "refresh_token", "refreshToken"),
            Str(o, "id_token", "idToken"),
            Math.Max(0, expiresIn),
            email.Contains('@') ? email : "");
    }

    /// <summary>Random URL-safe value for PKCE verifiers, states and one-time ids.</summary>
    public static string RandomToken(int bytes) => Pkce.RandomToken(bytes);

    /// <summary>
    /// Pulls the useful part out of whatever the user pasted: a bare token, a JSON object, or a
    /// URL with the value in its query or fragment.
    /// </summary>
    public static string ExtractPasted(string? pasted, params string[] keys)
    {
        var text = (pasted ?? "").Trim();
        if (text.Length == 0) return "";
        if (text.StartsWith('{'))
        {
            var value = Str(ProviderJson.Object(text), keys);
            return value.Length == 0 ? text : value;
        }
        var marker = Math.Max(text.IndexOf('?'), text.IndexOf('#'));
        if (text.StartsWith("http", StringComparison.Ordinal) && marker > 0)
        {
            foreach (var pair in text[(marker + 1)..].Split('&', '#'))
            {
                var equals = pair.IndexOf('=');
                if (equals <= 0) continue;
                if (keys.Contains(pair[..equals]))
                {
                    var raw = pair[(equals + 1)..];
                    try { return Uri.UnescapeDataString(raw.Replace('+', ' ')); }
                    catch (UriFormatException) { return raw; }
                }
            }
        }
        return Regex.Replace(text, @"\s+", "");
    }

    /// <summary>The first non-empty string value among <paramref name="keys"/>.</summary>
    internal static string Str(JsonObject? o, params string[] keys)
    {
        if (o is null) return "";
        foreach (var key in keys)
        {
            if (o.TryGetPropertyValue(key, out var node) && node is JsonValue value
                && value.GetValueKind() == System.Text.Json.JsonValueKind.String)
            {
                var text = value.GetValue<string>();
                if (text.Length > 0 && text != "null") return text;
            }
        }
        return "";
    }
}
