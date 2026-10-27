namespace CodexMeter.Core.Auth;

/// <summary>Stored credentials for one provider account.</summary>
public sealed record AuthTokens(string AccessToken, string RefreshToken, string IdToken, DateTimeOffset ExpiresAt,
    string AccountId = "", string Email = "")
{
    /// <summary>Expiry for credentials that never expire (API keys, GitHub tokens).</summary>
    public static readonly DateTimeOffset NeverExpires = new(9999, 1, 1, 0, 0, 0, TimeSpan.Zero);

    /// <summary>Marks a stored credential that is a pasted API key: it never refreshes.</summary>
    public const string ApiKeyAccount = "api_key";

    public bool IsApiKey => AccountId == ApiKeyAccount;

    public bool IsUsable => AccessToken.Length > 0 && RefreshToken.Length > 0;

    /// <summary>Refresh five minutes before expiry.</summary>
    public bool ShouldRefresh(DateTimeOffset now) => ExpiresAt <= now.AddMinutes(5);
}
