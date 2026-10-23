using System.Globalization;
using System.Text.Json.Nodes;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Parsing;

/// <summary>
/// Maps Cursor's <c>DashboardService/GetCurrentPeriodUsage</c> onto <see cref="UsageSnapshot"/>.
/// Current plans refresh weekly (one included pool); legacy plans keep a monthly cycle with
/// "Cursor models" and "Other models" pools. A port of iOS <c>CursorUsageParser</c>.
/// </summary>
public static class CursorUsageParser
{
    public const long DefaultCycleSeconds = 30 * 24 * 60 * 60;
    public const string LimitIdPrefix = "cursor_";

    public static UsageSnapshot Parse(string json, string planLabel, DateTimeOffset fetchedAt)
    {
        var root = Json.ParseObject(json);
        var start = EpochMillis(Json.Get(root, "billingCycleStart"));
        var end = EpochMillis(Json.Get(root, "billingCycleEnd"));
        var cycleSeconds = start > 0 && end > start ? (end - start) / 1000 : DefaultCycleSeconds;
        DateTimeOffset? resetAt = end > 0 ? DateTimeOffset.FromUnixTimeSeconds(end / 1000) : null;
        var weekly = IsWeeklyCycle(cycleSeconds);

        UsageWindow? plan = null;
        var limits = new List<UsageLimit>();
        if (Json.Obj(Json.Get(root, "planUsage")) is { } planUsage)
        {
            var percent = Number(Json.Get(planUsage, "totalPercentUsed"));
            if (percent is null && Number(Json.Get(planUsage, "limit")) is { } limit && limit > 0)
            {
                var used = Number(Json.Get(planUsage, "includedSpend")) ?? Number(Json.Get(planUsage, "totalSpend")) ?? 0;
                percent = used / limit * 100;
            }
            if (percent is { } p) plan = new UsageWindow(RoundPercent(p), cycleSeconds, 0, resetAt);

            (string Key, string Id, string Name)[] pools =
                [("autoPercentUsed", "auto", "Cursor models"), ("apiPercentUsed", "api", "Other models")];
            var hasPools = !weekly && pools.Any(pool => Number(Json.Get(planUsage, pool.Key)) is not null);
            if (hasPools)
            {
                foreach (var pool in pools)
                {
                    var window = new UsageWindow(RoundPercent(Number(Json.Get(planUsage, pool.Key)) ?? 0), cycleSeconds, 0, resetAt);
                    limits.Add(new UsageLimit(LimitIdPrefix + pool.Id, pool.Name, limitReached: window.UsedPercent >= 100, primary: window));
                }
            }
        }

        var onDemand = OnDemand(Json.Obj(Json.Get(root, "spendLimitUsage")), cycleSeconds, resetAt, weekly);
        if (onDemand is not null) limits.Add(onDemand);

        var planSpent = (plan?.UsedPercent ?? 0) >= 100;
        var limitReached = planSpent && (onDemand?.LimitReached ?? true);
        return new UsageSnapshot(planLabel, !limitReached, limitReached, null, weekly ? plan : null,
            weekly ? null : plan, null, limits, null, null, fetchedAt);
    }

    public static bool IsWeeklyCycle(long cycleSeconds) => cycleSeconds > 0 && cycleSeconds <= 10 * 24 * 60 * 60;

    internal static UsageLimit? OnDemand(JsonObject? spend, long cycleSeconds, DateTimeOffset? resetAt, bool weekly)
    {
        if (spend is null) return null;
        var name = weekly ? "Additional usage" : "On-demand";
        var limit = Number(Json.Get(spend, "individualLimit")) ?? 0;
        var used = Number(Json.Get(spend, "individualUsed")) ?? 0;
        if (limit <= 0)
        {
            name = weekly ? "Team additional usage" : "Team on-demand";
            limit = Number(Json.Get(spend, "pooledLimit")) ?? 0;
            used = Number(Json.Get(spend, "pooledUsed")) ?? 0;
        }
        if (limit <= 0) return null;
        var window = new UsageWindow(RoundPercent(used / limit * 100), cycleSeconds, 0, resetAt);
        return new UsageLimit(LimitIdPrefix + "on_demand", $"{name} ({Dollars(used)} of {Dollars(limit)})",
            limitReached: used >= limit, primary: window);
    }

    internal static long EpochMillis(JsonNode? value)
    {
        if (Number(value) is { } number) return number > 0 ? (long)number : 0;
        return ClaudeUsageParser.ParseResetDate(value) is { } date ? date.ToUnixTimeMilliseconds() : 0;
    }

    internal static double? Number(JsonNode? value) => Json.Finite(value);

    /// <summary>Cents to a short dollar label: 1250 → "$12.50", 2000 → "$20".</summary>
    public static string Dollars(double cents)
    {
        var value = cents / 100;
        return Math.Abs(value - Math.Round(value)) < 0.005
            ? "$" + value.ToString("0", CultureInfo.InvariantCulture)
            : "$" + value.ToString("0.00", CultureInfo.InvariantCulture);
    }

    /// <summary>Any usage shows at least 1% so a light month never reads as untouched.</summary>
    private static int RoundPercent(double percent) =>
        percent <= 0 ? 0 : Math.Max(1, (int)Math.Min(1000, Math.Floor(percent + 0.5)));

    public static string PlanLabel(string? profileJson)
    {
        var root = Json.TryParseObject(profileJson);
        if (root is null) return "";
        var type = Json.Str(Json.Get(root, "membershipType"));
        if (type.Length == 0) type = Json.Str(Json.Get(root, "membership_type"));
        if (type.Length == 0) type = Json.Str(Json.Get(root, "individualMembershipType"));
        switch (type.Trim().ToLowerInvariant())
        {
            case "": return "";
            case "free" or "hobby": return "Hobby";
            case "free_trial": return "Pro trial";
            case "pro": return "Pro";
            case "pro_plus": return "Pro+";
            case "ultra": return "Ultra";
            case "team" or "business": return "Teams";
            case "enterprise": return "Enterprise";
            default:
                var spaced = type.Replace('_', ' ').Trim();
                return spaced.Length == 0 ? "" : char.ToUpperInvariant(spaced[0]) + spaced[1..];
        }
    }

    public static string Email(string? profileJson)
    {
        var email = Json.Str(Json.Get(Json.TryParseObject(profileJson), "email"));
        return email.Contains('@') ? email : "";
    }
}
