using System.Text.Json.Nodes;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Parsing;

/// <summary>
/// Parses ChatGPT's <c>/backend-api/wham/usage</c> response (the route Codex reads). A port of
/// iOS <c>UsageParser</c>; windows are classified by length, not by their JSON slot.
/// </summary>
public static class CodexUsageParser
{
    private const long FiveHours = 18_000;
    private const long Week = 604_800;
    private const long Month = 2_592_000;
    private const long MonthMin = 864_000;
    private const long MonthMax = 3_888_000;

    private sealed record Candidate(int Id, UsageWindow Window);

    public static UsageSnapshot Parse(string json, DateTimeOffset fetchedAt)
    {
        var root = Json.ParseObject(json);
        var planType = Json.Str(Json.Get(root, "plan_type"));
        var nextId = 0;
        Candidate? MakeCandidate(JsonObject? obj)
        {
            var window = ParseWindow(obj);
            return window is null ? null : new Candidate(nextId++, window);
        }

        var rateLimit = Json.Obj(Json.Get(root, "rate_limit"));
        var allowed = true;
        var limitReached = false;
        var primaryCandidates = new List<Candidate>();
        if (rateLimit is not null)
        {
            allowed = Json.Bool(Json.Get(rateLimit, "allowed"), true);
            limitReached = Json.Bool(Json.Get(rateLimit, "limit_reached"), false);
            if (MakeCandidate(Json.Obj(Json.Get(rateLimit, "primary_window"))) is { } primary) primaryCandidates.Add(primary);
            if (MakeCandidate(Json.Obj(Json.Get(rateLimit, "secondary_window"))) is { } secondary) primaryCandidates.Add(secondary);
        }

        var additional = new List<UsageLimit>();
        if (Json.Arr(Json.Get(root, "additional_rate_limits")) is { } rawAdditional)
        {
            for (var index = 0; index < rawAdditional.Count; index++)
            {
                if (Json.Obj(rawAdditional[index]) is not { } item) continue;
                var nested = Json.Obj(Json.Get(item, "rate_limit")) ?? item;
                var first = ParseWindow(Json.Obj(Json.Get(nested, "primary_window")));
                var second = ParseWindow(Json.Obj(Json.Get(nested, "secondary_window")));
                if (first is null && second is null) continue;
                var name = Json.Str(Json.Get(item, "limit_name"));
                var feature = Json.Str(Json.Get(item, "metered_feature"));
                var identity = new[] { Json.Str(Json.Get(item, "limit_id")), name, feature, "additional" }
                    .First(v => v.Trim().Length > 0);
                additional.Add(new UsageLimit($"{identity}-{index}", name, feature,
                    Json.Bool(Json.Get(nested, "allowed"), true), Json.Bool(Json.Get(nested, "limit_reached"), false),
                    first, second));
            }
        }

        var fiveHour = Nearest(primaryCandidates, FiveHours, 10_800, 28_800, []);
        var weekly = Nearest(primaryCandidates, Week, 432_000, 777_600, fiveHour is null ? [] : [fiveHour.Id]);
        var monthly = Nearest(primaryCandidates, Month, MonthMin, MonthMax,
            new[] { fiveHour?.Id, weekly?.Id }.OfType<int>().ToArray());

        // Go-style plans can report one window outside the standard buckets; keep it visible.
        if (fiveHour is null && weekly is null && monthly is null && primaryCandidates.Count > 0)
        {
            var leftover = primaryCandidates.MaxBy(c => c.Window.WindowSeconds)!;
            if (leftover.Window.WindowSeconds >= MonthMin) monthly = leftover;
            else if (leftover.Window.WindowSeconds >= 86_400) weekly = leftover;
            else fiveHour = leftover;
        }

        var resetCredits = Json.Obj(Json.Get(root, "rate_limit_reset_credits"));
        var available = resetCredits is null ? -1 : Json.Int(Json.Get(resetCredits, "available_count"), -1);

        UsageCredits? credits = null;
        if (Json.Obj(Json.Get(root, "credits")) is { } rawCredits
            && (rawCredits.ContainsKey("has_credits") || rawCredits.ContainsKey("unlimited") || rawCredits.ContainsKey("balance")))
        {
            var balance = Json.Get(rawCredits, "balance");
            credits = new UsageCredits(Json.Bool(Json.Get(rawCredits, "has_credits"), false),
                Json.Bool(Json.Get(rawCredits, "unlimited"), false), balance is null ? null : Json.Str(balance));
        }

        SpendControl? spendControl = null;
        if (Json.Obj(Json.Get(root, "spend_control")) is { } control)
        {
            spendControl = new SpendControl(Json.Bool(Json.Get(control, "reached"), false),
                ParseSpendControlLimit(Json.Obj(Json.Get(control, "individual_limit"))));
        }

        return new UsageSnapshot(planType, allowed, limitReached, fiveHour?.Window, weekly?.Window, monthly?.Window,
            available >= 0 ? available : null, additional, credits, spendControl, fetchedAt);
    }

    private static UsageWindow? ParseWindow(JsonObject? obj)
    {
        if (obj is null || !Json.Has(obj, "used_percent") || !Json.Has(obj, "limit_window_seconds")) return null;
        if (Json.Finite(Json.Get(obj, "used_percent")) is not { } used) return null;
        var duration = Json.Int64(Json.Get(obj, "limit_window_seconds"), -1);
        if (duration <= 0) return null;
        var resetAfter = Json.Int64(Json.Get(obj, "reset_after_seconds"), 0);
        var resetAt = Json.Int64(Json.Get(obj, "reset_at"), 0);
        return new UsageWindow(Json.RoundHalfUp(used), duration, resetAfter,
            resetAt > 0 ? DateTimeOffset.FromUnixTimeSeconds(resetAt) : null);
    }

    /// <summary>Amounts arrive as strings or numbers; returns null when nothing usable is present.</summary>
    private static SpendControlLimit? ParseSpendControlLimit(JsonObject? obj)
    {
        if (obj is null) return null;
        var limit = Amount(Json.Get(obj, "limit"));
        var used = Amount(Json.Get(obj, "used"));
        var remaining = Amount(Json.Get(obj, "remaining"));
        int usedPercent;
        if (Json.Finite(Json.Get(obj, "used_percent")) is { } percent)
            usedPercent = Json.RoundHalfUp(percent);
        else if (Json.Finite(Json.Get(obj, "remaining_percent")) is { } remainingPercent)
            usedPercent = 100 - Json.RoundHalfUp(remainingPercent);
        else if (limit is not null && used is not null
                 && double.TryParse(limit, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var l)
                 && double.TryParse(used, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var u)
                 && l > 0 && double.IsFinite(u))
            usedPercent = Json.RoundHalfUp(u / l * 100);
        else
            return null;

        var resetAt = Json.Int64(Json.Get(obj, "reset_at"), 0);
        return new SpendControlLimit(Json.Str(Json.Get(obj, "source")), limit, used, remaining, usedPercent,
            Json.Int64(Json.Get(obj, "reset_after_seconds"), 0),
            resetAt > 0 ? DateTimeOffset.FromUnixTimeSeconds(resetAt) : null);
    }

    private static string? Amount(JsonNode? value)
    {
        if (value is JsonValue v && v.GetValueKind() == System.Text.Json.JsonValueKind.String)
        {
            var trimmed = v.GetValue<string>().Trim();
            return trimmed.Length == 0 ? null : trimmed;
        }
        return Json.Double(value) is null ? null : Json.Str(value);
    }

    private static Candidate? Nearest(List<Candidate> candidates, long target, long min, long max, int[] excluded)
    {
        Candidate? best = null;
        var bestDistance = long.MaxValue;
        foreach (var candidate in candidates)
        {
            var seconds = candidate.Window.WindowSeconds;
            if (excluded.Contains(candidate.Id) || seconds < min || seconds > max) continue;
            var distance = Math.Abs(seconds - target);
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }
}
