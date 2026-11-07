using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Parsing;

/// <summary>
/// Turns each added provider's usage response into a <see cref="UsageSnapshot"/>. A line-for-line
/// port of Android's and iOS's <c>ProviderUsageParsers</c>, so every app reads the same windows
/// from the same body. None of these routes is documented publicly; the shapes follow the
/// providers' own CLIs and editors, so every reader is defensive: a missing field leaves a
/// window out rather than failing the whole refresh.
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
    /// <c>v1internal:retrieveUserQuotaSummary</c>: buckets per model family. Gemini is the main
    /// pair of windows; Claude and other third-party models become an additional limit.
    /// </summary>
    public static UsageSnapshot Antigravity(string body, string? plan, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = plan ?? "" };
        var buckets = new List<JsonObject>();
        CollectBuckets(root, buckets);
        UsageWindow? thirdShort = null, thirdWeekly = null;
        foreach (var bucket in buckets)
        {
            var id = Str(bucket, "bucketId", "id", "name").ToLowerInvariant();
            var fraction = Number(bucket, "remainingFraction", "remaining_fraction");
            // proto3 omits zero: a bucket with a reset but no fraction is used up.
            if (double.IsNaN(fraction) && (ProviderJson.Has(bucket, "resetTime") || ProviderJson.Has(bucket, "reset_time"))) fraction = 0;
            if (double.IsNaN(fraction)) continue;
            var weekly = id.Contains("week") || id.Contains("7d");
            var seconds = weekly ? Week : 5 * Hour;
            var window = MakeWindow((1 - fraction) * 100, seconds, EpochSeconds(First(bucket, "resetTime", "reset_time")), now);
            if (id.StartsWith("gemini", StringComparison.Ordinal) && !id.Contains("argon"))
            {
                if (weekly && output.Weekly is null) output.Weekly = window;
                else if (!weekly && output.ShortWindow is null) output.ShortWindow = window;
            }
            else if (id.StartsWith("3p", StringComparison.Ordinal) || id.Contains("third"))
            {
                if (weekly) thirdWeekly = window; else thirdShort = window;
            }
            else
            {
                var label = Str(bucket, "displayName", "label");
                output.Extra(label.Length == 0 ? TitleCase(id) : label, window);
            }
        }
        if ((thirdShort ?? thirdWeekly) is { } primary)
        {
            var secondary = thirdShort is not null ? thirdWeekly : null;
            var reached = primary.UsedPercent >= 100 || (secondary?.UsedPercent ?? 0) >= 100;
            output.Extras.Insert(0, new UsageLimit("third_party", "Claude and other models", "", !reached, reached, primary, secondary));
        }
        return output.Build();
    }

    private static void CollectBuckets(JsonNode? node, List<JsonObject> into)
    {
        switch (node)
        {
            case JsonObject o:
                if (ProviderJson.Has(o, "bucketId") || ProviderJson.Has(o, "remainingFraction"))
                {
                    into.Add(o);
                    return;
                }
                foreach (var pair in o) CollectBuckets(pair.Value, into);
                break;
            case JsonArray array:
                foreach (var item in array) CollectBuckets(item, into);
                break;
        }
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

    /// <summary>CodeWhisperer <c>GetUsageLimits</c>: monthly agentic requests or credits.</summary>
    public static UsageSnapshot Kiro(string body, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now)
        {
            Plan = Regex.Replace(Str(ProviderJson.Obj(root, "subscriptionInfo"), "subscriptionTitle", "type"), @"(?i)^kiro\s+", ""),
        };
        var list = ProviderJson.Arr(root, "usageBreakdownList") ?? ProviderJson.Arr(root, "limits");
        var fallbackReset = EpochSeconds(First(root, "nextDateReset", "resetDate"));
        for (var index = 0; index < (list?.Count ?? 0); index++)
        {
            if (list![index] is not JsonObject entry) continue;
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

    private static readonly Regex AmpFree = new(@"\$\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*/\s*\$\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*remaining",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AmpCredits = new(@"\$\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:in\s+)?(?:individual\s+)?credits",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// <c>ampcode.com/api/internal?userDisplayBalanceInfo</c>: Amp returns the sentence the CLI
    /// prints, e.g. "Amp Free: $7.12/$10 remaining (replenishes +$0.42/hour) · $5.00 credits".
    /// The free tier refills continuously, so it is shown as a daily window.
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
            output.Add("Free usage", MakeWindow(PercentUsed(Amount(free.Groups[2].Value), double.NaN, Amount(free.Groups[1].Value)), Day, 0, now));
        }
        if (AmpCredits.Match(text) is { Success: true } credits) output.Credits = Balance(Amount(credits.Groups[1].Value));
        return output.Build();
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

    // ------------------------------------------------------------------ Key-only heuristics

    /// <summary>
    /// Synthetic, Chutes, v0 and Warp don't publish one stable shape, so this walks the response
    /// for anything that looks like a quota: an object with a limit and a used or remaining count,
    /// or a percent, plus a reset time. Window length comes from its name ("5h", "weekly",
    /// "daily", "monthly") or, failing that, from <paramref name="defaultSeconds"/>.
    /// </summary>
    public static UsageSnapshot KeyQuota(string body, long defaultSeconds, DateTimeOffset now)
    {
        var root = ProviderJson.Object(body);
        var output = new Builder(now) { Plan = TitleCase(Str(root, "plan", "planName", "tier", "subscription")) };
        WalkQuotas(root, "", defaultSeconds, output, 0);
        return output.Build();
    }

    private static void WalkQuotas(JsonNode? node, string name, long defaultSeconds, Builder output, int depth)
    {
        if (depth > 6) return;
        switch (node)
        {
            case JsonArray array:
                foreach (var value in array)
                {
                    var itemName = value is JsonObject o ? Str(o, "name", "type", "window", "period", "label", "id") : "";
                    WalkQuotas(value, itemName.Length == 0 ? name : itemName, defaultSeconds, output, depth + 1);
                }
                break;
            case JsonObject obj:
                var percent = Number(obj, PercentKeys);
                if (!double.IsNaN(percent) && percent <= 1 && ProviderJson.Has(obj, "utilization")) percent *= 100;
                if (double.IsNaN(percent))
                {
                    percent = PercentUsed(
                        Number(obj, "limit", "max", "total", "quota", "allowed", "requestLimit"),
                        Number(obj, "used", "usage", "count", "consumed", "requestsUsedSinceLastRefresh", "requestsUsed"),
                        Number(obj, "remaining", "left", "available"));
                }
                if (!double.IsNaN(percent))
                {
                    var seconds = SecondsFromName(name);
                    if (seconds <= 0) seconds = SecondsFromObject(obj);
                    if (seconds <= 0) seconds = defaultSeconds;
                    var label = name.Length == 0 ? DurationName(seconds) + " limit" : TitleCase(name);
                    output.Add(label, MakeWindow(percent, seconds, ResetFrom(obj, output.Now), output.Now));
                    return;
                }
                foreach (var pair in obj.ToList()) WalkQuotas(pair.Value, pair.Key, defaultSeconds, output, depth + 1);
                break;
        }
    }

    internal static long SecondsFromName(string? name)
    {
        var value = (name ?? "").ToLowerInvariant();
        if (value.Length == 0) return 0;
        if (value.Contains("five") || value.Contains("5h") || value.Contains("5_hour") || value.Contains("rolling")) return 5 * Hour;
        if (value.Contains("hour")) return Hour;
        if (value.Contains("week") || value.Contains("7d")) return Week;
        if (value.Contains("day") || value.Contains("daily") || value.Contains("24h")) return Day;
        if (value.Contains("month") || value.Contains("30d")) return Month;
        return 0;
    }

    private static long SecondsFromObject(JsonObject o)
    {
        var seconds = Number(o, "windowSeconds", "window_seconds", "periodSeconds", "durationSeconds");
        if (!double.IsNaN(seconds) && seconds > 0) return (long)seconds;
        return SecondsFromName(Str(o, "window", "period", "interval", "refreshDuration"));
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
