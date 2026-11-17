using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Models;

/// <summary>A provider's official service state. Severity orders them; Unknown is never an outage.</summary>
public enum ServiceState
{
    /// <summary>Every matching component reports operational.</summary>
    Operational,
    /// <summary>An incident-only feed (RSS) has no unresolved incident for the provider's components.</summary>
    NoActiveIncidents,
    Maintenance,
    Degraded,
    PartialOutage,
    MajorOutage,
    /// <summary>No feed, no data, or the feed doesn't list this provider.</summary>
    Unknown,
}

public static class ServiceStates
{
    public static int Severity(this ServiceState state) => state switch
    {
        ServiceState.Operational or ServiceState.NoActiveIncidents => 0,
        ServiceState.Maintenance => 1,
        ServiceState.Degraded => 2,
        ServiceState.PartialOutage => 3,
        ServiceState.MajorOutage => 4,
        _ => -1,
    };

    public static bool IsProblem(this ServiceState state) => state.Severity() >= 1;
}

/// <summary>One affected component or incident, as the feed names it.</summary>
public sealed record StatusIssue(string Component, ServiceState State, string Title = "", string Link = "", DateTimeOffset? StartedAt = null);

/// <summary>
/// A provider's service status, read from its official status feed and limited to the components
/// that provider depends on (Claude Code, not the Claude API). A port of the Android
/// <c>ProviderStatus</c>, with the same rules: no feed, a failed fetch, or a feed that doesn't list
/// the provider is <see cref="ServiceState.Unknown"/>, never an outage; one degraded component never
/// reads as the whole provider being down; an RSS incident feed can only say "no active incidents
/// reported"; and official incidents stay apart from the app's own failed requests.
/// </summary>
public sealed record ProviderStatus(string ProviderId, ServiceState State, IReadOnlyList<StatusIssue> Issues, string PageUrl, DateTimeOffset CheckedAt)
{
    public static ProviderStatus Unknown(string providerId, string pageUrl, DateTimeOffset now) =>
        new(providerId, ServiceState.Unknown, [], pageUrl, now);

    /// <summary>True when the dashboard should show a status card (an official problem, not "unknown").</summary>
    public bool ShouldShowCard => State.IsProblem();

    /// <summary>Card title, naming the component so one component never reads as the whole provider.</summary>
    public string Headline(string providerName)
    {
        var where = Issues.Count == 0 || Issues[0].Component.Length == 0 ? providerName : Issues[0].Component;
        if (Issues.Count > 1) where += $" and {Issues.Count - 1} more";
        return State switch
        {
            ServiceState.MajorOutage => where + ": major outage",
            ServiceState.PartialOutage => where + ": partial outage",
            ServiceState.Degraded => where + ": degraded performance",
            ServiceState.Maintenance => where + ": maintenance in progress",
            ServiceState.Operational => providerName + ": operational",
            ServiceState.NoActiveIncidents => providerName + ": no active incidents reported",
            _ => providerName + ": status unknown",
        };
    }

    /// <summary>One line under the headline: the incident title when there is one.</summary>
    public string Detail =>
        Issues.FirstOrDefault(i => i.Title.Length > 0)?.Title
        ?? (State == ServiceState.Unknown ? "Couldn't read the official status page." : "");

    // ---- Statuspage-style summary.json (OpenAI, Claude, Cursor) ---------------------------------

    /// <summary>
    /// Reads a Statuspage-compatible <c>/api/v2/summary.json</c>. Only components whose name contains
    /// one of <paramref name="componentNames"/> count. An unresolved incident listing a matching
    /// component raises the state and supplies the title; incidents that list no components are
    /// ignored, since they can't be tied to this provider.
    /// </summary>
    public static ProviderStatus ParseSummary(string providerId, string? body, IReadOnlyList<string> componentNames, string pageUrl, DateTimeOffset now)
    {
        var root = Json.TryParseObject(body);
        if (Json.Arr(Json.Get(root, "components")) is not { } components) return Unknown(providerId, pageUrl, now);
        var issues = new List<StatusIssue>();
        var matched = false;
        var worst = ServiceState.Operational;
        foreach (var node in components)
        {
            if (Json.Obj(node) is not { } component || Json.Bool(Json.Get(component, "group"), false)) continue;
            var name = Json.Str(Json.Get(component, "name"));
            if (!Matches(name, componentNames)) continue;
            matched = true;
            var state = ComponentState(Json.Str(Json.Get(component, "status")));
            if (state == ServiceState.Unknown) continue;
            if (state.IsProblem()) issues.Add(new StatusIssue(name, state));
            if (state.Severity() > worst.Severity()) worst = state;
        }
        if (!matched) return Unknown(providerId, pageUrl, now);
        AddIncidents(Json.Arr(Json.Get(root, "incidents")), componentNames, issues, maintenance: false);
        AddIncidents(Json.Arr(Json.Get(root, "scheduled_maintenances")), componentNames, issues, maintenance: true);
        foreach (var issue in issues)
        {
            if (issue.State.Severity() > worst.Severity()) worst = issue.State;
        }
        return new ProviderStatus(providerId, worst, MergeByComponent(SortWorstFirst(issues)), pageUrl, now);
    }

    private static void AddIncidents(JsonArray? incidents, IReadOnlyList<string> names, List<StatusIssue> output, bool maintenance)
    {
        if (incidents is null) return;
        foreach (var node in incidents)
        {
            if (Json.Obj(node) is not { } incident) continue;
            var status = Json.Str(Json.Get(incident, "status")).ToLowerInvariant();
            if (status is "resolved" or "postmortem" or "completed"
                || (maintenance && status is not ("in_progress" or "verifying"))) continue;
            if (Json.Arr(Json.Get(incident, "components")) is not { } affected) continue;
            var state = maintenance ? ServiceState.Maintenance : ImpactState(Json.Str(Json.Get(incident, "impact")));
            foreach (var item in affected)
            {
                var component = Json.Obj(item);
                var name = Json.Str(Json.Get(component, "name"));
                if (!Matches(name, names)) continue;
                var componentState = component is null ? ServiceState.Unknown : ComponentState(Json.Str(Json.Get(component, "status")));
                var use = componentState.Severity() > state.Severity() ? componentState : state;
                // "monitoring" with impact none: the component is back; nothing to show.
                if (use.Severity() < ServiceState.Maintenance.Severity()) continue;
                var started = Json.Str(Json.Get(incident, "started_at"));
                if (started.Length == 0) started = Json.Str(Json.Get(incident, "created_at"));
                output.Add(new StatusIssue(name, use, Json.Str(Json.Get(incident, "name")), Json.Str(Json.Get(incident, "shortlink")), ParseIso(started)));
            }
        }
    }

    public static ServiceState ComponentState(string? status) => (status ?? "").ToLowerInvariant() switch
    {
        "operational" => ServiceState.Operational,
        "degraded_performance" => ServiceState.Degraded,
        "partial_outage" => ServiceState.PartialOutage,
        "major_outage" => ServiceState.MajorOutage,
        "under_maintenance" => ServiceState.Maintenance,
        _ => ServiceState.Unknown,
    };

    public static ServiceState ImpactState(string? impact) => (impact ?? "").ToLowerInvariant() switch
    {
        "critical" => ServiceState.MajorOutage,
        "major" => ServiceState.PartialOutage,
        "minor" => ServiceState.Degraded,
        "maintenance" => ServiceState.Maintenance,
        _ => ServiceState.Operational,
    };

    // ---- SpaceXSI RSS (status.x.ai/feed.xml) ----------------------------------------------------

    private static readonly Regex Item = new("<item>(.*?)</item>", RegexOptions.Singleline | RegexOptions.CultureInvariant);
    private static readonly Regex TitleTag = new("<title>(.*?)</title>", RegexOptions.Singleline | RegexOptions.CultureInvariant);
    private static readonly Regex LinkTag = new("<link>(.*?)</link>", RegexOptions.Singleline | RegexOptions.CultureInvariant);
    private static readonly Regex PubDate = new("<pubDate>(.*?)</pubDate>", RegexOptions.Singleline | RegexOptions.CultureInvariant);
    private static readonly Regex Category = new("<category>(.*?)</category>", RegexOptions.Singleline | RegexOptions.CultureInvariant);
    private static readonly Regex Bracket = new(@"^\s*\[([^\]]+)\]\s*(.*)$", RegexOptions.Singleline | RegexOptions.CultureInvariant);

    /// <summary>
    /// Reads the SpaceXSI incident feed. Each title starts with the component in brackets
    /// ("[Grok Bot] …") and carries a severity category (outage, disruption, info) and a status
    /// category (resolved when over). With no unresolved item the state is
    /// <see cref="ServiceState.NoActiveIncidents"/>, because the feed lists incidents only.
    /// </summary>
    public static ProviderStatus ParseRss(string providerId, string? body, IReadOnlyList<string> componentNames, string pageUrl, DateTimeOffset now)
    {
        if (body is null || !body.Contains("<rss", StringComparison.Ordinal)) return Unknown(providerId, pageUrl, now);
        var issues = new List<StatusIssue>();
        foreach (Match items in Item.Matches(body))
        {
            var item = items.Groups[1].Value;
            var title = TitleTag.Match(item);
            if (!title.Success) continue;
            var bracket = Bracket.Match(Unescape(title.Groups[1].Value));
            if (!bracket.Success) continue;
            var component = bracket.Groups[1].Value.Trim();
            if (!componentNames.Any(n => string.Equals(n, component, StringComparison.OrdinalIgnoreCase))) continue;
            var resolved = false;
            var state = ServiceState.Degraded;
            foreach (Match category in Category.Matches(item))
            {
                switch (category.Groups[1].Value.Trim().ToLowerInvariant())
                {
                    case "resolved" or "completed" or "postmortem": resolved = true; break;
                    case "outage": state = ServiceState.PartialOutage; break;
                    case "maintenance": state = ServiceState.Maintenance; break;
                    case "info": state = ServiceState.NoActiveIncidents; break;
                }
            }
            if (resolved || !state.IsProblem()) continue;
            var link = LinkTag.Match(item);
            var date = PubDate.Match(item);
            issues.Add(new StatusIssue(component, state, bracket.Groups[2].Value.Trim(),
                link.Success ? link.Groups[1].Value.Trim() : "", date.Success ? ParseRfc1123(date.Groups[1].Value.Trim()) : null));
        }
        var worst = ServiceState.NoActiveIncidents;
        foreach (var issue in issues)
        {
            if (issue.State.Severity() > worst.Severity()) worst = issue.State;
        }
        return new ProviderStatus(providerId, worst, MergeByComponent(SortWorstFirst(issues)), pageUrl, now);
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    public static bool Matches(string? name, IReadOnlyList<string>? wanted) =>
        name is not null && wanted is not null && wanted.Any(w => w.Length > 0 && name.Contains(w, StringComparison.OrdinalIgnoreCase));

    private static List<StatusIssue> SortWorstFirst(List<StatusIssue> issues) =>
        [.. issues.OrderByDescending(i => i.State.Severity())];

    /// <summary>Keeps the worst issue per component, so one incident on one component reads once.</summary>
    private static List<StatusIssue> MergeByComponent(List<StatusIssue> sorted)
    {
        var output = new List<StatusIssue>();
        foreach (var issue in sorted)
        {
            var index = output.FindIndex(o => string.Equals(o.Component, issue.Component, StringComparison.OrdinalIgnoreCase));
            if (index < 0) output.Add(issue);
            else if (output[index].Title.Length == 0 && issue.Title.Length > 0)
                output[index] = output[index] with { Title = issue.Title, Link = issue.Link, StartedAt = issue.StartedAt };
        }
        return output;
    }

    private static string Unescape(string value)
    {
        var text = value.Trim();
        if (text.StartsWith("<![CDATA[", StringComparison.Ordinal) && text.EndsWith("]]>", StringComparison.Ordinal)) text = text[9..^3];
        return text.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", "\"")
            .Replace("&#39;", "'").Replace("&apos;", "'").Replace("&amp;", "&");
    }

    public static DateTimeOffset? ParseIso(string? value) =>
        DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at) ? at : null;

    public static DateTimeOffset? ParseRfc1123(string? value) =>
        DateTimeOffset.TryParseExact(value?.Replace(" GMT", " +0000").Replace(" UTC", " +0000"), "ddd, dd MMM yyyy HH:mm:ss zzz",
            CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at)
        || DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out at) ? at : null;
}

/// <summary>Which official feed a provider uses, and which of its components count.</summary>
public enum StatusFeedFormat { StatuspageJson, Rss }

public sealed record StatusSource(string FeedUrl, string PageUrl, StatusFeedFormat Format, string ExpectedPageName, IReadOnlyList<string> Components);

/// <summary>
/// Official status feeds, as on Android (<c>ProviderStatusSources</c>). First-party feeds only; no
/// scraping of status pages or third-party outage sites. Codex counts any OpenAI component naming
/// Codex; Claude Code counts only Claude Code (not the API or claude.ai); Cursor's
/// status.cursor.com summary now redirects to status.x.ai's compat feed named "Cursor", so that is
/// requested directly and the page name is still checked; Grok reads SpaceXSI's RSS incident feed.
/// Antigravity has no dedicated feed (Google Cloud or Gemini status isn't Antigravity status).
/// </summary>
public static class ProviderStatusSources
{
    private static readonly StatusSource CodexSource = new("https://status.openai.com/api/v2/summary.json", "https://status.openai.com",
        StatusFeedFormat.StatuspageJson, "OpenAI", ["Codex"]);
    private static readonly StatusSource ClaudeSource = new("https://status.claude.com/api/v2/summary.json", "https://status.claude.com",
        StatusFeedFormat.StatuspageJson, "Claude", ["Claude Code"]);
    private static readonly StatusSource CursorSource = new("https://status.x.ai/api/compat/summary.json", "https://status.cursor.com",
        StatusFeedFormat.StatuspageJson, "Cursor", ["IDE", "CLI", "Cloud Agents", "Third-party models"]);
    private static readonly StatusSource GrokSource = new("https://status.x.ai/feed.xml", "https://status.x.ai", StatusFeedFormat.Rss, "",
        ["Grok Bot", "grok.com", "Grok (Build)", "Global (api.x.ai)", "US (us.api.x.ai)"]);

    /// <summary>Hosts a feed may answer from (after redirects).</summary>
    public static readonly IReadOnlySet<string> Hosts = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "status.openai.com", "status.claude.com", "status.x.ai", "status.cursor.com" };

    public static StatusSource? For(UsageProvider? provider) => provider?.Id switch
    {
        "codex" => CodexSource,
        "claude_code" => ClaudeSource,
        "cursor" => CursorSource,
        "grok" => GrokSource,
        _ => null,
    };

    /// <summary>Parses a fetched body for <paramref name="provider"/>; anything unexpected is "status unknown".</summary>
    public static ProviderStatus? Parse(UsageProvider provider, string? body, DateTimeOffset now)
    {
        if (For(provider) is not { } source) return null;
        if (source.Format == StatusFeedFormat.Rss) return ProviderStatus.ParseRss(provider.Id, body, source.Components, source.PageUrl, now);
        if (source.ExpectedPageName.Length > 0 && !PageNameMatches(body, source.ExpectedPageName))
            return ProviderStatus.Unknown(provider.Id, source.PageUrl, now);
        return ProviderStatus.ParseSummary(provider.Id, body, source.Components, source.PageUrl, now);
    }

    public static bool PageNameMatches(string? body, string expected) =>
        Json.Obj(Json.Get(Json.TryParseObject(body), "page")) is { } page
        && string.Equals(Json.Str(Json.Get(page, "name")).Trim(), expected, StringComparison.OrdinalIgnoreCase);
}

/// <summary>
/// When to ask a status feed again, as on Android (<c>StatusPollPolicy</c>): about every 5 minutes
/// while the window is open and 30 from the tray, with jitter, exponential backoff after failures,
/// and Retry-After honoured. Cursor and Grok share status.x.ai, so this is keyed by feed URL.
/// </summary>
public static class StatusPollPolicy
{
    public static readonly TimeSpan ForegroundInterval = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan BackgroundInterval = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan MaxBackoff = TimeSpan.FromHours(2);
    public const double JitterFraction = 0.2;

    public static DateTimeOffset NextAllowedAt(DateTimeOffset? lastAttempt, int consecutiveFailures, DateTimeOffset? retryAfterUntil,
        bool foreground, double random01)
    {
        if (lastAttempt is not { } last) return retryAfterUntil ?? DateTimeOffset.MinValue;
        var interval = foreground ? ForegroundInterval : BackgroundInterval;
        if (consecutiveFailures > 0)
        {
            var backoff = ForegroundInterval * (1 << Math.Min(consecutiveFailures, 6));
            interval = backoff > MaxBackoff ? MaxBackoff : backoff < interval ? interval : backoff;
        }
        var jitter = interval * JitterFraction * Math.Clamp(random01, 0, 0.999999);
        var next = last + interval + jitter;
        return retryAfterUntil is { } until && until > next ? until : next;
    }

    public static bool ShouldFetch(DateTimeOffset now, DateTimeOffset? lastAttempt, int consecutiveFailures, DateTimeOffset? retryAfterUntil,
        bool foreground, double random01) => now >= NextAllowedAt(lastAttempt, consecutiveFailures, retryAfterUntil, foreground, random01);

    /// <summary>A Retry-After delay or date as an absolute time, capped at the longest backoff; null when missing or unreadable.</summary>
    public static DateTimeOffset? RetryAfterUntil(TimeSpan? delta, DateTimeOffset? date, DateTimeOffset now)
    {
        DateTimeOffset? until = delta is { } d ? now + (d < TimeSpan.Zero ? TimeSpan.Zero : d) : date;
        if (until is not { } u) return null;
        return u > now + MaxBackoff ? now + MaxBackoff : u;
    }
}
