namespace CodexMeter.Core.Parsing;

/// <summary>Account id and email from OpenAI id/access tokens.</summary>
public sealed record JwtClaims(string AccountId, string Email)
{
    public static JwtClaims FromTokens(string? idToken, string? accessToken)
    {
        var id = Parse(idToken);
        var access = Parse(accessToken);
        return new JwtClaims(id.AccountId.Length == 0 ? access.AccountId : id.AccountId,
            id.Email.Length == 0 ? access.Email : id.Email);
    }

    public static JwtClaims Parse(string? token)
    {
        var payload = Json.JwtPayload(token);
        if (payload is null) return new JwtClaims("", "");
        var accountId = Json.Str(Json.Get(payload, "chatgpt_account_id"));
        if (accountId.Length == 0 && Json.Obj(Json.Get(payload, "https://api.openai.com/auth")) is { } auth)
            accountId = Json.Str(Json.Get(auth, "chatgpt_account_id"));
        if (accountId.Length == 0 && Json.Arr(Json.Get(payload, "organizations")) is { Count: > 0 } orgs
            && Json.Obj(orgs[0]) is { } org)
            accountId = Json.Str(Json.Get(org, "id"));
        return new JwtClaims(accountId, Json.Str(Json.Get(payload, "email")));
    }
}
