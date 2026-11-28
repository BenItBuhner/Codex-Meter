using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Models;

/// <summary>
/// What the Reset credits card says for Codex reset credits or Claude Code saved resets, shared by
/// the Windows and Linux dashboards so both read the same: the count, what a reset refills, the
/// resets with their expiry, the auto-use rules that are on, and whether one can be used now.
/// </summary>
public sealed record ResetCreditsCard(
    string Count,
    string Description,
    IReadOnlyList<string> Lines,
    string? AutoUse,
    bool CanUse,
    string? Held,
    string ConfirmText,
    string? CreditId)
{
    public static ResetCreditsCard For(UsageProvider provider, ProviderCacheEntry? cache, int fallbackCount, AppSettings settings, DateTimeOffset now)
    {
        string Expiry(DateTimeOffset? at) => at is { } value
            ? $"expires {UsageFormat.Absolute(value, now, settings.Use24HourClock)} ({UsageFormat.Relative(value, now)})"
            : "no expiry";

        if (provider == UsageProvider.ClaudeCode)
        {
            var saved = cache?.SavedResets;
            var next = saved?.NextGrant(now);
            var usable = saved?.UsableGrant(now);
            var lines = (saved?.Grants ?? []).Where(g => g.HasResets(now)).OrderBy(g => g.EndsAt ?? DateTimeOffset.MaxValue).Take(4)
                .Select(g => $"• {g.Title}{(g.ResetsLeft > 1 ? $" ×{g.ResetsLeft}" : "")}, {Expiry(g.EndsAt)}").ToList();
            var rules = new List<string>();
            var claude = settings.ClaudeResets;
            if (claude.AutoUseExpiring) rules.Add($"before one expires ({CreditExpiryAutoApply.LeadLabel(claude.LeadMinutes)})");
            if (claude.AutoUseWhenLow) rules.Add($"when a limit falls to {claude.Threshold}% left");
            return new ResetCreditsCard(
                saved?.AvailableTitle(now) ?? "No resets available",
                (usable ?? next)?.RefillsText ?? "Saved resets refill a Claude Code limit you've hit. Anthropic gives them out from time to time.",
                lines,
                AutoUseText(rules, claude.AutoUseWhenLow ? claude.RefillWait : null, settings, now),
                usable is not null,
                saved is null ? null : saved.UnavailableMessage(now) is { Length: > 0 } held ? held + "." : null,
                usable is null ? "" : $"This uses one {usable.Title} now. {usable.RefillsText}",
                usable?.Id);
        }

        var credits = cache?.Credits;
        var count = credits?.AvailableCount ?? fallbackCount;
        var codexLines = (credits?.Credits ?? []).Where(c => c.IsAvailable && (c.ExpiresAt is null || c.ExpiresAt > now))
            .OrderBy(c => c.ExpiresAt ?? DateTimeOffset.MaxValue).Take(4)
            .Select(c => $"• {(c.Title.Length > 0 ? c.Title : "Reset")}, {Expiry(c.ExpiresAt)}").ToList();
        var codexRules = new List<string>();
        if (settings.AutoUseExpiringResets) codexRules.Add($"before one expires ({CreditExpiryAutoApply.LeadLabel(settings.AutoUseLeadMinutes)})");
        if (settings.AutoUseWhenLow) codexRules.Add($"when a limit falls to {settings.AutoUseThreshold}% left");
        return new ResetCreditsCard(
            count == 1 ? "1 reset available" : $"{count} resets available",
            "A reset clears your Codex 5-hour and weekly limits right away.",
            codexLines,
            AutoUseText(codexRules, settings.AutoUseWhenLow ? settings.AutoUseRefillWait : null, settings, now),
            count > 0,
            null,
            "This clears your Codex 5-hour and weekly limits. The reset that expires first is used.",
            null);
    }

    /// <summary>"Auto-use is on: …." plus, while one is held back, why no reset was used.</summary>
    private static string? AutoUseText(List<string> rules, RefillWait? wait, AppSettings settings, DateTimeOffset now)
    {
        if (rules.Count == 0) return null;
        var text = "Auto-use is on: " + string.Join(" and ", rules) + ".";
        return RefillWaitText(wait, settings, now) is { } held ? text + " " + held : text;
    }

    /// <summary>"Your weekly limit refills on its own tomorrow at 9:00 AM, so no reset was used.", or null.</summary>
    public static string? RefillWaitText(RefillWait? wait, AppSettings settings, DateTimeOffset now) =>
        wait is { } held && held.IsActive(now) ? held.Summary(at => UsageFormat.Absolute(at, now, settings.Use24HourClock)) : null;
}
