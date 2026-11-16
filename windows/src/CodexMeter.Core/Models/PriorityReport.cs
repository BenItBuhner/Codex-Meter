using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Models;

/// <summary>One line of the Priority page.</summary>
public sealed record PriorityItem(UsageProvider Provider, string Title, string Detail, int? RemainingPercent, DateTimeOffset? At)
{
    /// <summary>A page that explains the item (the official status page or incident), or null.</summary>
    public string? Link { get; init; }
}

/// <summary>
/// The Priority page, a desktop port of the Android Priority tab's lists, each shown only when it
/// has something: <b>Limits to watch</b> (running out before they reset, or nearly gone, and still
/// with some left), <b>Used up</b> (with when each resets, soonest first), <b>Coming up</b> (a
/// monthly limit renewing within three days, reset credits expiring within three days), and
/// <b>New models</b> for the makers the user is signed in to. Above those, as on Android, come
/// <b>Service status</b> (an official outage or maintenance on a component the provider depends
/// on) and <b>Running slow</b> (degraded performance on the official page, or a model much slower
/// than usual in public OpenRouter figures).
/// </summary>
public sealed record PriorityReport(
    IReadOnlyList<PriorityItem> Watch,
    IReadOnlyList<PriorityItem> UsedUp,
    IReadOnlyList<PriorityItem> ComingUp,
    IReadOnlyList<PriorityItem> NewModels)
{
    public const int LowPercent = 15;
    public static readonly TimeSpan ComingUpWindow = TimeSpan.FromDays(3);

    /// <summary>Official outages and maintenance, worst first. Each item's detail ends with the status page.</summary>
    public IReadOnlyList<PriorityItem> Service { get; init; } = [];
    /// <summary>Degraded performance and significant model slowdowns, worst first.</summary>
    public IReadOnlyList<PriorityItem> Slow { get; init; } = [];

    public const string SlowNote = "Performance figures come from official status pages and public OpenRouter data, not your own requests.";

    public bool IsEmpty => Watch.Count == 0 && UsedUp.Count == 0 && ComingUp.Count == 0 && NewModels.Count == 0
        && Service.Count == 0 && Slow.Count == 0;

    /// <summary>One line for the top of the page.</summary>
    public string Headline => IsEmpty ? "Nothing needs you right now. Every connected limit is on track."
        : Service.Count > 0 ? $"{Service[0].Title}."
        : Watch.Count > 0 ? $"{Watch.Count} {(Watch.Count == 1 ? "limit needs" : "limits need")} watching."
        : UsedUp.Count > 0 ? $"{UsedUp.Count} {(UsedUp.Count == 1 ? "limit is" : "limits are")} used up."
        : "Nothing urgent. Here's what's coming up.";

    public static PriorityReport Build(IEnumerable<(UsageProvider Provider, UsageSnapshot? Usage, ResetCreditsSnapshot? Credits)> providers,
        IEnumerable<PriorityItem> newModels, DateTimeOffset now, PaceSensitivity sensitivity = PaceSensitivity.Balanced)
    {
        var watch = new List<PriorityItem>();
        var usedUp = new List<PriorityItem>();
        var coming = new List<PriorityItem>();
        foreach (var (provider, usage, credits) in providers)
        {
            if (usage is null) continue;
            foreach (var (label, window) in Windows(usage))
            {
                var resetAt = window.EffectiveResetDate(usage.FetchedAt);
                if (resetAt is { } past && past <= now) continue; // stale: it has reset since
                if (window.RemainingPercent <= 0)
                {
                    usedUp.Add(new PriorityItem(provider, $"{provider.DisplayName} {label}",
                        resetAt is { } r ? UsageFormat.ResetText(r, ResetDisplayMode.Relative, now) : "Used up", 0, resetAt));
                    continue;
                }
                var pace = UsagePace.Assess(window, usage.FetchedAt, now, sensitivity);
                if ((pace.IsAvailable && pace.IsAccelerated) || window.RemainingPercent <= LowPercent)
                {
                    var detail = pace is { IsAvailable: true, IsAccelerated: true }
                        ? $"{window.RemainingPercent}% left · at this pace, runs out in {UsageFormat.Duration(pace.EstimatedRemaining)}"
                        : $"{window.RemainingPercent}% left" + (resetAt is { } at ? " · resets " + When(at, now) : "");
                    watch.Add(new PriorityItem(provider, $"{provider.DisplayName} {label}", detail, window.RemainingPercent, pace.EstimatedExhaustionAt));
                }
                else if (window == usage.Monthly && resetAt is { } renews && renews - now <= ComingUpWindow)
                {
                    coming.Add(new PriorityItem(provider, $"{provider.DisplayName} monthly renews",
                        $"{window.RemainingPercent}% left to use · renews " + UsageFormat.ResetText(renews, ResetDisplayMode.Relative, now),
                        window.RemainingPercent, renews));
                }
            }
            if (credits is not null)
            {
                foreach (var credit in credits.Credits.Where(c => c.IsAvailable && c.ExpiresAt is { } e && e > now && e - now <= ComingUpWindow))
                {
                    coming.Add(new PriorityItem(provider, $"{provider.DisplayName} reset credit expires",
                        "Expires " + When(credit.ExpiresAt!.Value, now) + ". Use it before then or it's lost.",
                        null, credit.ExpiresAt));
                }
            }
        }
        return new PriorityReport(
            watch.OrderBy(i => i.RemainingPercent).ToList(),
            usedUp.OrderBy(i => i.At ?? DateTimeOffset.MaxValue).ToList(),
            coming.OrderBy(i => i.At ?? DateTimeOffset.MaxValue).ToList(),
            newModels.ToList());
    }

    /// <summary>Adds official status and model slowdowns (Android's downtime and performance items).</summary>
    public PriorityReport WithService(IEnumerable<(UsageProvider Provider, ProviderStatus? Status, IReadOnlyList<ModelSlowdown> Slowdowns)> providers)
    {
        var service = new List<(PriorityItem Item, double Rank)>();
        var slow = new List<(PriorityItem Item, double Rank)>();
        foreach (var (provider, status, slowdowns) in providers)
        {
            if (status is { ShouldShowCard: true })
            {
                var detail = status.Detail.Length > 0 ? status.Detail : "From the official status page.";
                var item = new PriorityItem(provider, status.Headline(provider.DisplayName), detail, null, status.Issues.FirstOrDefault()?.StartedAt)
                {
                    Link = status.Issues.FirstOrDefault(i => i.Link.Length > 0)?.Link is { } link && IsWebLink(link) ? link : status.PageUrl,
                };
                (status.State == ServiceState.Degraded ? slow : service).Add((item, status.State.Severity()));
            }
            foreach (var slowdown in slowdowns)
                slow.Add((new PriorityItem(provider, slowdown.Title, slowdown.Detail, null, null), slowdown.Factor));
        }
        return this with
        {
            Service = service.OrderByDescending(s => s.Rank).Select(s => s.Item).ToList(),
            Slow = slow.OrderByDescending(s => s.Rank).Select(s => s.Item).ToList(),
        };
    }

    private static bool IsWebLink(string link) =>
        Uri.TryCreate(link, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps;

    /// <summary>"in 2h 10m", from the dashboard's own reset text.</summary>
    private static string When(DateTimeOffset at, DateTimeOffset now)
    {
        var text = UsageFormat.ResetText(at, ResetDisplayMode.Relative, now);
        return text.StartsWith("Resets ", StringComparison.Ordinal) ? text[7..] : text;
    }

    private static IEnumerable<(string Label, UsageWindow Window)> Windows(UsageSnapshot usage)
    {
        if (usage.FiveHour is { } five) yield return ("5-hour", five);
        if (usage.Weekly is { } weekly) yield return ("weekly", weekly);
        if (usage.Monthly is { } monthly) yield return ("monthly", monthly);
    }
}
