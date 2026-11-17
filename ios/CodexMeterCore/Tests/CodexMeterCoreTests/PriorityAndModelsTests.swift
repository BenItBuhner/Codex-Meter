import CodexMeterCore
import XCTest

final class PriorityAndModelsTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 2_000_000_000)

    private func window(used: Int, seconds: Int64, resetsIn: TimeInterval) -> UsageWindow {
        UsageWindow(usedPercent: used, windowSeconds: seconds, resetAt: now.addingTimeInterval(resetsIn))
    }

    // MARK: Priority

    func testPriorityListsOnlyWhatNeedsAttention() {
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
        let report = PriorityReport.build([
            PriorityInput(provider: .codex, usage: codex,
                          credits: ResetCreditsSnapshot(availableCount: 1, credits: [credit], fetchedAt: now)),
            PriorityInput(provider: .claudeCode, usage: claude),
            PriorityInput(provider: .cursor, usage: cursor),
        ], now: now)

        XCTAssertEqual(report.watch.map(\.title), ["Codex 5-hour"])
        XCTAssertTrue(report.watch[0].detail.hasPrefix("10% left"))
        XCTAssertEqual(report.usedUp.map(\.title), ["Claude Code 5-hour"])
        XCTAssertEqual(report.usedUp[0].detail, "Resets in 2h 0m")
        XCTAssertEqual(Set(report.comingUp.map(\.title)), ["Cursor monthly renews", "Codex reset credit expires"])
        XCTAssertEqual(report.comingUp.first?.title, "Codex reset credit expires")
        XCTAssertEqual(report.headline, "1 limit needs watching.")
        XCTAssertEqual(report.attentionCount, 2)
    }

    func testStaleAndHealthyWindowsStayOff() {
        let stale = UsageSnapshot(
            planType: "plus", allowed: true, limitReached: false,
            fiveHour: UsageWindow(usedPercent: 100, windowSeconds: 18_000, resetAt: now.addingTimeInterval(-60)),
            weekly: window(used: 5, seconds: 604_800, resetsIn: 6 * 86_400),
            fetchedAt: now.addingTimeInterval(-3_600)
        )
        let report = PriorityReport.build([PriorityInput(provider: .codex, usage: stale)], now: now)
        XCTAssertTrue(report.isEmpty)
        XCTAssertEqual(report.headline, "Nothing needs you right now. Every connected limit is on track.")
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
        XCTAssertEqual(items.map(\.title), ["New on OpenRouter: Anthropic: Claude Opus 6"])
        XCTAssertEqual(items.first?.detail, "Anthropic model, for your Claude Code")
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
