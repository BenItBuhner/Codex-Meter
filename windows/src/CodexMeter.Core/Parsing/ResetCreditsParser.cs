using System.Globalization;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Parsing;

/// <summary>Parses <c>/wham/rate-limit-reset-credits</c>.</summary>
public static class ResetCreditsParser
{
    public static ResetCreditsSnapshot Parse(string? json, DateTimeOffset fetchedAt)
    {
        var root = Json.ParseObject(string.IsNullOrWhiteSpace(json) ? "{}" : json);
        var credits = new List<RateLimitResetCredit>();
        foreach (var item in Json.Arr(Json.Get(root, "credits")) ?? [])
        {
            if (Json.Obj(item) is not { } obj) continue;
            credits.Add(new RateLimitResetCredit(
                Json.Str(Json.Get(obj, "id")),
                Json.Str(Json.Get(obj, "reset_type")),
                Json.Str(Json.Get(obj, "status")),
                ParseTimestamp(Json.Str(Json.Get(obj, "granted_at"))),
                ParseTimestamp(Json.Str(Json.Get(obj, "expires_at"))),
                Json.Str(Json.Get(obj, "title")),
                Json.Str(Json.Get(obj, "description"))));
        }
        var fallback = credits.Count(c => c.IsAvailable);
        return new ResetCreditsSnapshot(Json.Int(Json.Get(root, "available_count"), fallback), credits, fetchedAt);
    }

    /// <summary>ISO-8601 with or without fractional seconds; anything else is null.</summary>
    public static DateTimeOffset? ParseTimestamp(string? value)
    {
        var text = value?.Trim();
        if (string.IsNullOrEmpty(text)) return null;
        if (!text.Contains('T', StringComparison.OrdinalIgnoreCase)) return null;
        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var parsed)
            && parsed.ToUnixTimeSeconds() > 0)
            return parsed;
        return null;
    }
}
