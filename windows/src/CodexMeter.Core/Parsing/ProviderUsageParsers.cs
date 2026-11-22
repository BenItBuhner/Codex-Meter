using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Parsing;

/// <summary>
/// Turns each added provider's usage response into a <see cref="UsageSnapshot"/>. A line-for-line
/// port of Android's and iOS's <c>ProviderUsageParsers</c>, so every app reads the same windows
/// from the same body. Synthetic and v0 document their routes; Chutes publishes its API source;
/// the rest follow the providers' own CLIs and editors and open-source trackers (CodexBar,
/// OpenClaw). Every reader is defensive: a missing field leaves a window out rather than failing
/// the whole refresh.
///
/// Windows land in the snapshot by length: up to a day is the short window (<c>FiveHour</c>,
/// labelled on the dashboard by its real length), about a week is <c>Weekly</c>, about a month
/// is <c>Monthly</c>, and anything else becomes an additional limit.
/// </summary>
public static class ProviderUsageParsers
{
    public const long Hour = 3_600;
    public const long Day = 86_400;
    public const long Week = 604_800;
    public const long Month = 2_592_000;

    // ------------------------------------------------------------------ Shared building blocks

    /// <summary>Collects windows while a response is read, then builds the snapshot.</summary>
    internal sealed class Builder(DateTimeOffset now)
    {
        public DateTimeOffset Now { get; } = now;
        public string Plan = "";
        public UsageWindow? ShortWindow;
        public UsageWindow? Weekly;
        public UsageWindow? Monthly;
        public List<UsageLimit> Extras { get; } = [];
        public UsageCredits? Credits;

        /// <summary>Places a window by its length; the first window of each length wins.</summary>
        public void Add(string name, UsageWindow? window)
        {
            if (window is null) return;
            var seconds = window.WindowSeconds;
            if (seconds > 0 && seconds <= Day + 60 && ShortWindow is null) ShortWindow = window;
            else if (seconds >= 6 * Day && seconds <= 8 * Day && Weekly is null) Weekly = window;
            else if (seconds >= 27 * Day && seconds <= 32 * Day && Monthly is null) Monthly = window;
            else Extra(name, window);
        }

        public void Extra(string name, UsageWindow? window)
        {
            if (window is null) return;
            var id = Slug(name);
            if (Extras.Any(l => l.Id == id)) return;
            Extras.Add(new UsageLimit(id, name, "", window.UsedPercent < 100, window.UsedPercent >= 100, window, null));
        }

        public bool IsEmpty => ShortWindow is null && Weekly is null && Monthly is null && Extras.Count == 0
                               && Credits?.ShouldDisplay != true;

        public UsageSnapshot Build()
        {
            var reached = new[] { ShortWindow, Weekly, Monthly }.Any(w => (w?.UsedPercent ?? 0) >= 100);
            return new UsageSnapshot(Plan, !reached, reached, ShortWindow, Weekly, Monthly, null, Extras, Credits, null, Now);
        }
    }

    /// <summary>A window from percent used (0–100), its length, and when it resets (epoch seconds).</summary>
    internal static UsageWindow? MakeWindow(double usedPercent, long windowSeconds, long resetAt, DateTimeOffset now)
    {
        if (double.IsNaN(usedPercent)) return null;
        var nowSeconds = now.ToUnixTimeSeconds();
        var after = resetAt > nowSeconds ? resetAt - nowSeconds : 0;
        return new UsageWindow((int)Math.Round(Math.Clamp(usedPercent, 0, 100), MidpointRounding.AwayFromZero), windowSeconds, after,
            resetAt > 0 ? DateTimeOffset.FromUnixTimeSeconds(resetAt) : null);
    }

    /// <summary>Percent used from a limit and either how much is used or how much remains.</summary>
    internal static double PercentUsed(double limit, double used, double remaining)
    {
        if (double.IsNaN(limit) || limit <= 0) return double.NaN;
        if (!double.IsNaN(used)) return used * 100 / limit;
        if (!double.IsNaN(remaining)) return (limit - remaining) * 100 / limit;
        return double.NaN;
    }

    /// <summary>
    /// Epoch seconds from a timestamp in any shape these APIs use: seconds, milliseconds,
    /// microseconds, ISO-8601 instants or offsets, or a bare date. Returns 0 when unreadable.
    /// </summary>
    public static long EpochSeconds(JsonNode? value)
    {
        if (value is not JsonValue v) return 0;
        return v.GetValueKind() switch
        {
            JsonValueKind.Number => EpochSeconds(ProviderJson.Number(v)),
            JsonValueKind.String => EpochSeconds(v.GetValue<string>()),
            _ => 0,
        };
    }

    public static long EpochSeconds(double number)
    {
        if (!double.IsFinite(number) || number <= 0) return 0;
        if (number > 1e14) return (long)(number / 1_000_000);
        if (number > 1e11) return (long)(number / 1_000);
        return (long)number;
    }

    private static readonly Regex NumericText = new(@"^-?\d+(\.\d+)?$", RegexOptions.Compiled);

    public static long EpochSeconds(string? raw)
    {
        var text = (raw ?? "").Trim();
        if (text.Length == 0 || text == "null") return 0;
        if (NumericText.IsMatch(text))
            return EpochSeconds(double.Parse(text, CultureInfo.InvariantCulture));
        if (text.Length > 10 && text.Contains('T')
            && DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var instant)
            && HasZone(text))
            return instant.ToUnixTimeSeconds();
        if (text.Length >= 10 && DateTime.TryParseExact(text[..10], "yyyy-MM-dd", CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var day))
            return new DateTimeOffset(day, TimeSpan.Zero).ToUnixTimeSeconds();
        return 0;
    }

    /// <summary>Only full instants count (with Z or an offset), as ISO8601DateFormatter requires.</summary>
    private static bool HasZone(string text)
    {
        var time = text[(text.IndexOf('T') + 1)..];
        return time.EndsWith('Z') || time.EndsWith('z') || time.Contains('+') || time.Contains('-');
    }

    internal static double Number(JsonObject? o, params string[] keys)
    {
        if (o is null) return double.NaN;
        foreach (var key in keys)
        {
            switch (ProviderJson.Get(o, key))
            {
                case JsonValue value when value.GetValueKind() is JsonValueKind.Number or JsonValueKind.String:
                    var parsed = ProviderJson.Number(value);
                    if (!double.IsNaN(parsed)) return parsed;
                    break;
                case JsonObject inner:
                    // Grok wraps amounts as {"val": "123"}.
                    var nested = Number(inner, "val", "value", "amount");
                    if (!double.IsNaN(nested)) return nested;
                    break;
            }
        }
        return double.NaN;
    }

    public static JsonNode? First(JsonObject? o, params string[] keys)
    {
        if (o is null) return null;
        foreach (var key in keys)
        {
            var value = ProviderJson.Get(o, key);
            if (ProviderJson.IsNull(value)) continue;
            if (value is JsonValue v && v.GetValueKind() == JsonValueKind.String && v.GetValue<string>().Length == 0) continue;
            return value;
        }
        return null;
    }

    internal static string Str(JsonObject? o, params string[] keys) =>
        First(o, keys) is { } node ? ProviderJson.Text(node).Trim() : "";

    /// <summary>The nested object along <paramref name="path"/>, or null.</summary>
    internal static JsonObject? Path(JsonObject? root, params string[] path)
    {
        var current = root;
        foreach (var key in path) current = ProviderJson.Obj(current, key);
        return current;
    }

    internal static string Money(double amount) =>
        double.IsNaN(amount) ? "" : "$" + amount.ToString("0.00", CultureInfo.InvariantCulture);

    internal static UsageCredits? Balance(double dollars) =>
        double.IsNaN(dollars) ? null : new UsageCredits(true, false, Money(dollars));

    internal static string WholeCount(double value) => ((long)Math.Truncate(value)).ToString(CultureInfo.InvariantCulture);

    public static string TitleCase(string? raw)
    {
        if (raw is null) return "";
        var cleaned = raw.Replace('_', ' ').Replace('-', ' ').Trim();
        return string.Join(' ', cleaned.Split([' ', '\t', '\n'], StringSplitOptions.RemoveEmptyEntries)
            .Select(word => word[..1].ToUpperInvariant() + word[1..].ToLowerInvariant()));
    }

    internal static string Slug(string name)
    {
        var slug = Regex.Replace(name.ToLowerInvariant(), "[^a-z0-9]+", "_");
        slug = Regex.Replace(slug, "^_+|_+$", "");
        return slug.Length == 0 ? "limit" : slug;
    }

    /// <summary>Length of a window from a start and end in any timestamp shape, or <paramref name="fallback"/>.</summary>
    internal static long Span(JsonNode? start, JsonNode? end, long fallback)
    {
        var from = EpochSeconds(start);
        var to = EpochSeconds(end);
        return from > 0 && to > from ? to - from : fallback;
    }

    internal static double RatioPercent(double ratio) => ratio <= 1 ? ratio * 100 : ratio;

    // ------------------------------------------------------------------ GitHub Copilot

    private static readonly string[] CopilotMain = ["premium_interactions", "chat", "completions"];

    /// <summary><c>api.github.com/copilot_internal/user</c>. Premium requests are the monthly window.</summary>
    public static UsageSnapshot Copilot(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = CopilotPlan(Str(root, "copilot_plan", "access_type_sku")) };
        var reset = EpochSeconds(First(root, "quota_reset_date_utc", "quota_reset_date", "limited_user_reset_date"));
        if (ProviderJson.Obj(root, "quota_snapshots") is { } quotas)
        {
            foreach (var key in CopilotMain)
            {
                if (CopilotQuota(ProviderJson.Obj(quotas, key), reset, now) is not { } window) continue;
                if (key == "premium_interactions" && output.Monthly is null) output.Monthly = window;
                else output.Extra(CopilotName(key), window);
            }
            foreach (var key in quotas.Select(p => p.Key).Where(k => !CopilotMain.Contains(k)).ToList())
                output.Extra(CopilotName(key), CopilotQuota(ProviderJson.Obj(quotas, key), reset, now));
        }
        // Free accounts report counts instead of snapshots.
        if (ProviderJson.Obj(root, "monthly_quotas") is { } monthly && ProviderJson.Obj(root, "limited_user_quotas") is { } left)
        {
            foreach (var key in new[] { "chat", "completions" })
            {
                if (MakeWindow(PercentUsed(Number(monthly, key), double.NaN, Number(left, key)), Month, reset, now) is not { } window) continue;
                if (key == "chat" && output.Monthly is null) output.Monthly = window;
                else output.Extra(CopilotName(key), window);
            }
        }
        return output.Build();
    }

    private static UsageWindow? CopilotQuota(JsonObject? quota, long reset, DateTimeOffset now)
    {
        if (quota is null || ProviderJson.Bool(quota, "unlimited", false)) return null;
        var entitlement = Number(quota, "entitlement");
        var remaining = Number(quota, "remaining");
        // Placeholder rows for features the plan doesn't include.
        if (entitlement == 0 && remaining == 0) return null;
        var percentRemaining = Number(quota, "percent_remaining");
        var used = !double.IsNaN(percentRemaining) ? 100 - percentRemaining : PercentUsed(entitlement, double.NaN, remaining);
        return MakeWindow(used, Month, reset, now);
    }

    private static string CopilotName(string key) => key switch
    {
        "premium_interactions" => "Premium requests",
        "chat" => "Chat",
        "completions" => "Code completions",
        _ => TitleCase(key),
    };

    public static string CopilotPlan(string? sku)
    {
        var value = (sku ?? "").ToLowerInvariant();
        if (value.Length == 0) return "";
        if (value.Contains("pro_plus") || value.Contains("pro+") || value.Contains("individual_pro")) return "Pro+";
        if (value.Contains("free")) return "Free";
        if (value.Contains("individual") || value == "pro") return "Pro";
        if (value.Contains("business")) return "Business";
        if (value.Contains("enterprise")) return "Enterprise";
        return TitleCase(value);
    }

    // ------------------------------------------------------------------ Kimi Code

    /// <summary><c>api.kimi.com/coding/v1/usages</c>: 5-hour and weekly limits, as the Kimi CLI shows.</summary>
    public static UsageSnapshot Kimi(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = KimiPlan(Str(Path(root, "user", "membership"), "level")) };
        if (ProviderJson.Arr(root, "limits") is { } limits)
        {
            for (var index = 0; index < limits.Count; index++)
            {
                if (limits[index] is not JsonObject entry) continue;
                var windowInfo = ProviderJson.Obj(entry, "window");
                var detail = ProviderJson.Obj(entry, "detail") ?? entry;
                var seconds = KimiWindowSeconds(windowInfo);
                var used = PercentUsed(Number(detail, "limit"), Number(detail, "used"), Number(detail, "remaining"));
                var window = MakeWindow(used, seconds, EpochSeconds(First(detail, "resetTime", "reset_time", "resetAt")), now);
                output.Add(seconds > 0 ? DurationName(seconds) + " limit" : $"Limit {index + 1}", window);
            }
        }
        if (ProviderJson.Obj(root, "usages") is { } usages)
        {
            KimiRatio(output, ProviderJson.Obj(usages, "limit_5h"), 5 * Hour);
            KimiRatio(output, ProviderJson.Obj(usages, "limit_7d"), Week);
            KimiRatio(output, ProviderJson.Obj(usages, "limit_month_total"), Month);
        }
        // The top-level "usage" block is the weekly quota the Kimi CLI's /usage shows first.
        if (ProviderJson.Obj(root, "usage") is { } usage && output.Weekly is null)
        {
            var used = PercentUsed(Number(usage, "limit"), Number(usage, "used"), Number(usage, "remaining"));
            output.Weekly = MakeWindow(used, Week, EpochSeconds(First(usage, "resetTime", "reset_time", "resetAt")), now);
        }
        return output.Build();
    }

    private static void KimiRatio(Builder output, JsonObject? entry, long seconds)
    {
        if (entry is null) return;
        var ratio = Number(entry, "used_ratio", "usedRatio");
        if (double.IsNaN(ratio)) return;
        output.Add(DurationName(seconds) + " limit",
            MakeWindow(RatioPercent(ratio), seconds, EpochSeconds(First(entry, "reset_time", "resetTime")), output.Now));
    }

    internal static long KimiWindowSeconds(JsonObject? window)
    {
        if (window is null) return 0;
        var duration = Number(window, "duration");
        if (double.IsNaN(duration) || duration <= 0) return 0;
        var unit = Str(window, "timeUnit", "time_unit", "unit").ToUpperInvariant();
        long multiplier = unit.Contains("MINUTE") ? 60
            : unit.Contains("HOUR") ? Hour
            : unit.Contains("DAY") ? Day
            : unit.Contains("WEEK") ? Week
            : unit.Contains("MONTH") ? Month : 1;
        return (long)duration * multiplier;
    }

    public static string KimiPlan(string? level) => (level ?? "").ToUpperInvariant() switch
    {
        "LEVEL_FREE" => "Adagio",
        "LEVEL_TRIAL" => "Andante",
        "LEVEL_BASIC" => "Moderato",
        "LEVEL_INTERMEDIATE" => "Allegretto",
        "LEVEL_ADVANCED" => "Allegro",
        "" => "",
        _ => TitleCase(Regex.Replace(level ?? "", "(?i)^level_", "")),
    };

    // ------------------------------------------------------------------ Factory (Droid)

    /// <summary><c>api.factory.ai/api/billing/limits</c>: standard and core token limits.</summary>
    public static UsageSnapshot Factory(string body, string? plan, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = plan ?? "" };
        if (ProviderJson.Obj(root, "limits") is { } limits)
        {
            var standard = ProviderJson.Obj(limits, "standard");
            FactoryWindow(output, standard, "fiveHour", 5 * Hour, "", now);
            FactoryWindow(output, standard, "weekly", Week, "", now);
            FactoryWindow(output, standard, "monthly", Month, "", now);
            var core = ProviderJson.Obj(limits, "core");
            FactoryWindow(output, core, "fiveHour", 5 * Hour, "Core ", now);
            FactoryWindow(output, core, "weekly", Week, "Core ", now);
            FactoryWindow(output, core, "monthly", Month, "Core ", now);
        }
        var cents = Number(root, "extraUsageBalanceCents");
        if (!double.IsNaN(cents) && cents > 0) output.Credits = Balance(cents / 100);
        if (output.IsEmpty) FactorySubscription(output, root, now);
        return output.Build();
    }

    private static void FactoryWindow(Builder output, JsonObject? group, string key, long seconds, string prefix, DateTimeOffset now)
    {
        if (ProviderJson.Obj(group, key) is not { } entry) return;
        var used = Number(entry, "usedPercent", "used_percent");
        if (double.IsNaN(used))
        {
            var ratio = Number(entry, "usedRatio", "used_ratio");
            if (!double.IsNaN(ratio)) used = RatioPercent(ratio);
        }
        var nowSeconds = now.ToUnixTimeSeconds();
        var reset = EpochSeconds(First(entry, "windowEnd", "window_end", "resetAt"));
        if (reset <= 0)
        {
            var remaining = Number(entry, "secondsRemaining");
            if (!double.IsNaN(remaining)) reset = nowSeconds + (long)remaining;
        }
        // A window that already ended has reset; Factory keeps the old percent until next use.
        if (reset > 0 && reset <= nowSeconds) used = 0;
        var window = MakeWindow(used, seconds, reset, now);
        if (prefix.Length == 0) output.Add(DurationName(seconds) + " limit", window);
        else output.Extra(prefix + DurationName(seconds).ToLowerInvariant() + " limit", window);
    }

    /// <summary>Older <c>app.factory.ai/api/organization/subscription/usage</c> token allowance.</summary>
    private static void FactorySubscription(Builder output, JsonObject root, DateTimeOffset now)
    {
        if (ProviderJson.Obj(root, "usage") is not { } usage) return;
        var seconds = Span(ProviderJson.Get(usage, "startDate"), ProviderJson.Get(usage, "endDate"), Month);
        var reset = EpochSeconds(ProviderJson.Get(usage, "endDate"));
        foreach (var key in new[] { "standard", "premium" })
        {
            if (ProviderJson.Obj(usage, key) is not { } entry) continue;
            var ratio = Number(entry, "usedRatio");
            var percent = !double.IsNaN(ratio) ? RatioPercent(ratio)
                : PercentUsed(Number(entry, "totalAllowance"), Number(entry, "userTokens", "orgTotalTokensUsed"), double.NaN);
            var window = MakeWindow(percent, seconds, reset, now);
            if (key == "standard" && output.Monthly is null && seconds >= 27 * Day) output.Monthly = window;
            else output.Extra(TitleCase(key) + " tokens", window);
        }
    }

    /// <summary>Plan name from <c>app.factory.ai/api/app/auth/me</c>.</summary>
    public static string FactoryPlan(string body)
    {
        var root = ProviderJson.Object(body);
        var subscription = Path(root, "organization", "subscription");
        var name = Str(Path(subscription, "orbSubscription", "plan"), "name");
        if (name.Length == 0) name = Str(subscription, "factoryTier", "tier");
        if (name.Length == 0) name = Str(ProviderJson.Obj(root, "organization"), "factoryTier");
        return TitleCase(Regex.Replace(name, @"(?i)^factory\s*", ""));
    }

    public static string FactoryEmail(string body)
    {
        var root = ProviderJson.Object(body);
        var email = Str(ProviderJson.Obj(root, "userProfile"), "email");
        if (email.Length == 0) email = Str(ProviderJson.Obj(root, "user"), "email");
        return email.Contains('@') ? email : "";
    }

    // ------------------------------------------------------------------ Grok (xAI)

    /// <summary><c>cli-chat-proxy.grok.com/v1/billing?format=credits</c>: credits for the billing period.</summary>
    public static UsageSnapshot Grok(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var config = ProviderJson.Obj(root, "config") ?? root;
        var output = new Builder(now) { Plan = GrokPlan(Str(config, "subscriptionTier")) };
        if (output.Plan.Length == 0) output.Plan = GrokPlan(Str(root, "subscriptionTier"));
        var period = ProviderJson.Obj(config, "currentPeriod");
        var start = period is not null ? First(period, "start", "startTime") : First(config, "billingPeriodStart");
        var end = period is not null ? First(period, "end", "endTime") : First(config, "billingPeriodEnd");
        var seconds = Span(start, end, Month);
        var reset = EpochSeconds(end);
        var percent = Number(config, "creditUsagePercent", "usagePercent");
        if (!double.IsNaN(percent) && percent <= 1 && percent > 0 && !ProviderJson.Has(config, "creditUsagePercent")) percent *= 100;
        output.Add("Credits", MakeWindow(percent, seconds, reset, now));
        if (ProviderJson.Arr(config, "productUsage") is { } products)
        {
            for (var index = 0; index < products.Count; index++)
            {
                if (products[index] is not JsonObject product) continue;
                var name = TitleCase(Str(product, "product", "name"));
                output.Extra(name.Length == 0 ? $"Product {index + 1}" : name,
                    MakeWindow(Number(product, "usagePercent", "creditUsagePercent"), seconds, reset, now));
            }
        }
        var prepaid = Number(config, "prepaidBalance");
        if (!double.IsNaN(prepaid) && prepaid > 0) output.Credits = Balance(prepaid / 100);
        return output.Build();
    }

    internal static string GrokPlan(string? tier)
    {
        var value = (tier ?? "").ToUpperInvariant();
        if (value.Length == 0) return "";
        if (value.Contains("HEAVY")) return "SuperGrok Heavy";
        if (value.Contains("SUPER")) return "SuperGrok";
        if (value.Contains("PREMIUM_PLUS") || value.Contains("PREMIUMPLUS")) return "Premium+";
        if (value.Contains("PREMIUM")) return "Premium";
        if (value.Contains("FREE")) return "Free";
        return TitleCase(Regex.Replace(value, "^SUBSCRIPTION_TIER_", ""));
    }

    // ------------------------------------------------------------------ Antigravity (Google)

    /// <summary>
    /// Antigravity usage from any of Cloud Code's three quota replies:
    /// <c>retrieveUserQuotaSummary</c> (<c>groups[].buckets[]</c> with <c>remaining</c>),
    /// <c>fetchAvailableModels</c> (<c>models.&lt;id&gt;.quotaInfo</c>) or <c>retrieveUserQuota</c>
    /// (<c>buckets[]</c> per model). Gemini is the main pair of windows; Claude and GPT become one
    /// additional limit. A bucket with no fraction, or a disabled one, is unknown. Mirrors Android.
    /// </summary>
    public static UsageSnapshot Antigravity(string body, string? plan, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var wrapped = ProviderJson.Obj(root, "response") ?? ProviderJson.Obj(root, "summary");
        if (wrapped is not null && ProviderJson.Has(wrapped, "groups")) root = wrapped;
        var output = new Builder(now) { Plan = plan ?? "" };
        UsageWindow? thirdShort = null, thirdWeekly = null;
        if (ProviderJson.Arr(root, "groups") is { } groups)
        {
            foreach (var group in groups.OfType<JsonObject>())
            {
                var groupName = Str(group, "displayName", "name");
                var family = AntigravityFamily(groupName);
                foreach (var bucket in (ProviderJson.Arr(group, "buckets") ?? []).OfType<JsonObject>())
                {
                    if (ProviderJson.Bool(bucket, "disabled", false)) continue;
                    var id = Str(bucket, "bucketId", "id", "name");
                    var fraction = AntigravityFraction(bucket);
                    if (double.IsNaN(fraction)) continue;
                    var weekly = AntigravityWeekly(Str(bucket, "window") + " " + id + " " + Str(bucket, "displayName"));
                    var window = MakeWindow((1 - fraction) * 100, weekly ? Week : 5 * Hour, EpochSeconds(First(bucket, "resetTime", "reset_time")), now);
                    var bucketFamily = family.Length == 0 ? AntigravityFamily(id) : family;
                    if (bucketFamily == "gemini")
                    {
                        if (weekly && output.Weekly is null) output.Weekly = window;
                        else if (!weekly && output.ShortWindow is null) output.ShortWindow = window;
                    }
                    else if (bucketFamily == "third")
                    {
                        if (weekly) thirdWeekly ??= window; else thirdShort ??= window;
                    }
                    else
                    {
                        var label = groupName.Length == 0 ? TitleCase(id) : groupName;
                        output.Extra(label + (weekly ? " weekly" : " 5-hour"), window);
                    }
                }
            }
        }
        else
        {
            // Per-model quotas: each family shows its most used model.
            var rows = new List<(string Name, double Fraction, JsonNode? Reset)>();
            if (ProviderJson.Obj(root, "models") is { } models)
            {
                foreach (var (id, node) in models)
                {
                    if (node is not JsonObject model || ProviderJson.Obj(model, "quotaInfo") is not { } quota) continue;
                    rows.Add((id + " " + Str(model, "displayName", "label"), Number(quota, "remainingFraction"), First(quota, "resetTime")));
                }
            }
            foreach (var bucket in (ProviderJson.Arr(root, "buckets") ?? []).OfType<JsonObject>())
                rows.Add((Str(bucket, "modelId", "bucketId"), Number(bucket, "remainingFraction"), First(bucket, "resetTime")));
            (double Fraction, JsonNode? Reset)? gemini = null, third = null;
            foreach (var row in rows)
            {
                if (double.IsNaN(row.Fraction)) continue;
                var family = AntigravityFamily(row.Name);
                if (family == "gemini" && (gemini is null || row.Fraction < gemini.Value.Fraction)) gemini = (row.Fraction, row.Reset);
                else if (family == "third" && (third is null || row.Fraction < third.Value.Fraction)) third = (row.Fraction, row.Reset);
            }
            if (gemini is { } g) output.ShortWindow = MakeWindow((1 - g.Fraction) * 100, 5 * Hour, EpochSeconds(g.Reset), now);
            if (third is { } t) thirdShort = MakeWindow((1 - t.Fraction) * 100, 5 * Hour, EpochSeconds(t.Reset), now);
        }
        if ((thirdShort ?? thirdWeekly) is { } primary)
        {
            var secondary = thirdShort is not null ? thirdWeekly : null;
            var reached = primary.UsedPercent >= 100 || (secondary?.UsedPercent ?? 0) >= 100;
            output.Extras.Insert(0, new UsageLimit("third_party", "Claude and GPT models", "", !reached, reached, primary, secondary));
        }
        return output.Build();
    }

    /// <summary>"gemini", "third" (Claude, GPT and other partner models) or "" for anything else.</summary>
    private static string AntigravityFamily(string name)
    {
        var value = name.ToLowerInvariant();
        if (value.Contains("claude") || value.Contains("gpt") || value.StartsWith("3p", StringComparison.Ordinal) || value.Contains("third")) return "third";
        // A versioned family such as "Gemini 4 Argon" is a separate quota with its own name.
        return value.Contains("gemini") && !value.Contains("argon") ? "gemini" : "";
    }

    private static bool AntigravityWeekly(string text)
    {
        var value = text.ToLowerInvariant();
        return value.Contains("week") || value.Contains("7d") || value.Contains("7-day");
    }

    /// <summary><c>remaining</c> is <c>{remainingFraction}</c> or a oneof <c>{case, value}</c>.</summary>
    private static double AntigravityFraction(JsonObject bucket)
    {
        var fraction = Number(bucket, "remainingFraction", "remaining_fraction");
        if (!double.IsNaN(fraction)) return fraction;
        if (ProviderJson.Obj(bucket, "remaining") is not { } remaining) return double.NaN;
        fraction = Number(remaining, "remainingFraction", "remaining_fraction");
        if (!double.IsNaN(fraction)) return fraction;
        return Str(remaining, "case") == "remainingFraction" ? Number(remaining, "value") : double.NaN;
    }

    /// <summary>True when the account's OAuth client can't read consumer quota (sign in again).</summary>
    public static bool AntigravityClientRejected(string body)
    {
        var root = ProviderJson.Object(body);
        if (ProviderJson.Has(root, "currentTier") || AntigravityProject(body).Length > 0) return false;
        return (ProviderJson.Arr(root, "ineligibleTiers") ?? []).OfType<JsonObject>()
            .Any(tier => Str(tier, "reasonCode") == "GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT");
    }

    /// <summary>Google Cloud project the account's Code Assist quota is billed to, when given.</summary>
    public static string AntigravityProject(string body)
    {
        var root = ProviderJson.Object(body);
        // onboardUser wraps its answer in "response".
        if (!ProviderJson.Has(root, "cloudaicompanionProject") && ProviderJson.Obj(root, "response") is { } inner) root = inner;
        var project = ProviderJson.Get(root, "cloudaicompanionProject");
        if (ProviderJson.IsNull(project)) return "";
        return project is JsonObject o ? Str(o, "id", "projectId", "name") : ProviderJson.Text(project).Trim();
    }

    /// <summary>The tier to onboard an account that has no Code Assist project yet.</summary>
    public static string AntigravityOnboardTier(string body)
    {
        var root = ProviderJson.Object(body);
        var allowed = (ProviderJson.Arr(root, "allowedTiers") ?? []).OfType<JsonObject>().ToList();
        var pick = allowed.FirstOrDefault(t => ProviderJson.Bool(t, "isDefault", false) && Str(t, "id").Length > 0)
            ?? allowed.FirstOrDefault(t => Str(t, "id").Length > 0);
        if (pick is not null) return Str(pick, "id");
        var paid = Str(ProviderJson.Obj(root, "paidTier"), "id");
        return paid.Length > 0 ? paid : Str(ProviderJson.Obj(root, "currentTier"), "id");
    }

    /// <summary>Plan from <c>v1internal:loadCodeAssist</c>.</summary>
    public static string AntigravityPlan(string body)
    {
        var root = ProviderJson.Object(body);
        var paid = ProviderJson.Obj(root, "paidTier");
        var id = Str(paid, "id").ToLowerInvariant();
        if (id.Contains("ultra-lite") || id.Contains("ultra_lite")) return "Ultra Lite";
        if (id.Contains("ultra")) return "Ultra";
        if (id.Contains("pro")) return "Pro";
        var name = Str(paid, "name");
        if (name.Length == 0) name = Str(ProviderJson.Obj(root, "currentTier"), "name");
        return Regex.Replace(name, @"(?i)^google\s+(ai\s+)?", "");
    }

    // ------------------------------------------------------------------ Devin and Windsurf

    /// <summary>Codeium <c>GetUserStatus</c>: daily and weekly quota for Devin and Windsurf plans.</summary>
    public static UsageSnapshot Codeium(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var status = ProviderJson.Obj(root, "userStatus") ?? root;
        var plan = ProviderJson.Obj(status, "planStatus") ?? status;
        var output = new Builder(now) { Plan = Str(ProviderJson.Obj(plan, "planInfo"), "planName") };
        if (output.Plan.Length == 0) output.Plan = Str(status, "teamsTier", "planName");
        var hideDaily = ProviderJson.Bool(ProviderJson.Obj(plan, "planInfo"), "hideDailyQuota", false);
        if (!hideDaily) output.Add("Daily quota", CodeiumWindow(plan, "dailyQuotaRemainingPercent", "dailyQuotaResetAtUnix", Day, now));
        output.Weekly = CodeiumWindow(plan, "weeklyQuotaRemainingPercent", "weeklyQuotaResetAtUnix", Week, now);
        var micros = Number(plan, "overageBalanceMicros");
        if (!double.IsNaN(micros) && micros > 0) output.Credits = Balance(micros / 1_000_000);
        // Older plans meter prompt credits per month instead.
        if (output.Weekly is null && output.Extras.Count == 0)
        {
            var available = Number(plan, "availablePromptCredits");
            var used = Number(plan, "usedPromptCredits");
            if (!double.IsNaN(available) && available > 0)
                output.Monthly = MakeWindow(PercentUsed(available, used, double.NaN), Month, EpochSeconds(First(plan, "planEnd")), now);
        }
        return output.Build();
    }

    private static UsageWindow? CodeiumWindow(JsonObject plan, string percentKey, string resetKey, long seconds, DateTimeOffset now)
    {
        var remaining = Number(plan, percentKey);
        var reset = EpochSeconds(ProviderJson.Get(plan, resetKey));
        // proto3 omits zero values: a reset time with no percent means nothing is left.
        if (double.IsNaN(remaining) && reset > 0) remaining = 0;
        return double.IsNaN(remaining) ? null : MakeWindow(100 - remaining, seconds, reset, now);
    }

    // ------------------------------------------------------------------ Augment

    /// <summary><c>&lt;tenant&gt;/get-billing-summary</c>: credits included per billing cycle.</summary>
    public static UsageSnapshot Augment(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = Str(root, "plan_name", "planName") };
        var included = Number(root, "amount_included_per_cycle", "credits_included");
        var remaining = Number(root, "amount_remaining", "credits_remaining");
        var reset = EpochSeconds(First(root, "billing_cycle_end_date_iso", "billing_cycle_end_date", "cycle_end"));
        var window = MakeWindow(PercentUsed(included, double.NaN, remaining), Month, reset, now);
        output.Monthly = window;
        if (window is null && !double.IsNaN(remaining))
        {
            var unit = Str(root, "usage_unit");
            output.Credits = new UsageCredits(true, false, WholeCount(remaining) + (unit.Length == 0 ? " credits" : " " + unit.ToLowerInvariant()));
        }
        return output.Build();
    }

    // ------------------------------------------------------------------ Cline

    /// <summary><c>api.cline.bot/api/v1/users/me/plan/usage-limits</c>.</summary>
    public static UsageSnapshot Cline(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var data = ProviderJson.Obj(root, "data") ?? root;
        var output = new Builder(now) { Plan = TitleCase(Str(data, "planName", "plan")) };
        if (ProviderJson.Arr(data, "limits") is { } limits)
        {
            foreach (var element in limits)
            {
                if (element is not JsonObject entry) continue;
                var type = Str(entry, "type", "window").ToLowerInvariant();
                long seconds = type.Contains("five") || type.Contains("5h") ? 5 * Hour
                    : type.Contains("week") ? Week
                    : type.Contains("month") ? Month
                    : type.Contains("day") ? Day : 0;
                output.Add(seconds > 0 ? DurationName(seconds) + " limit" : TitleCase(type),
                    MakeWindow(Number(entry, "percentUsed", "usedPercent"), seconds, EpochSeconds(First(entry, "resetsAt", "resetAt")), now));
            }
        }
        return output.Build();
    }

    // ------------------------------------------------------------------ OpenCode Go

    internal static readonly string[] PercentKeys = ["usagePercent", "usedPercent", "percentUsed", "percent", "usage_percent",
        "used_percent", "percent_used", "utilization"];
    internal static readonly string[] ResetInKeys = ["resetInSec", "resetInSeconds", "reset_in_sec", "reset_in_seconds",
        "resetsInSeconds", "secondsUntilReset", "secondsRemaining"];
    internal static readonly string[] ResetAtKeys = ["resetAt", "resetsAt", "reset_at", "resets_at", "nextReset", "next_reset",
        "renewAt", "renew_at", "resetTime", "reset_time", "windowEnd", "endTime", "end_time", "nextResetTime"];

    /// <summary><c>opencode.ai/zen/go/v1/usage</c>: rolling, weekly and monthly percentages.</summary>
    public static UsageSnapshot OpencodeGo(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var usage = ProviderJson.Obj(root, "usage") ?? root;
        var output = new Builder(now) { Plan = TitleCase(Str(root, "plan", "tier")) };
        string[][] windows = [["rolling", "5h", "fiveHour"], ["weekly", "week", "7d"], ["monthly", "month", "30d"]];
        long[] lengths = [5 * Hour, Week, Month];
        for (var index = 0; index < windows.Length; index++)
        {
            var entry = windows[index].Select(k => ProviderJson.Obj(usage, k)).FirstOrDefault(o => o is not null);
            if (entry is null) continue;
            var percent = Number(entry, PercentKeys);
            if (!double.IsNaN(percent) && percent <= 1 && ProviderJson.Has(entry, "utilization")) percent *= 100;
            output.Add(DurationName(lengths[index]) + " limit", MakeWindow(percent, lengths[index], ResetFrom(entry, now), now));
        }
        return output.Build();
    }

    internal static long ResetFrom(JsonObject entry, DateTimeOffset now)
    {
        var at = EpochSeconds(First(entry, ResetAtKeys));
        if (at > 0) return at;
        var within = Number(entry, ResetInKeys);
        return double.IsNaN(within) || within <= 0 ? 0 : now.ToUnixTimeSeconds() + (long)within;
    }

    // ------------------------------------------------------------------ MiniMax

    /// <summary>
    /// <c>api.minimax.io/v1/token_plan/remains</c>: prompts left in the current interval for each
    /// coding-plan model. MiniMax names the remaining count <c>usage_count</c>.
    /// </summary>
    public static UsageSnapshot MiniMax(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now);
        if (ProviderJson.Obj(root, "base_resp") is { } baseResp && ProviderJson.Int(baseResp, "status_code", 0) != 0) return output.Build();
        output.Plan = Str(root, "plan_name", "current_plan");
        if (ProviderJson.Arr(root, "model_remains") is not { } models) return output.Build();
        for (var index = 0; index < models.Count; index++)
        {
            if (models[index] is not JsonObject model) continue;
            var name = Str(model, "model_name");
            var percentLeft = Number(model, "current_interval_remaining_percent");
            var used = !double.IsNaN(percentLeft) ? 100 - percentLeft
                : PercentUsed(Number(model, "current_interval_total_count"), double.NaN, Number(model, "current_interval_usage_count"));
            var seconds = Span(ProviderJson.Get(model, "start_time"), ProviderJson.Get(model, "end_time"), 5 * Hour);
            var reset = EpochSeconds(ProviderJson.Get(model, "end_time"));
            if (reset <= 0)
            {
                var remainsMillis = Number(model, "remains_time");
                if (!double.IsNaN(remainsMillis)) reset = (long)((now.ToUnixTimeMilliseconds() + remainsMillis) / 1_000);
            }
            var interval = MakeWindow(used, seconds, reset, now);
            var weekly = MakeWindow(PercentUsed(Number(model, "current_weekly_total_count"), double.NaN, Number(model, "current_weekly_usage_count")),
                Week, EpochSeconds(ProviderJson.Get(model, "weekly_end_time")), now);
            if (index == 0)
            {
                output.Add("Prompts", interval);
                if (weekly is not null) output.Weekly = weekly;
            }
            else output.Extra(name.Length == 0 ? $"Model {index + 1}" : name, interval);
        }
        return output.Build();
    }

    // ------------------------------------------------------------------ Z.ai

    /// <summary><c>api.z.ai/api/monitor/usage/quota/limit</c>: token windows plus the web-tool quota.</summary>
    public static UsageSnapshot Zai(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var data = ProviderJson.Obj(root, "data") ?? root;
        var output = new Builder(now) { Plan = TitleCase(Str(data, "planName", "plan", "level")) };
        if (ProviderJson.Arr(data, "limits") is not { } limits) return output.Build();
        foreach (var element in limits)
        {
            if (element is not JsonObject entry) continue;
            var type = Str(entry, "type").ToUpperInvariant();
            var seconds = ZaiWindowSeconds(ProviderJson.Int(entry, "unit", 0), ProviderJson.Int(entry, "number", 0));
            var percent = Number(entry, "percentage");
            if (double.IsNaN(percent)) percent = PercentUsed(Number(entry, "usage", "total"), Number(entry, "currentValue"), Number(entry, "remaining"));
            var window = MakeWindow(percent, seconds, EpochSeconds(ProviderJson.Get(entry, "nextResetTime")), now);
            if (type.Contains("TIME")) output.Extra("Web search and reader", window);
            else output.Add(seconds > 0 ? DurationName(seconds) + " limit" : TitleCase(type), window);
        }
        return output.Build();
    }

    /// <summary>Z.ai's unit codes: 1 day, 3 hour, 5 minute, 6 week.</summary>
    internal static long ZaiWindowSeconds(long unit, long number)
    {
        long baseSeconds = unit switch { 1 => Day, 3 => Hour, 5 => 60, 6 => Week, _ => 0 };
        return baseSeconds == 0 ? 0 : baseSeconds * Math.Max(1, number);
    }

    // ------------------------------------------------------------------ Kiro

    /// <summary>CodeWhisperer <c>GetUsageLimits</c>: monthly credits, plus any other metered resource.</summary>
    public static UsageSnapshot Kiro(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now)
        {
            Plan = Regex.Replace(Str(ProviderJson.Obj(root, "subscriptionInfo"), "subscriptionTitle", "type"), @"(?i)^kiro\s+", ""),
        };
        var list = ProviderJson.Arr(root, "usageBreakdownList") ?? ProviderJson.Arr(root, "limits");
        var fallbackReset = EpochSeconds(First(root, "nextDateReset", "resetDate"));
        // Kiro's credits (resourceType CREDIT) are the plan's main meter; list them first.
        var entries = (list ?? []).OfType<JsonObject>().ToList();
        var credit = entries.FindIndex(e => Str(e, "resourceType").Equals("CREDIT", StringComparison.OrdinalIgnoreCase));
        if (credit > 0)
        {
            var first = entries[credit];
            entries.RemoveAt(credit);
            entries.Insert(0, first);
        }
        for (var index = 0; index < entries.Count; index++)
        {
            var entry = entries[index];
            var limit = Number(entry, "usageLimitWithPrecision", "usageLimit", "limit");
            var used = Number(entry, "currentUsageWithPrecision", "currentUsage", "used");
            var reset = EpochSeconds(First(entry, "nextDateReset", "resetDate"));
            var window = MakeWindow(PercentUsed(limit, used, double.NaN), Month, reset > 0 ? reset : fallbackReset, now);
            var name = TitleCase(Str(entry, "displayName", "resourceType"));
            if (output.Monthly is null) output.Monthly = window;
            else output.Extra(name.Length == 0 ? $"Limit {index + 1}" : name, window);
            if (ProviderJson.Obj(entry, "freeTrialInfo") is { } trial)
            {
                output.Extra("Free trial", MakeWindow(
                    PercentUsed(Number(trial, "usageLimitWithPrecision", "usageLimit"), Number(trial, "currentUsageWithPrecision", "currentUsage"), double.NaN),
                    Month, EpochSeconds(ProviderJson.Get(trial, "freeTrialExpiry")), now));
            }
        }
        return output.Build();
    }

    // ------------------------------------------------------------------ OpenRouter

    /// <summary><c>/api/v1/key</c> (spend limit) plus <c>/api/v1/credits</c> (balance).</summary>
    public static UsageSnapshot OpenRouter(string keyBody, string creditsBody, DateTimeOffset now)
    {
        var key = ProviderJson.Object(keyBody);
        var data = ProviderJson.Obj(key, "data") ?? key;
        var output = new Builder(now) { Plan = ProviderJson.Bool(data, "is_free_tier", false) ? "Free" : "" };
        var limit = Number(data, "limit");
        if (!double.IsNaN(limit) && limit > 0)
        {
            var period = Str(data, "limit_reset").ToLowerInvariant();
            long seconds = period.Contains("day") ? Day : period.Contains("week") ? Week : period.Contains("month") ? Month : 0;
            var used = seconds == Day ? Number(data, "usage_daily", "usage")
                : seconds == Week ? Number(data, "usage_weekly", "usage")
                : seconds == Month ? Number(data, "usage_monthly", "usage")
                : Number(data, "usage");
            var remaining = Number(data, "limit_remaining");
            output.Add("Key limit", MakeWindow(PercentUsed(limit, used, remaining), seconds, NextPeriodStart(seconds, now), now));
        }
        if (ProviderJson.Obj(ProviderJson.Object(creditsBody), "data") is { } credits)
        {
            var total = Number(credits, "total_credits");
            var used = Number(credits, "total_usage");
            if (!double.IsNaN(total)) output.Credits = Balance(total - (double.IsNaN(used) ? 0 : used));
        }
        return output.Build();
    }

    /// <summary>OpenRouter limits reset at midnight UTC (daily), Monday (weekly) or the 1st (monthly).</summary>
    internal static long NextPeriodStart(long seconds, DateTimeOffset now)
    {
        if (seconds <= 0) return 0;
        var today = new DateTimeOffset(now.UtcDateTime.Date, TimeSpan.Zero);
        DateTimeOffset next;
        if (seconds == Day) next = today.AddDays(1);
        else if (seconds == Week)
        {
            var iso = today.DayOfWeek == DayOfWeek.Sunday ? 7 : (int)today.DayOfWeek;
            next = today.AddDays(8 - iso);
        }
        else next = new DateTimeOffset(today.Year, today.Month, 1, 0, 0, 0, TimeSpan.Zero).AddMonths(1);
        return next.ToUnixTimeSeconds();
    }

    // ------------------------------------------------------------------ Amp

    private const string AmountPattern = @"([0-9][0-9,]*(?:\.[0-9]+)?)";
    private static readonly Regex AmpFree = new(@"Amp Free:\s*\$\s*" + AmountPattern + @"\s*/\s*\$\s*" + AmountPattern + @"\s*remaining",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AmpFreePercent = new(@"Amp Free:\s*" + AmountPattern + @"\s*%\s*remaining",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AmpTier = new(@"Amp\s+(.+?)\s+(?:Tier|Subscription):\s*agent usage\s*\$\s*" + AmountPattern
        + @"\s*of\s*\$\s*" + AmountPattern + @"\s*remaining", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AmpOrb = new(@"orb usage\s*" + AmountPattern + @"\s*h\s*of\s*" + AmountPattern + @"\s*h",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AmpPeriod = new(@"period\s+(\d{4}-\d{2}-\d{2})\s+to\s+(\d{4}-\d{2}-\d{2})",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AmpCredits = new(@"(?:Individual credits:\s*\$\s*" + AmountPattern + @"\s*remaining|\$\s*" + AmountPattern
        + @"\s*(?:in\s+)?(?:individual\s+)?credits)", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// <c>ampcode.com/api/internal?userDisplayBalanceInfo</c>: Amp returns the text the CLI's
    /// <c>amp usage</c> prints. Amp Free is a daily allowance that resets at 8 PM New York time
    /// (older replies give dollars, newer ones a percentage); paid tiers report agent dollars and
    /// orb hours for the billing period; individual credits are a dollar balance.
    /// </summary>
    public static UsageSnapshot Amp(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var text = ProviderJson.Obj(root, "result") is { } result ? Str(result, "displayText", "text") : "";
        if (text.Length == 0) text = Str(root, "displayText", "result");
        if (text.Length == 0) text = body;
        var output = new Builder(now);
        if (AmpFree.Match(text) is { Success: true } free)
        {
            output.Plan = "Free";
            output.Add("Free usage", MakeWindow(PercentUsed(Amount(free.Groups[2].Value), double.NaN, Amount(free.Groups[1].Value)),
                Day, AmpFreeReset(now), now));
        }
        else if (AmpFreePercent.Match(text) is { Success: true } percent)
        {
            output.Plan = "Free";
            output.Add("Free usage", MakeWindow(100 - Amount(percent.Groups[1].Value), Day, AmpFreeReset(now), now));
        }
        if (AmpTier.Match(text) is { Success: true } tier)
        {
            output.Plan = TitleCase(tier.Groups[1].Value);
            var seconds = Month;
            long reset = 0;
            if (AmpPeriod.Match(text) is { Success: true } period)
            {
                seconds = Span(JsonValue.Create(period.Groups[1].Value), JsonValue.Create(period.Groups[2].Value), Month);
                reset = EpochSeconds(period.Groups[2].Value);
            }
            var agent = MakeWindow(PercentUsed(Amount(tier.Groups[3].Value), double.NaN, Amount(tier.Groups[2].Value)), seconds, reset, now);
            if (seconds >= 27 * Day && seconds <= 32 * Day) output.Monthly = agent;
            else output.Extra("Agent usage", agent);
            if (AmpOrb.Match(text) is { Success: true } orb)
            {
                output.Extra("Orb hours", MakeWindow(PercentUsed(Amount(orb.Groups[2].Value), double.NaN, Amount(orb.Groups[1].Value)),
                    seconds, reset, now));
            }
        }
        if (AmpCredits.Match(text) is { Success: true } credits)
        {
            var group = credits.Groups[1].Success ? credits.Groups[1] : credits.Groups[2];
            output.Credits = Balance(Amount(group.Value));
        }
        return output.Build();
    }

    /// <summary>Amp Free refills at 8 PM in New York.</summary>
    internal static long AmpFreeReset(DateTimeOffset now)
    {
        TimeZoneInfo zone;
        try
        {
            zone = TimeZoneInfo.FindSystemTimeZoneById("America/New_York");
        }
        catch (Exception e) when (e is TimeZoneNotFoundException or InvalidTimeZoneException)
        {
            return 0;
        }
        var local = TimeZoneInfo.ConvertTime(now, zone);
        var evening = new DateTime(local.Year, local.Month, local.Day, 20, 0, 0, DateTimeKind.Unspecified);
        var reset = new DateTimeOffset(evening, zone.GetUtcOffset(evening));
        if (reset <= now)
        {
            var next = evening.AddDays(1);
            reset = new DateTimeOffset(next, zone.GetUtcOffset(next));
        }
        return reset.ToUnixTimeSeconds();
    }

    private static double Amount(string text) =>
        double.TryParse(text.Replace(",", ""), NumberStyles.Float, CultureInfo.InvariantCulture, out var value) ? value : double.NaN;

    // ------------------------------------------------------------------ Balance-only providers

    /// <summary>Kilo <c>/api/profile/balance</c>: a dollar balance.</summary>
    public static UsageSnapshot Kilo(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var data = ProviderJson.Obj(root, "data") ?? root;
        var output = new Builder(now);
        var dollars = Number(data, "balance", "balanceUsd", "credits");
        if (double.IsNaN(dollars))
        {
            var micros = Number(data, "balance_mUsd", "microdollars");
            if (!double.IsNaN(micros)) dollars = micros / 1_000_000;
        }
        if (!double.IsNaN(dollars)) output.Credits = Balance(dollars);
        return output.Build();
    }

    /// <summary>Codebuff <c>/api/v1/usage</c>: credits used against the cycle quota.</summary>
    public static UsageSnapshot Codebuff(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = TitleCase(Str(root, "subscriptionTier", "plan", "tier")) };
        var quota = Number(root, "quota", "limit");
        var used = Number(root, "usage", "used");
        var reset = EpochSeconds(First(root, "next_quota_reset", "nextQuotaReset", "resetAt"));
        output.Monthly = MakeWindow(PercentUsed(quota, used, double.NaN), Month, reset, now);
        var remaining = Number(root, "remainingBalance", "balance");
        if (!double.IsNaN(remaining) && output.Monthly is null)
            output.Credits = new UsageCredits(true, false, WholeCount(remaining) + " credits");
        return output.Build();
    }

    // ------------------------------------------------------------------ Synthetic

    /// <summary>
    /// <c>api.synthetic.new/v2/quotas</c> (documented at dev.synthetic.new/docs/synthetic/quotas).
    /// Current plans meter weekly credits in <c>planWeeklyLimit</c>, whose <c>percentRemaining</c>
    /// is in percentage points and regenerates continuously, so <c>nextRegenAt</c> is when the
    /// next slice comes back. Older plans report <c>rollingFiveHourLimit</c> and
    /// <c>weeklyTokenLimit</c> as fractions, and the retired <c>subscription</c> request counter is
    /// read only when nothing else is there. The search tool has its own hourly allowance.
    /// </summary>
    public static UsageSnapshot Synthetic(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var data = ProviderJson.Obj(root, "data");
        var source = data is not null && !ProviderJson.Has(root, "planWeeklyLimit") && !ProviderJson.Has(root, "rollingFiveHourLimit")
                     && !ProviderJson.Has(root, "weeklyTokenLimit") ? data : root;
        var output = new Builder(now) { Plan = TitleCase(Str(source, "plan", "planName", "plan_name", "tier")) };
        if (output.Plan.Length == 0) output.Plan = TitleCase(Str(root, "plan", "planName", "tier"));
        if (ProviderJson.Obj(source, "planWeeklyLimit") is { } planWeekly)
        {
            output.Weekly = MakeWindow(QuotaUsed(planWeekly, true), Week, QuotaReset(planWeekly, now), now);
        }
        else
        {
            if (ProviderJson.Obj(source, "rollingFiveHourLimit") is { } rolling)
                output.ShortWindow = MakeWindow(QuotaUsed(rolling, false), 5 * Hour, QuotaReset(rolling, now), now);
            if (ProviderJson.Obj(source, "weeklyTokenLimit") is { } weekly)
                output.Weekly = MakeWindow(QuotaUsed(weekly, false), Week, QuotaReset(weekly, now), now);
        }
        if (Path(source, "search", "hourly") is { } search)
            output.Extra("Search (hourly)", MakeWindow(QuotaUsed(search, false), Hour, QuotaReset(search, now), now));
        if (output.ShortWindow is null && output.Weekly is null && ProviderJson.Obj(source, "subscription") is { } subscription)
            output.ShortWindow = MakeWindow(QuotaUsed(subscription, false), 5 * Hour, QuotaReset(subscription, now), now);
        return output.Build();
    }

    private static readonly string[] QuotaLimitKeys = ["limit", "max", "total", "quota", "cap", "requestLimit", "messageLimit", "maxRequests"];
    private static readonly string[] QuotaUsedKeys = ["used", "usage", "requests", "requestCount", "consumed", "spent", "messagesUsed"];
    private static readonly string[] QuotaLeftKeys = ["remaining", "left", "available"];
    private static readonly string[] QuotaLeftPercentKeys = ["percentRemaining", "remainingPercent", "remaining_percent", "percent_remaining"];
    private static readonly string[] QuotaResetKeys = ["nextRegenAt", "next_regen_at", "renewsAt", "renews_at", "periodEnd", "period_end"];

    internal static long QuotaReset(JsonObject quota, DateTimeOffset now)
    {
        var at = EpochSeconds(First(quota, QuotaResetKeys));
        return at > 0 ? at : ResetFrom(quota, now);
    }

    /// <summary>
    /// Percent used of one quota object. Percent fields win over counts; <paramref name="points"/>
    /// says the percentages are already 0–100 (otherwise values up to 1 are read as fractions).
    /// </summary>
    internal static double QuotaUsed(JsonObject quota, bool points)
    {
        var used = Number(quota, "percentUsed", "usedPercent", "usagePercent", "used_percent");
        var left = Number(quota, QuotaLeftPercentKeys);
        if (double.IsNaN(used) && !double.IsNaN(left)) used = 100 - (!points && left <= 1 ? left * 100 : left);
        else if (!double.IsNaN(used) && !points && used <= 1) used *= 100;
        if (!double.IsNaN(used)) return used;
        var limit = Number(quota, QuotaLimitKeys);
        var count = Number(quota, QuotaUsedKeys);
        var remaining = Number(quota, QuotaLeftKeys);
        if (double.IsNaN(limit) && !double.IsNaN(count) && !double.IsNaN(remaining)) limit = count + remaining;
        return PercentUsed(limit, count, remaining);
    }

    // ------------------------------------------------------------------ Chutes

    /// <summary>
    /// <c>api.chutes.ai/users/me/subscription_usage</c>, from Chutes' own API source
    /// (<c>api/user/router.py</c>): a rolling 4-hour cap and a monthly cap, both in dollars of
    /// pay-as-you-go value, or <c>{"subscription": false}</c> without a plan. Custom plans only
    /// enforce the 4-hour cap and report <c>monthly.uncapped</c>.
    /// </summary>
    public static UsageSnapshot Chutes(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now);
        if (!ProviderJson.Bool(root, "subscription", false)) return output.Build();
        var price = Number(root, "monthly_price");
        if (ProviderJson.Bool(root, "custom", false)) output.Plan = "Custom";
        else if (!double.IsNaN(price) && price > 0) output.Plan = Money(price).Replace(".00", "") + "/month";
        if (ProviderJson.Obj(root, "four_hour") is { } fourHour)
        {
            output.ShortWindow = MakeWindow(PercentUsed(Number(fourHour, "cap"), Number(fourHour, "usage"), Number(fourHour, "remaining")),
                4 * Hour, EpochSeconds(ProviderJson.Get(fourHour, "reset_at")), now);
        }
        if (ProviderJson.Obj(root, "monthly") is { } monthly && !ProviderJson.Bool(monthly, "uncapped", false))
        {
            output.Monthly = MakeWindow(PercentUsed(Number(monthly, "cap"), Number(monthly, "usage"), Number(monthly, "remaining")),
                Month, EpochSeconds(ProviderJson.Get(monthly, "reset_at")), now);
        }
        return output.Build();
    }

    // ------------------------------------------------------------------ v0

    /// <summary>
    /// The v0 Platform API (<c>v0.app/docs/api/v1</c>): <c>/v1/user/billing</c> gives the credit
    /// balance for the billing cycle (token billing) or a legacy allowance, <c>/v1/user/plan</c>
    /// names the plan, and <c>/v1/rate-limits</c> gives the request limit plus, for free accounts,
    /// the daily message limit.
    /// </summary>
    public static UsageSnapshot V0(string billingBody, string planBody, string rateBody, DateTimeOffset now)
    {
        var billing = ProviderJson.Object(billingBody);
        var plan = ProviderJson.Object(planBody);
        var rate = ProviderJson.Object(rateBody);
        var output = new Builder(now) { Plan = TitleCase(Str(plan, "plan")) };
        UsageWindow? CycleWindow(JsonObject? balance, JsonObject? cycle) =>
            MakeWindow(PercentUsed(Number(balance, "total"), double.NaN, Number(balance, "remaining")),
                cycle is null ? Month : Span(ProviderJson.Get(cycle, "start"), ProviderJson.Get(cycle, "end"), Month),
                EpochSeconds(ProviderJson.Get(cycle, "end")), now);
        UsageWindow? credits = null;
        if (ProviderJson.Obj(billing, "data") is { } data)
        {
            if (ProviderJson.Obj(data, "balance") is { } balance)
            {
                credits = CycleWindow(balance, ProviderJson.Obj(data, "billingCycle"));
                var onDemand = Number(ProviderJson.Obj(data, "onDemand"), "balance");
                if (!double.IsNaN(onDemand) && onDemand > 0) output.Credits = Balance(onDemand);
            }
            else
            {
                credits = MakeWindow(PercentUsed(Number(data, "limit"), double.NaN, Number(data, "remaining")),
                    Month, EpochSeconds(ProviderJson.Get(data, "reset")), now);
            }
        }
        credits ??= CycleWindow(ProviderJson.Obj(plan, "balance"), ProviderJson.Obj(plan, "billingCycle"));
        if (credits is not null)
        {
            if (credits.WindowSeconds >= 27 * Day && credits.WindowSeconds <= 32 * Day) output.Monthly = credits;
            else output.Extra("Credits", credits);
        }
        if (ProviderJson.Obj(rate, "dailyLimit") is { } daily && !ProviderJson.Bool(daily, "isWithinGracePeriod", false))
        {
            output.ShortWindow = MakeWindow(PercentUsed(Number(daily, "limit"), double.NaN, Number(daily, "remaining")),
                Day, EpochSeconds(ProviderJson.Get(daily, "reset")), now);
        }
        output.Extra("API requests", MakeWindow(PercentUsed(Number(rate, "limit"), double.NaN, Number(rate, "remaining")),
            Day, EpochSeconds(ProviderJson.Get(rate, "reset")), now));
        return output.Build();
    }

    // ------------------------------------------------------------------ Warp

    /// <summary>
    /// Warp's <c>GetRequestLimitInfo</c> GraphQL query: monthly credits (Warp still names them
    /// requests) and add-on credit grants for the user and their workspaces.
    /// </summary>
    public static UsageSnapshot Warp(string body, DateTimeOffset now)
    {
        var output = new Builder(now);
        if (Path(ProviderJson.Object(body), "data", "user", "user") is not { } user) return output.Build();
        if (ProviderJson.Obj(user, "requestLimitInfo") is { } info)
        {
            if (ProviderJson.Bool(info, "isUnlimited", false))
            {
                output.Plan = "Unlimited";
                output.Credits = new UsageCredits(true, true, null);
            }
            else
            {
                output.Monthly = MakeWindow(PercentUsed(Number(info, "requestLimit"), Number(info, "requestsUsedSinceLastRefresh"), double.NaN),
                    Month, EpochSeconds(ProviderJson.Get(info, "nextRefreshTime")), now);
            }
        }
        var grants = new List<JsonObject>();
        grants.AddRange((ProviderJson.Arr(user, "bonusGrants") ?? []).OfType<JsonObject>());
        foreach (var workspace in (ProviderJson.Arr(user, "workspaces") ?? []).OfType<JsonObject>())
            grants.AddRange((ProviderJson.Arr(ProviderJson.Obj(workspace, "bonusGrantsInfo"), "grants") ?? []).OfType<JsonObject>());
        double remaining = 0;
        var any = false;
        foreach (var grant in grants)
        {
            var left = Number(grant, "requestCreditsRemaining");
            if (double.IsNaN(left) || left <= 0) continue;
            remaining += left;
            any = true;
        }
        if (any && output.Credits is null) output.Credits = new UsageCredits(true, false, WholeCount(remaining) + " add-on credits");
        return output.Build();
    }

    /// <summary>The first GraphQL error message in a Warp reply, or "".</summary>
    public static string WarpError(string body) =>
        ProviderJson.Arr(ProviderJson.Object(body), "errors") is { Count: > 0 } errors && errors[0] is JsonObject first
            ? Str(first, "message") : "";

    // ------------------------------------------------------------------ Devin

    /// <summary>
    /// <c>app.devin.ai/api/&lt;org&gt;/billing/quota/usage</c>: daily and weekly quota percentages
    /// used, their reset times, and the extra-usage balance.
    /// </summary>
    public static UsageSnapshot Devin(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = TitleCase(Str(root, "plan_name", "planName", "plan", "tier")) };
        if (!ProviderJson.Bool(root, "hide_daily_quota", false))
        {
            output.Add("Daily quota", MakeWindow(Number(root, "daily_percentage", "dailyPercentage"), Day,
                EpochSeconds(First(root, "daily_reset_at", "dailyResetAt")), now));
        }
        output.Weekly = MakeWindow(Number(root, "weekly_percentage", "weeklyPercentage"), Week,
            EpochSeconds(First(root, "weekly_reset_at", "weeklyResetAt")), now);
        var overage = Number(root, "overage_balance");
        if (double.IsNaN(overage))
        {
            var cents = Number(root, "overage_balance_cents");
            if (!double.IsNaN(cents)) overage = cents / 100;
        }
        if (!double.IsNaN(overage) && overage > 0) output.Credits = Balance(overage);
        return output.Build();
    }

    /// <summary>"5-hour", "Weekly", "Daily", "Monthly" or "N-day" for a window length.</summary>
    public static string DurationName(long seconds)
    {
        if (seconds <= 0) return "Usage";
        if (seconds == Week) return "Weekly";
        if (seconds >= 27 * Day && seconds <= 32 * Day) return "Monthly";
        if (seconds == Day) return "Daily";
        if (seconds % Day == 0) return $"{seconds / Day}-day";
        if (seconds % Hour == 0) return $"{seconds / Hour}-hour";
        return $"{Math.Max(1, seconds / 60)}-minute";
    }
}
