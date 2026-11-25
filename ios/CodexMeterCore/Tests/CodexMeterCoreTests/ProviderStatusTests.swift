@testable import CodexMeterCore
import XCTest

/// The same cases as Android's ProviderStatusSelfTest, on the same recorded feeds
/// (android/tests/fixtures/status): official components only, unknown is never an outage, RSS,
/// and the polling policy.
final class ProviderStatusTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_794_000_000)

    private func fixture(_ name: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("android/tests/fixtures/status/\(name)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func parse(_ provider: UsageProvider, _ body: String?) throws -> ProviderStatus {
        try XCTUnwrap(ProviderStatusSources.parse(provider, body: body, now: now))
    }

    func testRealFeedsReadOperational() throws {
        let codex = try parse(.codex, fixture("openai-summary.json"))
        XCTAssertEqual(codex.state, .operational)
        XCTAssertFalse(codex.shouldShowCard)
        XCTAssertEqual(try parse(.claudeCode, fixture("claude-summary.json")).state, .operational)
        XCTAssertEqual(try parse(.cursor, fixture("cursor-compat-summary.json")).state, .operational)
        let grok = try parse(.grok, fixture("xai-feed.xml"))
        XCTAssertEqual(grok.state, .noActiveIncidents, "RSS with only resolved items says no active incidents")
        XCTAssertEqual(grok.headline("Grok"), "Grok: no active incidents reported")
    }

    private func claudeWith(api: String, code: String, incidents: String) -> String {
        """
        {"page":{"name":"Claude"},"components":[{"name":"claude.ai","status":"operational"},\
        {"name":"Claude API (api.anthropic.com)","status":"\(api)"},{"name":"Claude Code","status":"\(code)"}],\
        "incidents":\(incidents),"scheduled_maintenances":[]}
        """
    }

    func testClaudeAPIOutageIsNotClaudeCode() throws {
        let status = try parse(.claudeCode, claudeWith(api: "major_outage", code: "operational", incidents: """
            [{"name":"API errors","status":"investigating","impact":"critical",\
            "components":[{"name":"Claude API (api.anthropic.com)","status":"major_outage"}]}]
            """))
        XCTAssertEqual(status.state, .operational, "an API outage must not mark Claude Code down")
        XCTAssertTrue(status.issues.isEmpty)
    }

    func testClaudeCodeIncidentNamesTheComponent() throws {
        let status = try parse(.claudeCode, claudeWith(api: "operational", code: "degraded_performance", incidents: """
            [{"name":"Elevated errors in Claude Code","status":"identified","impact":"minor",\
            "created_at":"2026-11-07T01:00:00.000Z","shortlink":"https://stspg.io/abc",\
            "components":[{"name":"Claude Code","status":"degraded_performance"}]}]
            """))
        XCTAssertEqual(status.state, .degraded)
        XCTAssertTrue(status.shouldShowCard)
        XCTAssertEqual(status.headline("Claude Code"), "Claude Code: degraded performance")
        XCTAssertEqual(status.detail, "Elevated errors in Claude Code")
        XCTAssertEqual(status.issues.count, 1, "one issue per component")
        XCTAssertNotNil(status.issues.first?.startedAt, "incident start parsed")
        XCTAssertEqual(status.link?.absoluteString, "https://stspg.io/abc", "the incident's own page")
    }

    func testMajorOutageOutranksAndNamesMore() throws {
        let body = """
            {"page":{"name":"Cursor"},"components":[{"name":"IDE","status":"degraded_performance"},\
            {"name":"CLI","status":"major_outage"},{"name":"Dashboard","status":"major_outage"}],"incidents":[]}
            """
        let status = try parse(.cursor, body)
        XCTAssertEqual(status.state, .majorOutage)
        XCTAssertEqual(status.headline("Cursor"), "CLI and 1 more: major outage", "the worst component is named first")
        XCTAssertEqual(status.link?.absoluteString, "https://status.cursor.com")
    }

    func testResolvedUnlinkedAndRecoveredIncidentsAreIgnored() throws {
        let status = try parse(.claudeCode, claudeWith(api: "operational", code: "operational", incidents: """
            [{"name":"Old","status":"resolved","impact":"major","components":[{"name":"Claude Code","status":"operational"}]},\
            {"name":"Billing","status":"investigating","impact":"major","components":[]},\
            {"name":"Watching","status":"monitoring","impact":"none","components":[{"name":"Claude Code","status":"operational"}]}]
            """))
        XCTAssertEqual(status.state, .operational)
    }

    func testOnlyMaintenanceInProgressCounts() throws {
        let body = """
            {"page":{"name":"OpenAI"},"components":[{"name":"Codex in ChatGPT Desktop","status":"operational"}],\
            "incidents":[],"scheduled_maintenances":[\
            {"name":"Planned","status":"scheduled","impact":"maintenance","components":[{"name":"Codex in ChatGPT Desktop"}]},\
            {"name":"Upgrade","status":"in_progress","impact":"maintenance","components":[{"name":"Codex in ChatGPT Desktop"}]}]}
            """
        let status = try parse(.codex, body)
        XCTAssertEqual(status.state, .maintenance)
        XCTAssertEqual(status.detail, "Upgrade", "scheduled but not started is ignored")
        XCTAssertEqual(status.headline("Codex"), "Codex in ChatGPT Desktop: maintenance in progress")
    }

    func testGarbageIsUnknownNotAnOutage() throws {
        for body in [nil, "", "<html>blocked</html>", "{}", #"{"page":{"name":"Claude"}}"#] as [String?] {
            let status = try parse(.claudeCode, body)
            XCTAssertEqual(status.state, .unknown, "unreadable feed is unknown: \(body ?? "nil")")
            XCTAssertFalse(status.shouldShowCard)
            XCTAssertEqual(status.headline("Claude Code"), "Claude Code: status unknown")
            XCTAssertEqual(status.detail, "Couldn't read the official status page.")
        }
        XCTAssertEqual(try parse(.grok, "not rss").state, .unknown)
    }

    func testUnlistedProviderAndWrongPageAreUnknown() throws {
        let body = #"{"page":{"name":"Claude"},"components":[{"name":"claude.ai","status":"major_outage"}],"incidents":[]}"#
        XCTAssertEqual(try parse(.claudeCode, body).state, .unknown, "a feed without Claude Code is unknown")
        let wrongPage = try fixture("cursor-compat-summary.json")
            .replacingOccurrences(of: #""name":"Cursor""#, with: #""name":"SpaceXAI""#)
        XCTAssertEqual(try parse(.cursor, wrongPage).state, .unknown, "a status.x.ai page that isn't Cursor's")
    }

    private func rss(_ items: String...) -> String {
        "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>SpaceXAI System Status</title>"
            + items.joined() + "</channel></rss>"
    }

    private func item(_ title: String, _ categories: String...) -> String {
        "<item><title>\(title)</title><link>https://status.x.ai/x/1</link><pubDate>Sat, 07 Nov 2026 01:00:00 GMT</pubDate>"
            + categories.map { "<category>\($0)</category>" }.joined() + "</item>"
    }

    func testRSSActiveGrokIncident() throws {
        let status = try parse(.grok, rss(
            item("[Grok Bot] Grok Bot is currently degraded", "disruption", "investigating"),
            item("[grok.com] Login errors", "outage", "identified"),
            item("[Grok Bot] Old issue", "outage", "resolved")))
        XCTAssertEqual(status.state, .partialOutage)
        XCTAssertEqual(status.issues.count, 2)
        XCTAssertEqual(status.headline("Grok"), "grok.com and 1 more: partial outage")
        XCTAssertEqual(status.detail, "Login errors")
        XCTAssertNotNil(status.issues.first?.startedAt, "pubDate parsed")
        XCTAssertEqual(status.link?.absoluteString, "https://status.x.ai/x/1")
    }

    func testRSSOtherComponentsDontAffectGrokAndEntitiesDecode() throws {
        XCTAssertEqual(try parse(.grok, rss(
            item("[Cloud Agents] Agents failing", "outage", "investigating"),
            item("[Grok (iOS)] App crash", "disruption", "investigating"))).state, .noActiveIncidents)
        let cdata = try parse(.grok, rss(item("<![CDATA[[Grok Bot] Replies &amp; images slow]]>", "disruption")))
        XCTAssertEqual(cdata.detail, "Replies & images slow")
        XCTAssertEqual(try parse(.grok, rss(item("[Grok Bot] Heads up", "info"))).state, .noActiveIncidents,
                       "an info item is not a problem")
        XCTAssertEqual(try parse(.grok, rss(item("[Grok Bot] Work", "maintenance"))).state, .maintenance)
    }

    func testOnlyVerifiedFeedsAreListed() {
        XCTAssertNil(ProviderStatusSources.source(for: .antigravity), "Gemini or Cloud status is not Antigravity's")
        XCTAssertNil(ProviderStatusSources.parse(.antigravity, body: "{}", now: now))
        XCTAssertNil(ProviderStatusSources.source(for: .copilot))
        for provider in UsageProvider.allCases {
            guard let source = ProviderStatusSources.source(for: provider) else { continue }
            XCTAssertTrue(ProviderStatusSources.hosts.contains(URL(string: source.feedURL)?.host ?? ""),
                          "\(provider) asks only an official host")
        }
    }

    func testRoundTrip() throws {
        let status = try parse(.grok, rss(item("[Grok Bot] Slow replies", "disruption", "investigating")))
        let back = try JSONDecoder().decode(ProviderStatus.self, from: JSONEncoder().encode(status))
        XCTAssertEqual(back, status)
        XCTAssertEqual(back.headline("Grok"), status.headline("Grok"))
        XCTAssertEqual(back.pageURL, "https://status.x.ai")
    }

    func testPollPolicy() {
        let foreground = StatusPollPolicy.foregroundInterval
        XCTAssertTrue(StatusPollPolicy.shouldFetch(now: now, lastAttempt: nil, failures: 0, retryAfterUntil: nil,
                                                   foreground: true, random01: 0.5), "first fetch is immediate")
        XCTAssertFalse(StatusPollPolicy.shouldFetch(now: now + foreground - 1, lastAttempt: now, failures: 0,
                                                    retryAfterUntil: nil, foreground: true, random01: 0))
        XCTAssertTrue(StatusPollPolicy.shouldFetch(now: now + foreground, lastAttempt: now, failures: 0,
                                                   retryAfterUntil: nil, foreground: true, random01: 0))
        XCTAssertFalse(StatusPollPolicy.shouldFetch(now: now + foreground * 1.1, lastAttempt: now, failures: 0,
                                                    retryAfterUntil: nil, foreground: true, random01: 0.9), "jitter")
        XCTAssertFalse(StatusPollPolicy.shouldFetch(now: now + 29 * 60, lastAttempt: now, failures: 0,
                                                    retryAfterUntil: nil, foreground: false, random01: 0))
        XCTAssertEqual(StatusPollPolicy.nextAllowed(lastAttempt: now, failures: 2, retryAfterUntil: nil, foreground: true,
                                                    random01: 0).timeIntervalSince(now), 4 * foreground)
        XCTAssertEqual(StatusPollPolicy.nextAllowed(lastAttempt: now, failures: 30, retryAfterUntil: nil, foreground: true,
                                                    random01: 0).timeIntervalSince(now), StatusPollPolicy.maxBackoff)
        let retry = now + 40 * 60
        XCTAssertEqual(StatusPollPolicy.nextAllowed(lastAttempt: now, failures: 0, retryAfterUntil: retry,
                                                    foreground: true, random01: 0), retry)
    }

    func testRetryAfter() throws {
        XCTAssertEqual(StatusPollPolicy.retryAfterUntil("120", now: now), now + 120)
        XCTAssertEqual(StatusPollPolicy.retryAfterUntil("999999", now: now), now + StatusPollPolicy.maxBackoff)
        XCTAssertNil(StatusPollPolicy.retryAfterUntil("soon", now: now))
        XCTAssertNil(StatusPollPolicy.retryAfterUntil(nil, now: now))
        let base = try XCTUnwrap(ProviderStatus.parseRFC1123("Sat, 07 Nov 2026 00:30:00 GMT"))
        XCTAssertEqual(StatusPollPolicy.retryAfterUntil("Sat, 07 Nov 2026 01:00:00 GMT", now: base),
                       ProviderStatus.parseRFC1123("Sat, 07 Nov 2026 01:00:00 GMT"))
    }
}
