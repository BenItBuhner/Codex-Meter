import CodexMeterCore
import XCTest

final class PriorityAndModelsTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 2_000_000_000)

    private func window(used: Int, seconds: Int64, resetsIn: TimeInterval) -> UsageWindow {
        UsageWindow(usedPercent: used, windowSeconds: seconds, resetAt: now.addingTimeInterval(resetsIn))
    }

    // MARK: Priority

    private let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private func build(_ inputs: [PriorityInput], events: [ProviderEvents.Event] = [], slowdowns: [PriorityItem] = [],
                       models: [PriorityItem] = [], order: [PrioritySectionKey] = PrioritySectionKey.allCases,
                       hidden: Set<PrioritySectionKey> = []) -> PriorityReport {
        PriorityReport.build(inputs, events: events, slowdowns: slowdowns, newModels: models, order: order, hidden: hidden,
                             now: now, calendar: utc, locale: Locale(identifier: "en_US_POSIX"))
    }

    private var mixed: [PriorityInput] {
        let codex = UsageSnapshot(
            planType: "plus", allowed: true, limitReached: false,
            // 90% gone one hour into five: runs out long before the reset.
            fiveHour: window(used: 90, seconds: 18_000, resetsIn: 4 * 3_600),
            weekly: window(used: 10, seconds: 604_800, resetsIn: 6 * 86_400),
            fetchedAt: now
        )
        let claude = UsageSnapshot(
            planType: "Pro", allowed: false, limitReached: true,
            fiveHour: window(used: 100, seconds: 18_000, resetsIn: 2 * 3_600),
            weekly: nil, fetchedAt: now
        )
        let cursor = UsageSnapshot(
            planType: "Pro", allowed: true, limitReached: false,
            fiveHour: nil, weekly: nil,
            monthly: window(used: 40, seconds: 30 * 86_400, resetsIn: 2 * 86_400),
            fetchedAt: now
        )
        let credit = RateLimitResetCredit(id: "c1", resetType: "rate_limit", status: RateLimitResetCredit.availableStatus,
                                          expiresAt: now.addingTimeInterval(86_400))
        return [
            PriorityInput(provider: .codex, usage: codex,
                          credits: ResetCreditsSnapshot(availableCount: 1, credits: [credit], fetchedAt: now)),
            PriorityInput(provider: .claudeCode, usage: claude),
            PriorityInput(provider: .cursor, usage: cursor),
        ]
    }

    func testPriorityListsOnlyWhatNeedsAttentionLikeAndroid() {
        let report = build(mixed)
        XCTAssertEqual(report.sections.map(\.key), [.watch, .usedUp, .comingUp])
        XCTAssertEqual(report.watch.map(\.title), ["Codex · 5-hour"])
        XCTAssertTrue(report.watch[0].detail.hasPrefix("10% left · at this pace, runs out in"), report.watch[0].detail)
        XCTAssertEqual(report.watch[0].remainingPercent, 10)
        XCTAssertEqual(report.usedUp.map(\.title), ["Claude Code · 5-hour"])
        // 2_000_000_000 is 03:33:20 UTC; two hours on is the same day.
        XCTAssertEqual(report.usedUp[0].detail, "Resets 5:33 AM")
        XCTAssertEqual(report.comingUp.map(\.title), ["Codex · 1 reset credit", "Cursor · Monthly limit"],
                       "soonest first")
        XCTAssertEqual(report.comingUp[0].detail, "Expires in 1d 0h")
        XCTAssertEqual(report.comingUp[1].detail, "Renews in 2d 0h · 60% left")
        XCTAssertEqual(report.attentionCount, 2)
        XCTAssertFalse(report.isEmpty)
    }

    func testLowButOnPaceIsNotALimitToWatch() {
        // 90% used near the end of the window: low, but it resets before it runs out.
        let late = UsageSnapshot(planType: "", allowed: true, limitReached: false,
                                 fiveHour: window(used: 90, seconds: 18_000, resetsIn: 600), weekly: nil, fetchedAt: now)
        XCTAssertTrue(build([PriorityInput(provider: .codex, usage: late)]).isEmpty,
                      "Android lists only limits running out before they reset")
    }

    func testStaleAndHealthyWindowsStayOffAndEmptyLines() {
        let stale = UsageSnapshot(
            planType: "plus", allowed: true, limitReached: false,
            fiveHour: UsageWindow(usedPercent: 100, windowSeconds: 18_000, resetAt: now.addingTimeInterval(-60)),
            weekly: window(used: 5, seconds: 604_800, resetsIn: 6 * 86_400),
            fetchedAt: now.addingTimeInterval(-3_600)
        )
        let report = build([PriorityInput(provider: .codex, usage: stale)])
        XCTAssertTrue(report.isEmpty)
        XCTAssertEqual(report.emptyLine, "Nothing needs attention right now.")
        XCTAssertEqual(build([]).emptyLine, "Sign in to a provider to see the limits to watch here.")
        XCTAssertTrue(build([PriorityInput(provider: .codex, usage: nil)]).isEmpty, "no data yet is not an alert")
    }

    func testSectionsFollowTheSavedOrderAndHideSwitchedOffOnes() {
        let report = build(mixed, order: [.comingUp, .usedUp], hidden: [.watch])
        XCTAssertEqual(report.sections.map(\.key), [.comingUp, .usedUp], "saved order first, watch hidden")
        XCTAssertTrue(report.filtered)
        XCTAssertEqual(report.sizes[.watch], 1, "Edit mode still counts what is hidden")
        XCTAssertEqual(report.sizes[.alerts], 0)
        let allHidden = build(mixed, hidden: Set(PrioritySectionKey.allCases))
        XCTAssertTrue(allHidden.isEmpty)
        XCTAssertEqual(allHidden.emptyLine, "Everything here is hidden. Tap Edit to show sections again.")
        XCTAssertFalse(build(mixed, hidden: [.models]).filtered, "hiding an empty section filters nothing")
    }

    func testSectionOrderKeepsUnknownSectionsInTheirDefaultPlace() {
        XCTAssertEqual(PrioritySectionKey.order(saved: []), PrioritySectionKey.allCases)
        XCTAssertEqual(PrioritySectionKey.order(saved: ["models", "alerts"]).map(\.rawValue),
                       ["models", "news", "watch", "used_up", "ready", "coming_up", "alerts"],
                       "a missing section goes back at its default index, as on Android")
        XCTAssertEqual(PrioritySectionKey.order(saved: ["watch", "bogus", "watch", "news"]).map(\.rawValue),
                       ["alerts", "watch", "news", "used_up", "ready", "coming_up", "models"],
                       "unknown keys and repeats are dropped")
        XCTAssertEqual(PrioritySectionKey.allCases.map(\.label), [
            "Outages and slowdowns", "Recent activity", "Limits to watch", "Used up",
            "Saved resets you can use", "Coming up", "New models",
        ])
    }

    func testOutagesComeFirstMostSevereFirstThenSlowdowns() {
        let down = ProviderStatus(provider: "claude_code", state: .majorOutage,
                                  issues: [.init(component: "Claude Code", state: .majorOutage, title: "Errors")],
                                  pageURL: "https://status.claude.com", checkedAt: now)
        let slow = ProviderStatus(provider: "cursor", state: .degraded,
                                  issues: [.init(component: "IDE", state: .degraded, title: "Slow tabs")],
                                  pageURL: "https://status.cursor.com", checkedAt: now)
        let fine = ProviderStatus(provider: "codex", state: .operational, pageURL: "https://status.openai.com", checkedAt: now)
        let report = build([
            PriorityInput(provider: .cursor, usage: nil, status: slow),
            PriorityInput(provider: .codex, usage: nil, status: fine),
            PriorityInput(provider: .claudeCode, usage: nil, status: down),
        ], slowdowns: [PriorityItem(provider: .codex, title: "GPT is slow", detail: "31 tokens/s")])
        let alerts = report.items(.alerts)
        XCTAssertEqual(alerts.map(\.title), ["Claude Code: major outage", "IDE: degraded performance", "GPT is slow"])
        XCTAssertEqual(alerts.map(\.eyebrow), ["Official incident", "Performance warning", "Performance warning"])
        XCTAssertEqual(alerts[0].detail, "Errors")
        XCTAssertEqual(alerts[0].link?.absoluteString, "https://status.claude.com")
        XCTAssertEqual(report.attentionCount, 3)
    }

    func testRecentActivityIsDismissibleAndOnlyForConnectedProviders() throws {
        let refill = try XCTUnwrap(ProviderEvents.surpriseRefill(.codex, labels: ["Weekly"], at: now.addingTimeInterval(-32 * 60)))
        let stranger = try XCTUnwrap(ProviderEvents.surpriseRefill(.warp, labels: ["Monthly"], at: now))
        let old = try XCTUnwrap(ProviderEvents.surpriseRefill(.codex, labels: ["5-hour"], at: now.addingTimeInterval(-2 * 86_400)))
        let report = build([PriorityInput(provider: .codex, usage: nil)], events: [refill, stranger, old])
        let news = report.items(.news)
        XCTAssertEqual(news.map(\.title), ["Weekly refilled early"])
        XCTAssertEqual(news.first?.detail, "Codex · 32m ago")
        XCTAssertEqual(news.first?.dismissKey, "event:" + refill.key)
        XCTAssertFalse(report.sections[0].offersDismissAll, "Dismiss all only for more than one row")
    }

    func testSavedResetsYouCanUseNeedAUsedUpLimitAndAUsableReset() {
        let usedUp = UsageSnapshot(planType: "", allowed: false, limitReached: true,
                                   fiveHour: window(used: 100, seconds: 18_000, resetsIn: 3_600), weekly: nil, fetchedAt: now)
        let fine = UsageSnapshot(planType: "", allowed: true, limitReached: false,
                                 fiveHour: window(used: 10, seconds: 18_000, resetsIn: 3_600), weekly: nil, fetchedAt: now)
        func saved(usable: Bool) -> ClaudeSavedResets {
            ClaudeSavedResets(eligible: true, grants: [
                .init(id: "g_1", resetsTotal: 2, resetsLeft: 2, endsAt: now.addingTimeInterval(10 * 86_400),
                      usableNow: usable, type: "full"),
            ], fetchedAt: now)
        }
        func input(_ usage: UsageSnapshot, usable: Bool) -> PriorityInput {
            let resets = saved(usable: usable)
            return PriorityInput(provider: .claudeCode, usage: usage, credits: resets.resetCreditsSnapshot(at: now),
                                 savedResets: resets)
        }
        let ready = build([input(usedUp, usable: true)]).items(.ready)
        XCTAssertEqual(ready.map(\.title), ["Claude Code · 2 Full resets"])
        XCTAssertEqual(ready.first?.detail, "Ready to use")
        XCTAssertTrue(build([input(usedUp, usable: false)]).items(.ready).isEmpty, "a reset that can't be used now")
        XCTAssertTrue(build([input(fine, usable: true)]).items(.ready).isEmpty, "nothing used up")
        let codexCredits = ResetCreditsSnapshot(availableCount: 2, credits: [], fetchedAt: now)
        let codex = build([PriorityInput(provider: .codex, usage: usedUp, credits: codexCredits)]).items(.ready)
        XCTAssertEqual(codex.map(\.title), ["Codex · 2 reset credits"])
    }

    func testUsedUpOrderAndResetClockFormats() {
        let soon = UsageSnapshot(planType: "", allowed: false, limitReached: true,
                                 fiveHour: window(used: 100, seconds: 18_000, resetsIn: 3_600), weekly: nil, fetchedAt: now)
        let later = UsageSnapshot(planType: "", allowed: false, limitReached: true, fiveHour: nil,
                                  weekly: window(used: 100, seconds: 604_800, resetsIn: 3 * 86_400), fetchedAt: now)
        let unknown = UsageSnapshot(planType: "", allowed: false, limitReached: true, fiveHour: nil,
                                    weekly: UsageWindow(usedPercent: 100, windowSeconds: 604_800), fetchedAt: now)
        let report = build([
            PriorityInput(provider: .cursor, usage: unknown),
            PriorityInput(provider: .codex, usage: later),
            PriorityInput(provider: .claudeCode, usage: soon),
        ])
        XCTAssertEqual(report.usedUp.map(\.title), ["Claude Code · 5-hour", "Codex · Weekly", "Cursor · Weekly"])
        XCTAssertEqual(report.usedUp.last?.detail, "Reset time unknown")
        let posix = Locale(identifier: "en_US_POSIX")
        XCTAssertEqual(PriorityReport.resetClock(now.addingTimeInterval(86_400), now: now, calendar: utc, locale: posix),
                       "Resets tomorrow 3:33 AM")
        XCTAssertEqual(PriorityReport.resetClock(now.addingTimeInterval(3 * 86_400), now: now, calendar: utc, locale: posix),
                       "Resets Sat 3:33 AM")
        XCTAssertEqual(PriorityReport.resetClock(now.addingTimeInterval(9 * 86_400), now: now, calendar: utc, locale: posix),
                       "Resets May 27")
        XCTAssertEqual(PriorityReport.resetClock(now.addingTimeInterval(3_600), now: now, calendar: utc, locale: posix,
                                                 uses24HourClock: true), "Resets 04:33")
    }

    func testProvidersOwnShortCadenceNamesTheLimit() {
        let kimi = UsageSnapshot(planType: "", allowed: false, limitReached: true,
                                 fiveHour: window(used: 100, seconds: 86_400, resetsIn: 3_600), weekly: nil, fetchedAt: now)
        XCTAssertEqual(build([PriorityInput(provider: .kimi, usage: kimi)]).usedUp.map(\.title), ["Kimi Code · Daily"])
    }

    func testMonthlyRenewalIsListedOncePerProvider() {
        let both = UsageSnapshot(planType: "", allowed: true, limitReached: false, fiveHour: nil,
                                 weekly: nil, monthly: window(used: 20, seconds: 30 * 86_400, resetsIn: 86_400),
                                 additionalLimits: [], fetchedAt: now)
        XCTAssertEqual(build([PriorityInput(provider: .cursor, usage: both)]).comingUp.count, 1)
        let usedUpMonthly = UsageSnapshot(planType: "", allowed: false, limitReached: true, fiveHour: nil, weekly: nil,
                                          monthly: window(used: 100, seconds: 30 * 86_400, resetsIn: 86_400), fetchedAt: now)
        let report = build([PriorityInput(provider: .cursor, usage: usedUpMonthly)])
        XCTAssertTrue(report.comingUp.isEmpty, "a used-up monthly limit is under Used up, not Coming up")
        XCTAssertEqual(report.usedUp.count, 1)
    }

    // MARK: New models

    private func catalog(_ extra: [(String, String, Double)]) -> Data {
        var models: [[String: Any]] = (0..<60).map { ["id": "filler/model-\($0)", "name": "Filler \($0)", "created": 1_000] }
        for (id, name, created) in extra {
            models.append(["id": id, "name": name, "created": created])
        }
        return (try? JSONSerialization.data(withJSONObject: ["data": models])) ?? Data()
    }

    func testDiscoveryNeedsABaselineAndTwoSightings() throws {
        var state = ModelDiscoveryState()
        let first = ModelDiscovery.observe(state, catalog: ModelDiscovery.parseCatalog(catalog([])), now: now)
        XCTAssertTrue(first.state.baseline)
        XCTAssertTrue(first.confirmed.isEmpty)
        state = first.state

        let listed = now.timeIntervalSince1970 - 86_400
        let body = catalog([
            ("anthropic/claude-opus-6", "Anthropic: Claude Opus 6", listed),
            ("anthropic/claude-opus-6:beta", "Anthropic: Claude Opus 6 (beta)", listed),
            ("openai/gpt-3", "OpenAI: GPT-3", 1_000),
        ])
        let second = ModelDiscovery.observe(state, catalog: ModelDiscovery.parseCatalog(body), now: now)
        XCTAssertTrue(second.confirmed.isEmpty, "one sighting is not enough")
        let third = ModelDiscovery.observe(second.state, catalog: ModelDiscovery.parseCatalog(body), now: now.addingTimeInterval(60))
        XCTAssertEqual(third.confirmed.map(\.id), ["anthropic/claude-opus-6"], "variants and old models are not announced")

        let items = ModelDiscovery.items(third.state, signedIn: [.cursor, .claudeCode], now: now.addingTimeInterval(120))
        XCTAssertEqual(items.map(\.title), ["Anthropic: Claude Opus 6"])
        XCTAssertEqual(items.first?.detail, "For Claude Code")
        XCTAssertEqual(items.first?.dismissKey, "model:anthropic/claude-opus-6")
        let dismissed = ModelDiscovery.dismiss(third.state, keys: items.compactMap(\.dismissKey) + ["event:x"])
        XCTAssertTrue(ModelDiscovery.items(dismissed, signedIn: [.claudeCode], now: now.addingTimeInterval(120)).isEmpty,
                      "a dismissed model stays gone")
        let again = ModelDiscovery.observe(dismissed, catalog: ModelDiscovery.parseCatalog(body), now: now.addingTimeInterval(180))
        XCTAssertEqual(again.state.dismissed, ["anthropic/claude-opus-6"], "a later catalog keeps the dismissal")
        let legacy = try JSONDecoder().decode(ModelDiscoveryState.self, from: Data(#"{"baseline":true,"models":{}}"#.utf8))
        XCTAssertTrue(legacy.baseline && legacy.dismissed.isEmpty, "state saved before dismissing still loads")
        XCTAssertTrue(ModelDiscovery.items(third.state, signedIn: [.cursor], now: now).isEmpty, "Cursor adds no makers")
        XCTAssertTrue(ModelDiscovery.note(items).hasPrefix("Shown for Claude Code."))

        let partial = ModelDiscovery.observe(third.state, catalog: [], now: now)
        XCTAssertEqual(partial.state, third.state, "a partial fetch changes nothing")
    }

    func testDiscoveryPrefersTheMakersOwnSubscription() {
        XCTAssertEqual(ModelDiscovery.matchedBy("openai/gpt-7", signedIn: [.openrouter, .codex]), .codex)
        XCTAssertEqual(ModelDiscovery.matchedBy("mistral/large", signedIn: [.openrouter, .codex]), .openrouter)
        XCTAssertNil(ModelDiscovery.matchedBy("mistral/large", signedIn: [.codex, .copilot]))
        XCTAssertEqual(ModelDiscovery.reason(.openrouter), "for your OpenRouter")
    }

    // MARK: Model speed

    private func endpoints(tps: Any, latency: Any, uptime: Any = 99.5) -> Data {
        let body: [String: Any] = ["data": [
            "id": "anthropic/claude-opus-5.5",
            "name": "Anthropic: Claude Opus 5.5",
            "endpoints": [
                ["provider_name": "Google Vertex", "tag": "google-vertex", "throughput_last_30m": 80, "latency_last_30m": 900],
                ["provider_name": "Anthropic", "tag": "anthropic", "throughput_last_30m": tps,
                 "latency_last_30m": latency, "uptime_last_30m": uptime],
            ],
        ]]
        return (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
    }

    func testSpeedPrefersTheMakersEndpointAndNeedsHistory() throws {
        let speed = try XCTUnwrap(ModelSpeed.parse(endpoints(tps: ["p50": 20], latency: 800)))
        XCTAssertEqual(speed.modelName, "Claude Opus 5.5")
        XCTAssertEqual(speed.featured?.provider, "Anthropic")
        XCTAssertEqual(speed.featured?.throughput, 20)
        let usual = (0..<8).map { ModelSpeed.Sample(at: now.addingTimeInterval(Double(-$0) * 2_700), throughput: 60, latencyMs: 700) }
        XCTAssertNil(ModelSpeed.slowdown(speed, earlier: Array(usual.prefix(3))), "too little history")
        let slow = try XCTUnwrap(ModelSpeed.slowdown(speed, earlier: usual))
        XCTAssertEqual(slow.title, "Claude Opus 5.5 is running slower than usual")
        XCTAssertTrue(slow.detail.hasPrefix("20 tokens/s, usually 60"))
        XCTAssertEqual(slow.factor, 3, accuracy: 0.001)

        let normal = try XCTUnwrap(ModelSpeed.parse(endpoints(tps: 55, latency: 750)))
        XCTAssertNil(ModelSpeed.slowdown(normal, earlier: usual))
        let slowStart = try XCTUnwrap(ModelSpeed.parse(endpoints(tps: 58, latency: 2_100)))
        XCTAssertEqual(ModelSpeed.slowdown(slowStart, earlier: usual)?.title, "Claude Opus 5.5 is slow to start responding")
        let failing = try XCTUnwrap(ModelSpeed.parse(endpoints(tps: 60, latency: 700, uptime: 72)))
        XCTAssertEqual(ModelSpeed.slowdown(failing, earlier: [])?.title, "Claude Opus 5.5 is failing more often than usual")
        let missing = try XCTUnwrap(ModelSpeed.parse(endpoints(tps: NSNull(), latency: NSNull(), uptime: NSNull())))
        XCTAssertEqual(missing.featured?.provider, "Google Vertex", "an endpoint without figures is never featured")
    }

    func testSpeedModelListAndIds() {
        let body: [String: Any] = ["data": [
            ["id": "openai/gpt-6", "name": "OpenAI: GPT-6", "created": 10],
            ["id": "openai/gpt-6.1", "name": "OpenAI: GPT-6.1", "created": 20],
            ["id": "openai/gpt-6.1:free", "name": "OpenAI: GPT-6.1 (free)", "created": 30],
            ["id": "anthropic/claude", "name": "Anthropic: Claude", "created": 40],
        ]]
        let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
        XCTAssertEqual(ModelSpeed.models(by: "openai", in: data).map(\.id), ["openai/gpt-6.1", "openai/gpt-6"])
        XCTAssertTrue(ModelSpeed.isSafeModelID("anthropic/claude-opus-5.5"))
        XCTAssertFalse(ModelSpeed.isSafeModelID("../etc/passwd"))
        XCTAssertNil(ModelSpeed.endpointsURL("openai/gpt 6"))
        XCTAssertEqual(ModelSpeed.author(for: .cursor), nil)
    }
}
