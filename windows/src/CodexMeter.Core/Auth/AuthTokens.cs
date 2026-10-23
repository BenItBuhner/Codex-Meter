namespace CodexMeter.Core.Auth;

/// <summary>Stored credentials for one provider account.</summary>
public sealed record AuthTokens(string AccessToken, string RefreshToken, string IdToken, DateTimeOffset ExpiresAt,
    string AccountId = "", string Email = "")
{
    public bool IsUsable => AccessToken.Length > 0 && RefreshToken.Length > 0;

    /// <summary>Refresh five minutes before expiry.</summary>
    public bool ShouldRefresh(DateTimeOffset now) => ExpiresAt <= now.AddMinutes(5);
}
