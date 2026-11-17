using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using CodexMeter.Core.Parsing;

namespace CodexMeter.Core.Models;

/// <summary>One saved-reset grant. A grant can hold several resets.</summary>
public sealed record SavedResetGrant
{
    public string Id { get; init; } = "";
    public string Label { get; init; } = "";
    public int ResetsTotal { get; init; }
    public int ResetsLeft { get; init; }
    public DateTimeOffset? EndsAt { get; init; }
    /// <summary>Limits this grant refills: "five_hour", "seven_day", ...</summary>
    public List<string> Clears { get; init; } = [];
    public bool Paused { get; init; }
    public bool UsableNow { get; init; }
    public bool UseRequiresLimit { get; init; } = true;
    /// <summary>"session", "weekly", "full", or empty.</summary>
    public string Type { get; init; } = "";
    public string UnavailableReason { get; init; } = "";
    /// <summary>The one model this reset covers ("Fable"), or empty when it covers every model.</summary>
    public string Scope { get; init; } = "";

    [JsonIgnore] public bool ModelOnly => Scope.Length > 0;

    public bool HasResets(DateTimeOffset now) => ResetsLeft > 0 && !Paused && (EndsAt is null || EndsAt > now);

    [JsonIgnore] public bool ClearsFiveHour => Clears.Contains("five_hour") || Type is "session" or "full";

    /// <summary>Whether it refills the all-models weekly limit; a model-only reset does not.</summary>
    [JsonIgnore]
    public bool ClearsWeekly => Clears.Any(l => l == "seven_day" || (!ModelOnly && l.StartsWith("seven_day", StringComparison.Ordinal)))
        || Type is "weekly" or "full";

    /// <summary>"5-hour limit", "weekly limit", "5-hour and weekly limits", or "Fable limit".</summary>
    [JsonIgnore]
    public string ClearsLabel => ModelOnly ? Scope + " limit"
        : ClearsFiveHour && ClearsWeekly ? "5-hour and weekly limits"
        : ClearsWeekly ? "weekly limit"
        : ClearsFiveHour ? "5-hour limit"
        : "usage limit";

    /// <summary>What using it does, in a sentence.</summary>
    [JsonIgnore]
    public string RefillsText => ModelOnly ? $"Refills only your {Scope} limit. Your 5-hour and weekly limits stay as they are."
        : ClearsFiveHour && ClearsWeekly ? "Refills your 5-hour and weekly limits."
        : ClearsWeekly ? "Refills your weekly limit. Your 5-hour limit stays as it is."
        : ClearsFiveHour ? "Refills your 5-hour limit. Your weekly limit stays as it is."
        : "Refills a usage limit you've hit.";

    /// <summary>Why it can't be used right now, in words, or empty when the server says it can.</summary>
    [JsonIgnore]
    public string HeldText => UsableNow ? "" : UnavailableReason switch
    {
        "blocked_seven_day" => "Your weekly limit is blocking you, and this reset doesn't clear it",
        "blocked_five_hour" => "Your 5-hour limit is blocking you, and this reset doesn't clear it",
        "blocked_opus" or "blocked_sonnet" or "blocked_other" => "A model limit this reset doesn't clear is blocking you",
        "cooldown" => "Available again after a short cooldown",
        _ => ModelOnly ? $"Works once you hit your {Scope} limit" : "Works once you hit a usage limit",
    };

    /// <summary>The name claude.ai uses: "Full reset", "Session reset", or "Fable reset" for one model.</summary>
    [JsonIgnore]
    public string Title => ModelOnly ? Scope + " reset" : Type switch
    {
        "full" => "Full reset",
        "weekly" => "Weekly reset",
        "session" => "Session reset",
        _ => Label.Length == 0 ? "Limit reset" : Label,
    };
}

/// <summary>
/// Claude Code's saved usage-limit resets ("cedar_ember" in the CLI), a port of the Android
/// <c>ClaudeSavedResets</c>. Claude Code reads them from <c>GET /api/oauth/usage?cedar_ember=1</c>
/// and spends one with <c>POST /api/organizations/{org}/reset_rate_limits</c>. A reset normally only
/// works at a usage limit; the server then answers <c>not_limited</c> and nothing is used.
/// Anthropic does not document any of this, so parsing is defensive and an absent or malformed
/// block simply means "no saved resets".
/// </summary>
public sealed record ClaudeSavedResets
{
    public const string Program = "cedar_ember";
    private static readonly Regex GrantId = new("^[a-z0-9_-]{1,40}$", RegexOptions.CultureInvariant);
    private static readonly Regex RequestId = new("^[A-Za-z0-9_-]{1,64}$", RegexOptions.CultureInvariant);

    public bool Eligible { get; init; }
    public string IneligibleReason { get; init; } = "";
    public bool AtLimit { get; init; }
    public List<SavedResetGrant> Grants { get; init; } = [];
    public string NextGrantId { get; init; } = "";
    public DateTimeOffset? CooldownUntil { get; init; }
    public DateTimeOffset FetchedAt { get; init; }

    private static readonly HashSet<string> GenericNames =
    [
        "", "limit", "limits", "full", "session", "weekly", "usage", "rate limit", "saved", "free", "bonus", "all",
        "all models", "oauth apps", "overage", "five hour", "seven day",
    ];

    private static readonly Regex LabelScope = new(@"^([\p{L}][\p{L}\p{N}.-]{1,23})(?:[ -]only)? reset$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>Reads the <c>cedar_ember</c> block out of a usage response body; null when missing or unreadable.</summary>
    public static ClaudeSavedResets? FromUsageBody(string? body, DateTimeOffset fetchedAt) =>
        FromApiJson(Json.Obj(Json.Get(Json.TryParseObject(body), "cedar_ember")), fetchedAt);

    public static ClaudeSavedResets? FromApiJson(JsonObject? block, DateTimeOffset fetchedAt)
    {
        if (block is null || !block.ContainsKey("eligible")) return null;
        var grants = new List<SavedResetGrant>();
        foreach (var node in Json.Arr(Json.Get(block, "grants")) ?? [])
        {
            if (Json.Obj(node) is not { } row) continue;
            var id = Json.Str(Json.Get(row, "id"));
            if (!GrantId.IsMatch(id) || !row.ContainsKey("resets_left")) continue;
            var clears = Strings(Json.Arr(Json.Get(row, "clears")));
            var type = Json.Str(Json.Get(row, "type"));
            var label = Json.Str(Json.Get(row, "label"));
            grants.Add(new SavedResetGrant
            {
                Id = id,
                Label = label,
                ResetsTotal = Math.Max(0, Json.Int(Json.Get(row, "resets_total"), 0)),
                ResetsLeft = Math.Max(0, Json.Int(Json.Get(row, "resets_left"), 0)),
                EndsAt = ParseTime(Json.Str(Json.Get(row, "ends_at"))),
                Clears = clears,
                Paused = Json.Bool(Json.Get(row, "paused"), false),
                UsableNow = Json.Bool(Json.Get(row, "usable_now"), false),
                UseRequiresLimit = Json.Bool(Json.Get(row, "use_requires_limit"), true),
                Type = type,
                UnavailableReason = Json.Str(Json.Get(row, "unavailable_reason")),
                Scope = ScopeOf(row, type, label, clears),
            });
        }
        var next = Json.Str(Json.Get(block, "next_grant_id"));
        return new ClaudeSavedResets
        {
            Eligible = Json.Bool(Json.Get(block, "eligible"), false),
            IneligibleReason = Json.Str(Json.Get(block, "ineligible_reason")),
            AtLimit = Json.Bool(Json.Get(block, "at_limit"), false),
            Grants = grants,
            NextGrantId = grants.Any(g => g.Id == next) ? next : "",
            CooldownUntil = ParseTime(Json.Str(Json.Get(block, "cooldown_until"))),
            FetchedAt = fetchedAt,
        };
    }

    /// <summary>The model a grant row is limited to ("Fable"), or empty when it covers every model.</summary>
    public static string ScopeOf(JsonObject? row, string type, string label, IReadOnlyList<string> clears)
    {
        var name = ModelName(Json.Get(row, "scope"));
        if (name.Length == 0) name = ModelName(Json.Get(row, "model"));
        if (name.Length == 0) name = Named(Json.Str(Json.Get(row, "model_display_name")));
        if (name.Length == 0) name = ScopeFromClears(clears);
        if (name.Length == 0 && type.Length > 0 && type is not ("session" or "weekly" or "full"))
            name = Named(Regex.Replace(Regex.Replace(type, "^(weekly_scoped_|seven_day_|model_|scoped_)", ""), "(_weekly|_seven_day|_reset|_only)$", ""));
        if (name.Length == 0 && LabelScope.Match(label.Trim()) is { Success: true } match) name = Named(match.Groups[1].Value);
        return name;
    }

    private static string ModelName(JsonNode? value)
    {
        if (value is JsonValue v && v.TryGetValue<string>(out var text)) return Named(text);
        if (value is not JsonObject obj) return "";
        if (Json.Get(obj, "model") is { } inner && ModelName(inner) is { Length: > 0 } nested) return nested;
        foreach (var key in new[] { "display_name", "name", "id" })
        {
            var name = Named(Json.Str(Json.Get(obj, key)));
            if (name.Length > 0) return name;
        }
        return "";
    }

    /// <summary>The one model every entry of <paramref name="clears"/> names, when none is an all-models limit.</summary>
    public static string ScopeFromClears(IReadOnlyList<string>? clears)
    {
        var found = "";
        foreach (var limit in clears ?? [])
        {
            var name = Named(Regex.Replace(Regex.Replace(limit, "^(weekly_scoped_|seven_day_|weekly_)", ""), "(_seven_day|_weekly)$", ""));
            if (name.Length == 0 || (found.Length > 0 && found != name)) return "";
            found = name;
        }
        return found;
    }

    /// <summary>"fable_5" as "Fable 5"; empty for words that do not name a model.</summary>
    private static string Named(string? raw)
    {
        var words = Regex.Replace((raw ?? "").Replace('_', ' ').Replace('-', ' ').Trim(), @"\s+", " ");
        if (words.Length > 24 || GenericNames.Contains(words.ToLowerInvariant())) return "";
        return string.Join(' ', words.Split(' ').Select(w => char.ToUpperInvariant(w[0]) + w[1..]));
    }

    /// <summary>Resets that can still be spent, across all grants.</summary>
    public int AvailableCount(DateTimeOffset now) => Grants.Where(g => g.HasResets(now)).Sum(g => g.ResetsLeft);

    /// <summary>One credit per remaining reset, so the shared reset-credits card and Priority list render Claude resets too.</summary>
    public ResetCreditsSnapshot ToResetCredits(DateTimeOffset now)
    {
        var credits = new List<RateLimitResetCredit>();
        foreach (var grant in Grants.Where(g => g.HasResets(now)))
        {
            for (var i = 0; i < grant.ResetsLeft; i++)
                credits.Add(new RateLimitResetCredit(grant.ResetsLeft == 1 ? grant.Id : $"{grant.Id}#{i + 1}", grant.Type,
                    RateLimitResetCredit.AvailableStatus, null, grant.EndsAt, grant.Title, grant.ClearsLabel));
        }
        return new ResetCreditsSnapshot(credits.Count, credits, FetchedAt);
    }

    /// <summary>The grant id behind a credit id from <see cref="ToResetCredits"/> ("g_1#2" is "g_1").</summary>
    public static string GrantIdOf(string creditId) => creditId.Split('#', 2)[0];

    /// <summary>"3 Fable resets and 1 Full reset"; empty when nothing is left.</summary>
    public string InventoryLabel(DateTimeOffset now)
    {
        var counts = new List<(string Title, int Count)>();
        foreach (var grant in Grants.Where(g => g.HasResets(now)))
        {
            var index = counts.FindIndex(c => c.Title == grant.Title);
            if (index < 0) counts.Add((grant.Title, grant.ResetsLeft));
            else counts[index] = (grant.Title, counts[index].Count + grant.ResetsLeft);
        }
        var parts = counts.Where(c => c.Count > 0).Select(c => $"{c.Count} {c.Title}{(c.Count == 1 ? "" : "s")}").ToList();
        return parts.Count <= 1 ? string.Join("", parts) : string.Join(", ", parts.Take(parts.Count - 1)) + " and " + parts[^1];
    }

    /// <summary>"3 Fable resets available", or "No resets available".</summary>
    public string AvailableTitle(DateTimeOffset now) => InventoryLabel(now) is { Length: > 0 } inventory ? inventory + " available" : "No resets available";

    private static DateTimeOffset ExpiryOrder(SavedResetGrant grant) => grant.EndsAt ?? DateTimeOffset.MaxValue;

    /// <summary>The grant the server wants spent next, else the one that expires first.</summary>
    public SavedResetGrant? NextGrant(DateTimeOffset now)
    {
        SavedResetGrant? best = null;
        foreach (var grant in Grants.Where(g => g.HasResets(now)))
        {
            if (grant.Id == NextGrantId) return grant;
            if (best is null || ExpiryOrder(grant) < ExpiryOrder(best)) best = grant;
        }
        return best;
    }

    private bool Open(DateTimeOffset now) => Eligible && !(CooldownUntil > now);

    /// <summary>The grant a claim would spend right now, or null when none can be used yet.</summary>
    public SavedResetGrant? UsableGrant(DateTimeOffset now)
    {
        if (!Open(now)) return null;
        if (NextGrant(now) is { UsableNow: true } next) return next;
        return Grants.Where(g => g.HasResets(now) && g.UsableNow).OrderBy(ExpiryOrder).FirstOrDefault();
    }

    /// <summary>A usable grant that refills the given window ("5-hour", "weekly", "monthly"), else null.</summary>
    public SavedResetGrant? UsableGrantFor(string windowLabel, DateTimeOffset now)
    {
        if (!Open(now)) return null;
        var fiveHour = windowLabel == "5-hour";
        SavedResetGrant? best = null;
        foreach (var grant in Grants.Where(g => g.HasResets(now) && g.UsableNow && (fiveHour ? g.ClearsFiveHour : g.ClearsWeekly)))
        {
            if (grant.Id == NextGrantId) return grant;
            if (best is null || ExpiryOrder(grant) < ExpiryOrder(best)) best = grant;
        }
        return best;
    }

    /// <summary>Why the next reset can't be used right now, in words; empty when it can.</summary>
    public string UnavailableMessage(DateTimeOffset now)
    {
        if (UsableGrant(now) is not null) return "";
        if (!Eligible) return "Anthropic isn't offering resets on this account right now";
        if (CooldownUntil > now) return "Available again after a short cooldown";
        return NextGrant(now)?.HeldText is { Length: > 0 } held ? held : "Works once you hit a usage limit";
    }

    /// <summary>A copy with one reset taken off <paramref name="grantId"/>, so the card updates straight away.</summary>
    public ClaudeSavedResets AfterUse(string grantId) => this with
    {
        AtLimit = false,
        NextGrantId = "",
        Grants = Grants.Select(g => g.Id == grantId ? g with { ResetsLeft = Math.Max(0, g.ResetsLeft - 1), UsableNow = false } : g).ToList(),
    };

    /// <summary>Request body for <c>POST /api/organizations/{org}/reset_rate_limits</c>.</summary>
    public static Dictionary<string, string> ClaimBody(string grantId, string requestId)
    {
        if (!GrantId.IsMatch(grantId ?? "") || !RequestId.IsMatch(requestId ?? ""))
            throw new ArgumentException("Malformed saved-reset id.");
        return new() { ["program"] = Program, ["grant_id"] = grantId!, ["request_id"] = requestId! };
    }

    /// <summary>True when the granted scopes are known and lack the Claude Code sign-in scope.</summary>
    public static bool NeedsClaudeCodeSignIn(string? grantedScope) =>
        !string.IsNullOrWhiteSpace(grantedScope)
        && !grantedScope.Split(' ', StringSplitOptions.RemoveEmptyEntries).Contains(Services.ClaudeCodeService.SessionsScope);

    public static DateTimeOffset? ParseTime(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null
        : DateTimeOffset.TryParse(value.Trim(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at) ? at : null;

    private static List<string> Strings(JsonArray? array) =>
        (array ?? []).Select(n => Json.Str(n).Trim().ToLowerInvariant()).Where(v => v.Length > 0).ToList();

    /// <summary>
    /// Plain report of what Anthropic's usage route said about saved resets, for "Check with
    /// Anthropic". Shows the raw <c>cedar_ember</c> block so it can be copied. Never includes tokens.
    /// </summary>
    public static string DescribeCheck(int status, string? body, string? grantedScope, DateTimeOffset now)
    {
        var output = new StringBuilder();
        output.Append("Anthropic answered: HTTP ").Append(status).Append('\n');
        output.Append(string.IsNullOrWhiteSpace(grantedScope) ? "Sign-in access: not recorded (signed in on an older version)\n"
            : "Sign-in access: " + grantedScope.Trim() + "\n");
        if (status == 429) return output.Append("\nAnthropic is rate limiting usage checks. Try again in a few minutes.").ToString();
        if (status is < 200 or >= 300) return output.Append("\nThe usage check failed, so resets could not be read.").ToString();
        var block = Json.Obj(Json.Get(Json.TryParseObject(body), "cedar_ember"));
        if (block is null) output.Append("Reset status: not included in Anthropic's answer\n");
        else if (FromApiJson(block, now) is not { } saved) output.Append("Reset status: included but unreadable\n");
        else
        {
            output.Append("Offered resets: ").Append(saved.Eligible ? "yes" : "no");
            if (saved.IneligibleReason.Length > 0) output.Append(" (").Append(saved.IneligibleReason).Append(')');
            output.Append('\n').Append("Resets you can use: ").Append(saved.AvailableCount(now)).Append('\n');
            foreach (var grant in saved.Grants)
            {
                output.Append("• ").Append(grant.Title).Append(": ").Append(grant.ResetsLeft).Append(" of ").Append(grant.ResetsTotal).Append(" left");
                if (grant.UsableNow) output.Append(", usable now");
                else if (grant.UnavailableReason.Length > 0) output.Append(", held (").Append(grant.UnavailableReason).Append(')');
                if (grant.EndsAt is { } ends) output.Append(", ends ").Append(ends.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture));
                output.Append('\n');
            }
        }
        if (NeedsClaudeCodeSignIn(grantedScope))
            output.Append("\nThis sign-in lacks Claude Code access. Sign out of Claude Code and sign in again.\n");
        var raw = block?.ToJsonString() ?? "(none)";
        if (raw.Length > 2000) raw = raw[..2000] + "...";
        return output.Append("\nRaw: ").Append(raw).ToString();
    }
}

public enum SavedResetClaimOutcome { Reset, AlreadyUsed, NotLimited, Cooldown, Ineligible, Unavailable, RateLimited, AuthError, Error }

/// <summary>Outcome of spending a saved reset. Mirrors the server's word, plus local failures.</summary>
public sealed record SavedResetClaimResult(SavedResetClaimOutcome Outcome, string Reason = "", int ResetsLeft = -1, IReadOnlyList<string>? Cleared = null)
{
    public static SavedResetClaimResult Parse(string? body)
    {
        if (Json.TryParseObject(body) is not { } json) return new(SavedResetClaimOutcome.Error, "unreadable_response");
        var outcome = Json.Str(Json.Get(json, "result")) switch
        {
            "reset" => SavedResetClaimOutcome.Reset,
            "already_used" => SavedResetClaimOutcome.AlreadyUsed,
            "not_limited" => SavedResetClaimOutcome.NotLimited,
            "cooldown" => SavedResetClaimOutcome.Cooldown,
            "ineligible" => SavedResetClaimOutcome.Ineligible,
            _ => SavedResetClaimOutcome.Unavailable,
        };
        var left = Json.Get(json, "resets_left") is { } n ? Json.Int(n, -1) : -1;
        return new(outcome, Json.Str(Json.Get(json, "reason")), left,
            (Json.Arr(Json.Get(json, "cleared")) ?? []).Select(c => Json.Str(c).Trim().ToLowerInvariant()).Where(c => c.Length > 0).ToList());
    }

    public bool Applied => Outcome == SavedResetClaimOutcome.Reset;

    /// <summary>Network or server trouble worth retrying later.</summary>
    public bool Retryable => Outcome is SavedResetClaimOutcome.Error or SavedResetClaimOutcome.RateLimited;

    /// <summary>Same wording as Codex's <see cref="ResetConsumeResult.UserMessage"/>.</summary>
    public string UserMessage => Outcome switch
    {
        SavedResetClaimOutcome.Reset => Cleared is { Count: > 0 } c
            ? $"Reset applied to {c.Count} usage window{(c.Count == 1 ? "." : "s.")}" : "Claude Code usage reset applied.",
        SavedResetClaimOutcome.NotLimited => "There is no used Claude Code allowance to reset right now.",
        SavedResetClaimOutcome.AlreadyUsed => "That reset request was already redeemed.",
        SavedResetClaimOutcome.Ineligible or SavedResetClaimOutcome.Unavailable => "No reset credit is currently available.",
        SavedResetClaimOutcome.Cooldown => "Resets are cooling down. Try again in a few minutes.",
        SavedResetClaimOutcome.RateLimited => "Claude is rate-limiting requests. Try again in a few minutes.",
        SavedResetClaimOutcome.AuthError => "Claude rejected the saved sign-in. Sign in with Claude again.",
        _ => "The reset could not be applied.",
    };

    /// <summary>As a Codex-style result, so the shared reset card shows one kind of answer.</summary>
    public ResetConsumeResult ToConsumeResult() => new(Outcome switch
    {
        SavedResetClaimOutcome.Reset => ResetConsumeOutcome.Reset,
        SavedResetClaimOutcome.NotLimited => ResetConsumeOutcome.NothingToReset,
        SavedResetClaimOutcome.AlreadyUsed => ResetConsumeOutcome.AlreadyRedeemed,
        SavedResetClaimOutcome.Ineligible or SavedResetClaimOutcome.Unavailable => ResetConsumeOutcome.NoCredit,
        _ => ResetConsumeOutcome.Unknown,
    }, Cleared?.Count ?? 0)
    { Message = UserMessage };
}

public enum ClaudeAutoUseOutcome { Wait, FireExpiring, FireLowUsage, SkipAlreadyTried, SkipNoMatchingReset }

/// <summary>
/// Automatic use of Claude saved resets, a port of the Android <c>ClaudeResetAutoUse</c>: one that
/// is about to expire is used while the server says it can be, or one is used when a window it
/// refills runs low. Each rule fires once per grant or window, never from a stale status.
/// </summary>
public sealed record ClaudeResetAutoUse(ClaudeAutoUseOutcome Outcome, SavedResetGrant? Grant, string AttemptKey, string WindowLabel, int RemainingPercent)
{
    public static readonly TimeSpan MaxStatusAge = TimeSpan.FromMinutes(2);

    public bool Fires => Outcome is ClaudeAutoUseOutcome.FireExpiring or ClaudeAutoUseOutcome.FireLowUsage;

    private static readonly ClaudeResetAutoUse Waiting = new(ClaudeAutoUseOutcome.Wait, null, "", "", -1);

    public static ClaudeResetAutoUse Evaluate(ClaudeSavedResets? status, UsageSnapshot? usage, bool expiryEnabled, int leadMinutes,
        bool lowUsageEnabled, int threshold, LowUsageAutoUseMetric metric, string? lastExpiryKey, string? lastLowUsageToken, DateTimeOffset now)
    {
        if ((!expiryEnabled && !lowUsageEnabled) || status is null || now - status.FetchedAt > MaxStatusAge) return Waiting;
        ClaudeResetAutoUse? skipped = null;
        if (expiryEnabled)
        {
            var lead = CreditExpiryAutoApply.LeadInterval(leadMinutes);
            var expiring = status.Grants.Where(g => g.HasResets(now) && g.UsableNow && g.EndsAt is { } e && e - now <= lead)
                .OrderBy(g => g.EndsAt).FirstOrDefault();
            if (expiring is not null)
            {
                var key = $"expiry:{expiring.Id}:{expiring.ResetsLeft}";
                if (key != lastExpiryKey) return new(ClaudeAutoUseOutcome.FireExpiring, expiring, key, "", -1);
                skipped = new(ClaudeAutoUseOutcome.SkipAlreadyTried, expiring, key, "", -1);
            }
        }
        if (lowUsageEnabled && usage is not null)
        {
            var low = LowUsageAutoUse.Evaluate(usage, metric, threshold, status.AvailableCount(now), lastLowUsageToken, now);
            if (low.Fires)
            {
                return status.UsableGrantFor(low.WindowLabel, now) is { } grant
                    ? new(ClaudeAutoUseOutcome.FireLowUsage, grant, low.WindowToken, low.WindowLabel, low.RemainingPercent)
                    : new(ClaudeAutoUseOutcome.SkipNoMatchingReset, null, low.WindowToken, low.WindowLabel, low.RemainingPercent);
            }
            if (low.Outcome == LowUsageOutcome.SkipAlreadyUsed)
                return new(ClaudeAutoUseOutcome.SkipAlreadyTried, null, low.WindowToken, low.WindowLabel, low.RemainingPercent);
        }
        return skipped ?? Waiting;
    }
}
