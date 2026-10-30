using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Parsing;

/// <summary>
/// Maps Claude Code's <c>GET /api/oauth/usage</c> response onto <see cref="UsageSnapshot"/>.
/// The server-built <c>limits</c> list drives the cards (session, weekly, model-scoped weekly
/// allocations such as Fable); older <c>five_hour</c>/<c>seven_day</c> objects are the fallback.
/// A port of iOS <c>ClaudeUsageParser</c>.
/// </summary>
public static partial class ClaudeUsageParser
{
    public const long FiveHourSeconds = 5 * 60 * 60;
    public const long SevenDaySeconds = 7 * 24 * 60 * 60;
    public const long MonthSeconds = 30 * 24 * 60 * 60;
    public const string LimitIdPrefix = "claude_";
    private static readonly string[] LegacyFableKeys = ["seven_day_fable", "fable_seven_day", "fable_weekly"];

    public static UsageSnapshot Parse(string json, string planLabel, DateTimeOffset fetchedAt)
    {
        var root = Json.ParseObject(json);
        UsageWindow? fiveHour = null, weekly = null;
        var additional = new List<UsageLimit>();
        var seen = new HashSet<string>();

        foreach (var item in Json.Arr(Json.Get(root, "limits")) ?? [])
        {
            if (Json.Obj(item) is not { } row) continue;
            var kind = Json.Str(Json.Get(row, "kind")).Trim().ToLowerInvariant();
            var group = Json.Str(Json.Get(row, "group")).Trim().ToLowerInvariant();
            var scopeObject = Json.Obj(Json.Get(row, "scope"));
            if (IsAllModels(scopeObject)) continue;
            var scope = ScopeName(scopeObject);
            if (Meter(row, WindowSeconds(kind, group)) is not { } window) continue;
            if (scope.Length == 0 && kind == "session" && fiveHour is null) fiveHour = window;
            else if (scope.Length == 0 && kind == "weekly_all" && weekly is null) weekly = window;
            else
            {
                var suffix = scope.Length == 0 ? kind
                    : group.Length == 0 ? "scoped_" + Slug(scope) : group + "_scoped_" + Slug(scope);
                var id = LimitIdPrefix + suffix;
                if (!seen.Add(id)) continue;
                additional.Add(new UsageLimit(id, scope.Length == 0 ? Humanize(kind) : scope + " only",
                    allowed: true, limitReached: Reached(window), primary: window));
            }
        }

        fiveHour ??= ParseWindow(Json.Get(root, "five_hour"), FiveHourSeconds);
        weekly ??= ParseWindow(Json.Get(root, "seven_day"), SevenDaySeconds);
        var fableId = LimitIdPrefix + "weekly_scoped_fable";
        if (!seen.Contains(fableId))
        {
            foreach (var key in LegacyFableKeys)
            {
                if (ParseWindow(Json.Get(root, key), SevenDaySeconds) is { } window)
                {
                    additional.Add(new UsageLimit(fableId, "Fable only", allowed: true, limitReached: Reached(window), primary: window));
                    break;
                }
            }
        }

        var plan = NormalizePlanLabel(planLabel);
        // Verified Ultra accounts have no 5-hour window (unverified ones keep a temporary one).
        // An idle placeholder (0%, no reset time) on Ultra isn't a real window, so drop it.
        if (plan == PlanUltra && fiveHour is { UsedPercent: <= 0, ResetAt: null }) fiveHour = null;

        var limitReached = Reached(fiveHour) || Reached(weekly) || additional.Any(l => l.LimitReached);
        if (ExtraUsage(Json.Get(root, "extra_usage")) is { } extra) additional.Add(extra);

        return new UsageSnapshot(plan, !limitReached, limitReached, fiveHour, weekly, null, null, additional,
            null, null, fetchedAt);
    }

    internal static UsageWindow? Meter(JsonObject row, long seconds) =>
        PercentUsed(Json.Get(row, "percent")) is { } percent
            ? new UsageWindow(percent, seconds, 0, ParseResetDate(Json.Get(row, "resets_at")))
            : null;

    internal static long WindowSeconds(string kind, string group)
    {
        if (group == "session" || kind.StartsWith("session", StringComparison.Ordinal)) return FiveHourSeconds;
        if (group.StartsWith("month", StringComparison.Ordinal) || kind.StartsWith("month", StringComparison.Ordinal)) return MonthSeconds;
        return SevenDaySeconds;
    }

    private static bool IsAllModels(JsonObject? scope)
    {
        if (scope is null) return false;
        foreach (var key in new[] { "model", "surface" })
        {
            var node = Json.Get(scope, key);
            var name = Json.Obj(node) is { } obj ? Json.Str(Json.Get(obj, "display_name")) : Json.Str(node);
            if (name.Trim().Equals("all models", StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    internal static string ScopeName(JsonObject? scope)
    {
        if (scope is null) return "";
        foreach (var key in new[] { "model", "surface" })
        {
            var node = Json.Get(scope, key);
            string name;
            if (Json.Obj(node) is { } obj)
            {
                name = Json.Str(Json.Get(obj, "display_name"));
                if (name.Trim().Length == 0) name = Json.Str(Json.Get(obj, "id"));
            }
            else name = Json.Str(node);
            name = name.Trim();
            if (name.Length > 0 && name != "null") return name;
        }
        return "";
    }

    internal static string Slug(string value)
    {
        var builder = new StringBuilder();
        var lastWasSeparator = true;
        foreach (var c in value.ToLowerInvariant())
        {
            if (char.IsAscii(c) && char.IsLetterOrDigit(c)) { builder.Append(c); lastWasSeparator = false; }
            else if (!lastWasSeparator) { builder.Append('_'); lastWasSeparator = true; }
        }
        return builder.ToString().TrimEnd('_');
    }

    internal static string Humanize(string kind)
    {
        var spaced = kind.Replace('_', ' ').Trim();
        return spaced.Length == 0 ? "Additional usage" : char.ToUpperInvariant(spaced[0]) + spaced[1..];
    }

    internal static UsageWindow? ParseWindow(JsonNode? value, long seconds) =>
        Json.Obj(value) is { } obj && PercentUsed(Json.Get(obj, "utilization")) is { } utilization
            ? new UsageWindow(utilization, seconds, 0, ParseResetDate(Json.Get(obj, "resets_at")))
            : null;

    /// <summary>Paid overage behaves like a monthly cap when enabled.</summary>
    internal static UsageLimit? ExtraUsage(JsonNode? value)
    {
        if (Json.Obj(value) is not { } obj || !Json.Bool(Json.Get(obj, "is_enabled"), false)
            || PercentUsed(Json.Get(obj, "utilization")) is not { } utilization) return null;
        var window = new UsageWindow(utilization, MonthSeconds, 0, ParseResetDate(Json.Get(obj, "resets_at")));
        return new UsageLimit(LimitIdPrefix + "extra_usage", "Extra usage", allowed: true,
            limitReached: window.UsedPercent >= 100, primary: window);
    }

    private static int? PercentUsed(JsonNode? value) =>
        Json.Finite(value) is { } used ? (int)Math.Clamp(Math.Floor(used + 0.5), -1000, 1000) : null;

    private static bool Reached(UsageWindow? window) => window is { UsedPercent: >= 100 };

    [GeneratedRegex(@"(Z|[+-]\d{2}:?\d{2})$", RegexOptions.IgnoreCase)]
    private static partial Regex ZoneSuffix();

    /// <summary>
    /// ISO-8601 with a zone (fractional seconds truncated), or epoch seconds as a number.
    /// Unparseable values become null.
    /// </summary>
    public static DateTimeOffset? ParseResetDate(JsonNode? value)
    {
        if (value is JsonValue v && v.GetValueKind() == JsonValueKind.Number)
        {
            var seconds = v.GetValue<double>();
            return double.IsFinite(seconds) && seconds > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)(seconds * 1000)) : null;
        }
        if (value is not JsonValue s || s.GetValueKind() != JsonValueKind.String) return null;
        return ParseIsoDate(s.GetValue<string>());
    }

    public static DateTimeOffset? ParseIsoDate(string? raw)
    {
        var text = raw?.Trim() ?? "";
        if (text.Length == 0 || text.Equals("null", StringComparison.OrdinalIgnoreCase)) return null;
        var t = text.IndexOfAny(['T', 't']);
        if (t < 0 || !ZoneSuffix().IsMatch(text)) return null;
        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal, out var parsed)
            && parsed.ToUnixTimeSeconds() > 0)
            return DateTimeOffset.FromUnixTimeSeconds(parsed.ToUnixTimeSeconds());
        return null;
    }

    public const string PlanUltra = "Ultra";
    public const string PlanMax = "Max";
    /// <summary>Dashboard copy shown in place of the 5-hour card on Ultra.</summary>
    public const string NoFiveHourLimitNote = "No 5-hour limit on Ultra \u00b7 weekly limits still apply";

    /// <summary>
    /// Current name for a Claude plan label, so snapshots cached before the rename read right:
    /// "Max 20x" (and raw tiers like <c>default_claude_max_20x</c>) become "Ultra", "Max 5x"
    /// becomes "Max". Other labels pass through trimmed.
    /// </summary>
    public static string NormalizePlanLabel(string? label)
    {
        var trimmed = (label ?? "").Trim();
        var key = new string(trimmed.ToLowerInvariant()
            .Where(c => !char.IsWhiteSpace(c) && c != '_' && c != '-').ToArray());
        if (key.StartsWith("default", StringComparison.Ordinal)) key = key["default".Length..];
        if (key.StartsWith("claude", StringComparison.Ordinal)) key = key["claude".Length..];
        return key switch
        {
            "ultra" or "max20x" or "max20" => PlanUltra,
            "max" or "max5x" or "max5" => PlanMax,
            _ => trimmed,
        };
    }

    /// <summary>True when the plan has no 5-hour window (verified Ultra) and none was reported.</summary>
    public static bool NoFiveHourLimit(UsageSnapshot? snapshot) =>
        snapshot is { FiveHour: null }
        && NormalizePlanLabel(snapshot.PlanType) == PlanUltra
        && (snapshot.Weekly is not null || snapshot.AdditionalLimits.Count > 0);

    /// <summary>Plan badge from <c>GET /api/oauth/profile</c>; never throws.</summary>
    public static string PlanLabel(string? profileJson)
    {
        var root = Json.TryParseObject(profileJson);
        if (root is null) return "";
        var account = Json.Obj(Json.Get(root, "account"));
        var organization = Json.Obj(Json.Get(root, "organization"));
        var tier = Json.Str(Json.Get(organization, "rate_limit_tier")).ToLowerInvariant();
        var type = Json.Str(Json.Get(organization, "organization_type")).ToLowerInvariant();
        var hasMax = Json.Bool(Json.Get(account, "has_claude_max"), false);
        var hasPro = Json.Bool(Json.Get(account, "has_claude_pro"), false);
        // Anthropic renamed Max 20x to Ultra and Max 5x to Max; old tiers map to the new names.
        if (tier.Contains("ultra") || tier.Contains("max_20x")) return PlanUltra;
        if (tier.Contains("max_5x")) return PlanMax;
        if (type.Contains("ultra")) return PlanUltra;
        if (type.Contains("enterprise")) return "Enterprise";
        if (type.Contains("team")) return "Team";
        if (type.Contains("max") || hasMax) return PlanMax;
        if (type.Contains("pro") || hasPro) return "Pro";
        if (type.Contains("free")) return "Free";
        return "";
    }

    public static string Email(string? profileJson)
    {
        var account = Json.Obj(Json.Get(Json.TryParseObject(profileJson), "account"));
        if (account is null) return "";
        var email = Json.Str(Json.Get(account, "email"));
        return email.Length == 0 ? Json.Str(Json.Get(account, "email_address")) : email;
    }
}
