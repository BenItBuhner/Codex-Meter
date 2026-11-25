import Foundation

/// A provider's service status, read from its official status feed and limited to the components
/// that provider depends on (Claude Code, not the Claude API; Grok Bot, not the whole SpaceXAI
/// feed). A port of Android's and the desktop apps' `ProviderStatus`, with the same rules:
///
/// - No feed, a failed fetch, or a feed that doesn't list the provider is `unknown`, never an outage.
/// - One degraded component never reads as the whole provider being down: the state is the worst
///   matching component, and the copy names the component.
/// - An incident feed without component states (RSS) can only say "no active incidents reported",
///   not "operational".
/// - Official incidents are kept apart from the app's own failed requests.
public struct ProviderStatus: Codable, Sendable, Equatable {
    public enum State: String, Codable, Sendable, CaseIterable {
        /// Every matching component reports operational.
        case operational
        /// An incident-only feed (RSS) has no unresolved incident for the provider's components.
        case noActiveIncidents = "no_active_incidents"
        /// Maintenance in progress on a matching component.
        case maintenance
        case degraded
        case partialOutage = "partial_outage"
        case majorOutage = "major_outage"
        /// No feed, no data, or the feed doesn't list this provider. Never shown as an outage.
        case unknown

        public var severity: Int {
            switch self {
            case .operational, .noActiveIncidents: 0
            case .maintenance: 1
            case .degraded: 2
            case .partialOutage: 3
            case .majorOutage: 4
            case .unknown: -1
            }
        }

        public var isProblem: Bool { severity >= 1 }
    }

    /// One affected component or incident, as the feed names it.
    public struct Issue: Codable, Sendable, Equatable {
        public let component: String
        public let state: State
        /// Incident title, or "" when the feed only gave a component state.
        public let title: String
        public let link: String
        public let startedAt: Date?

        public init(component: String, state: State, title: String = "", link: String = "", startedAt: Date? = nil) {
            self.component = component
            self.state = state
            self.title = title
            self.link = link
            self.startedAt = startedAt
        }
    }

    public let provider: String
    public let state: State
    public let issues: [Issue]
    /// The status page people can open, e.g. https://status.claude.com.
    public let pageURL: String
    public let checkedAt: Date

    public init(provider: String, state: State, issues: [Issue] = [], pageURL: String, checkedAt: Date) {
        self.provider = provider
        self.state = state
        self.issues = issues
        self.pageURL = pageURL
        self.checkedAt = checkedAt
    }

    public static func unknown(_ provider: String, pageURL: String, now: Date) -> ProviderStatus {
        ProviderStatus(provider: provider, state: .unknown, pageURL: pageURL, checkedAt: now)
    }

    /// True when the dashboard and Priority should show it (an official problem, not "unknown").
    public var shouldShowCard: Bool { state.isProblem }

    /// The card's title, naming the component so one component never reads as the whole provider.
    public func headline(_ providerName: String) -> String {
        var place = issues.first.map { $0.component.isEmpty ? providerName : $0.component } ?? providerName
        if issues.count > 1 { place += " and \(issues.count - 1) more" }
        switch state {
        case .majorOutage: return place + ": major outage"
        case .partialOutage: return place + ": partial outage"
        case .degraded: return place + ": degraded performance"
        case .maintenance: return place + ": maintenance in progress"
        case .operational: return providerName + ": operational"
        case .noActiveIncidents: return providerName + ": no active incidents reported"
        case .unknown: return providerName + ": status unknown"
        }
    }

    /// One line under the headline: the incident title when there is one.
    public var detail: String {
        if let titled = issues.first(where: { !$0.title.isEmpty }) { return titled.title }
        return state == .unknown ? "Couldn't read the official status page." : ""
    }

    /// The incident's own page when the feed gave one, else the status page.
    public var link: URL? {
        let incident = issues.first { !$0.link.isEmpty }?.link
        return URL(string: incident ?? pageURL)
    }

    // MARK: Statuspage summary.json (OpenAI, Claude, Cursor)

    /// Reads a Statuspage-compatible `/api/v2/summary.json`. Only components whose name contains
    /// one of `componentNames` (ignoring case) count. An unresolved incident that lists a matching
    /// component raises the state and supplies the title; incidents that list no components are
    /// ignored, since they can't be tied to this provider.
    public static func parseSummary(_ provider: String, body: String?, componentNames: [String],
                                    pageURL: String, now: Date) -> ProviderStatus {
        guard let root = object(body), let components = root["components"] as? [Any] else {
            return unknown(provider, pageURL: pageURL, now: now)
        }
        var issues: [Issue] = []
        var matched = false
        var worst = State.operational
        for case let component as [String: Any] in components {
            if component["group"] as? Bool == true { continue }
            let name = string(component["name"])
            guard matches(name, componentNames) else { continue }
            matched = true
            let state = componentState(string(component["status"]))
            if state == .unknown { continue }
            if state.isProblem { issues.append(Issue(component: name, state: state)) }
            if state.severity > worst.severity { worst = state }
        }
        guard matched else { return unknown(provider, pageURL: pageURL, now: now) }
        addIncidents(root["incidents"], componentNames, &issues, maintenance: false)
        addIncidents(root["scheduled_maintenances"], componentNames, &issues, maintenance: true)
        for issue in issues where issue.state.severity > worst.severity { worst = issue.state }
        return ProviderStatus(provider: provider, state: worst, issues: mergeByComponent(sortWorstFirst(issues)),
                              pageURL: pageURL, checkedAt: now)
    }

    private static func addIncidents(_ value: Any?, _ names: [String], _ out: inout [Issue], maintenance: Bool) {
        guard let incidents = value as? [Any] else { return }
        for case let incident as [String: Any] in incidents {
            let status = string(incident["status"]).lowercased()
            if ["resolved", "postmortem", "completed"].contains(status)
                || (maintenance && status != "in_progress" && status != "verifying") {
                continue
            }
            guard let affected = incident["components"] as? [Any] else { continue }
            let incidentState = maintenance ? State.maintenance : impactState(string(incident["impact"]))
            for entry in affected {
                let component = entry as? [String: Any]
                let name = string(component?["name"])
                guard matches(name, names) else { continue }
                let listed: State = component.map { Self.componentState(string($0["status"])) } ?? .unknown
                let use = listed.severity > incidentState.severity ? listed : incidentState
                // "monitoring" with impact none: the component is back; nothing to show.
                guard use.severity >= State.maintenance.severity else { continue }
                let started = string(incident["started_at"]).isEmpty ? incident["created_at"] : incident["started_at"]
                out.append(Issue(component: name, state: use, title: string(incident["name"]),
                                 link: string(incident["shortlink"]), startedAt: parseISO(string(started))))
            }
        }
    }

    static func componentState(_ status: String) -> State {
        switch status.lowercased() {
        case "operational": .operational
        case "degraded_performance": .degraded
        case "partial_outage": .partialOutage
        case "major_outage": .majorOutage
        case "under_maintenance": .maintenance
        default: .unknown
        }
    }

    static func impactState(_ impact: String) -> State {
        switch impact.lowercased() {
        case "critical": .majorOutage
        case "major": .partialOutage
        case "minor": .degraded
        case "maintenance": .maintenance
        default: .operational
        }
    }

    // MARK: SpaceXAI RSS (status.x.ai/feed.xml)

    /// Reads the SpaceXAI incident feed. Each item's title starts with the component in brackets
    /// ("[Grok Bot] …") and carries a severity category (outage, disruption, info) and a status
    /// category (resolved when over). Unresolved items for a matching component are the issues;
    /// with none the state is `noActiveIncidents`, because the feed lists incidents only.
    public static func parseRSS(_ provider: String, body: String?, componentNames: [String],
                                pageURL: String, now: Date) -> ProviderStatus {
        guard let body, body.contains("<rss") else { return unknown(provider, pageURL: pageURL, now: now) }
        var issues: [Issue] = []
        for item in captures("<item>(.*?)</item>", in: body) {
            guard let rawTitle = captures("<title>(.*?)</title>", in: item).first,
                  let bracket = bracketed(unescape(rawTitle)) else { continue }
            let component = bracket.component
            guard componentNames.contains(where: { $0.caseInsensitiveCompare(component) == .orderedSame }) else { continue }
            var resolved = false
            var state = State.degraded
            for category in captures("<category>(.*?)</category>", in: item) {
                switch category.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
                case "resolved", "completed", "postmortem": resolved = true
                case "outage": state = .partialOutage
                case "maintenance": state = .maintenance
                case "info": state = .noActiveIncidents
                default: break
                }
            }
            if resolved || !state.isProblem { continue }
            let link = captures("<link>(.*?)</link>", in: item).first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let date = captures("<pubDate>(.*?)</pubDate>", in: item).first
                .flatMap { parseRFC1123($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
            issues.append(Issue(component: component, state: state, title: bracket.rest, link: link, startedAt: date))
        }
        var worst = State.noActiveIncidents
        for issue in issues where issue.state.severity > worst.severity { worst = issue.state }
        return ProviderStatus(provider: provider, state: worst, issues: mergeByComponent(sortWorstFirst(issues)),
                              pageURL: pageURL, checkedAt: now)
    }

    // MARK: Helpers

    static func matches(_ name: String, _ wanted: [String]) -> Bool {
        let lower = name.lowercased()
        return wanted.contains { !$0.isEmpty && lower.contains($0.lowercased()) }
    }

    private static func sortWorstFirst(_ issues: [Issue]) -> [Issue] {
        // A stable sort, like Java's Collections.sort.
        issues.enumerated().sorted { a, b in
            a.element.state.severity != b.element.state.severity
                ? a.element.state.severity > b.element.state.severity : a.offset < b.offset
        }.map(\.element)
    }

    /// Keeps the worst issue per component, so one incident on one component reads once.
    private static func mergeByComponent(_ sorted: [Issue]) -> [Issue] {
        var out: [Issue] = []
        for issue in sorted {
            if let index = out.firstIndex(where: { $0.component.caseInsensitiveCompare(issue.component) == .orderedSame }) {
                let existing = out[index]
                if existing.title.isEmpty && !issue.title.isEmpty {
                    out[index] = Issue(component: existing.component, state: existing.state, title: issue.title,
                                       link: issue.link, startedAt: issue.startedAt)
                }
            } else {
                out.append(issue)
            }
        }
        return out
    }

    private static func object(_ body: String?) -> [String: Any]? {
        guard let data = body?.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func string(_ value: Any?) -> String {
        value as? String ?? ""
    }

    private static func captures(_ pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).compactMap { match in
            Range(match.range(at: 1), in: text).map { String(text[$0]) }
        }
    }

    private static func bracketed(_ title: String) -> (component: String, rest: String)? {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("["), let close = trimmed.firstIndex(of: "]") else { return nil }
        let component = trimmed[trimmed.index(after: trimmed.startIndex)..<close]
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !component.isEmpty else { return nil }
        let rest = trimmed[trimmed.index(after: close)...].trimmingCharacters(in: .whitespacesAndNewlines)
        return (component, rest)
    }

    private static func unescape(_ value: String) -> String {
        var text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("<![CDATA["), text.hasSuffix("]]>") {
            text = String(text.dropFirst(9).dropLast(3))
        }
        return text.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&apos;", with: "'").replacingOccurrences(of: "&amp;", with: "&")
    }

    static func parseISO(_ value: String) -> Date? {
        guard !value.isEmpty else { return nil }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }
        return ISO8601DateFormatter().date(from: value)
    }

    public static func parseRFC1123(_ value: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: value)
    }
}

/// Which official status feed each provider uses, and which of its components count. Only
/// first-party feeds are listed; nothing is scraped. Providers without an official feed have no
/// status. Same table as Android's `ProviderStatusSources`:
///
/// - Codex: OpenAI's page has no plain "Codex" component, only "Codex in ChatGPT Desktop"; any
///   component naming Codex counts.
/// - Claude Code: only Claude Code, not the Claude API or claude.ai.
/// - Cursor: status.cursor.com redirects to status.x.ai's compat summary, whose page is named
///   "Cursor"; the page name is checked before it's trusted.
/// - Grok: the SpaceXAI RSS incident feed's Grok components.
/// - Antigravity: Google has no Antigravity status, and Gemini or Cloud status is not it.
public enum ProviderStatusSources {
    public enum Format: Sendable { case statuspageJSON, rss }

    public struct Source: Sendable, Equatable {
        public let feedURL: String
        public let pageURL: String
        public let format: Format
        /// For JSON, the page name the feed must report, or "" to skip the check.
        public let expectedPageName: String
        /// Component names to match (substring for JSON, exact bracket name for RSS).
        public let components: [String]
    }

    /// The only hosts a status request may end up on.
    public static let hosts: Set<String> = ["status.openai.com", "status.claude.com", "status.x.ai"]

    public static func source(for provider: UsageProvider) -> Source? {
        switch provider {
        case .codex:
            Source(feedURL: "https://status.openai.com/api/v2/summary.json", pageURL: "https://status.openai.com",
                   format: .statuspageJSON, expectedPageName: "OpenAI", components: ["Codex"])
        case .claudeCode:
            Source(feedURL: "https://status.claude.com/api/v2/summary.json", pageURL: "https://status.claude.com",
                   format: .statuspageJSON, expectedPageName: "Claude", components: ["Claude Code"])
        case .cursor:
            Source(feedURL: "https://status.x.ai/api/compat/summary.json", pageURL: "https://status.cursor.com",
                   format: .statuspageJSON, expectedPageName: "Cursor",
                   components: ["IDE", "CLI", "Cloud Agents", "Third-party models"])
        case .grok:
            Source(feedURL: "https://status.x.ai/feed.xml", pageURL: "https://status.x.ai", format: .rss,
                   expectedPageName: "",
                   components: ["Grok Bot", "grok.com", "Grok (Build)", "Global (api.x.ai)", "US (us.api.x.ai)"])
        default:
            nil
        }
    }

    /// Parses a fetched body for `provider`; anything unexpected is "status unknown". Nil for a
    /// provider with no feed.
    public static func parse(_ provider: UsageProvider, body: String?, now: Date) -> ProviderStatus? {
        guard let source = source(for: provider) else { return nil }
        if source.format == .rss {
            return ProviderStatus.parseRSS(provider.rawValue, body: body, componentNames: source.components,
                                           pageURL: source.pageURL, now: now)
        }
        if !source.expectedPageName.isEmpty, !pageNameMatches(body, source.expectedPageName) {
            return ProviderStatus.unknown(provider.rawValue, pageURL: source.pageURL, now: now)
        }
        return ProviderStatus.parseSummary(provider.rawValue, body: body, componentNames: source.components,
                                           pageURL: source.pageURL, now: now)
    }

    static func pageNameMatches(_ body: String?, _ expected: String) -> Bool {
        guard let data = body?.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let page = root["page"] as? [String: Any], let name = page["name"] as? String else { return false }
        return name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(expected) == .orderedSame
    }
}

/// When to ask a status feed again: about every 5 minutes while the app is open, every 30 minutes
/// from background work, with jitter, exponential backoff after failures, and the feed's
/// Retry-After honoured. Feeds are shared by several providers (Cursor and Grok both live on
/// status.x.ai), so callers key this by feed URL. Same numbers as Android's `StatusPollPolicy`.
public enum StatusPollPolicy {
    public static let foregroundInterval: TimeInterval = 5 * 60
    public static let backgroundInterval: TimeInterval = 30 * 60
    public static let maxBackoff: TimeInterval = 2 * 3_600
    /// Up to this share of the interval is added at random.
    public static let jitterFraction = 0.2

    /// Earliest time the feed may be asked again. `random01` is a number in [0, 1) for jitter.
    public static func nextAllowed(lastAttempt: Date?, failures: Int, retryAfterUntil: Date?, foreground: Bool,
                                   random01: Double) -> Date {
        guard let lastAttempt else { return retryAfterUntil ?? .distantPast }
        let base = foreground ? foregroundInterval : backgroundInterval
        var interval = base
        if failures > 0 {
            let shift = min(failures, 6)
            interval = min(maxBackoff, max(base, foregroundInterval * Double(1 << shift)))
        }
        let r = min(max(random01, 0), 0.999_999)
        let next = lastAttempt.addingTimeInterval(interval + (interval * jitterFraction * r).rounded(.down))
        guard let retryAfterUntil else { return next }
        return max(next, retryAfterUntil)
    }

    public static func shouldFetch(now: Date, lastAttempt: Date?, failures: Int, retryAfterUntil: Date?,
                                   foreground: Bool, random01: Double) -> Bool {
        now >= nextAllowed(lastAttempt: lastAttempt, failures: failures, retryAfterUntil: retryAfterUntil,
                           foreground: foreground, random01: random01)
    }

    /// A Retry-After header (seconds, or an HTTP date) as an absolute time, or nil when missing or
    /// unreadable. Capped at the longest backoff so a bad header can't silence status for days.
    public static func retryAfterUntil(_ header: String?, now: Date) -> Date? {
        guard let value = header?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        let until: Date?
        if let seconds = Int64(value) {
            until = now.addingTimeInterval(TimeInterval(max(0, seconds)))
        } else {
            until = ProviderStatus.parseRFC1123(value)
        }
        guard let until else { return nil }
        return min(until, now.addingTimeInterval(maxBackoff))
    }
}
