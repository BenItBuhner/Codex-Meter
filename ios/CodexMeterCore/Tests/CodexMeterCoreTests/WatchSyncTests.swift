import Foundation
import XCTest
@testable import CodexMeterCore

final class WatchSyncTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_790_000_000)

    private func window(_ used: Int, _ seconds: Int64, resetIn minutes: Double) -> UsageWindow {
        UsageWindow(usedPercent: used, windowSeconds: seconds,
                    resetAt: now.addingTimeInterval(minutes * 60))
    }

    func testCodexKeepsFiveHourAndWeekly() {
        let usage = UsageSnapshot(planType: "pro", allowed: true, limitReached: false,
                                  fiveHour: window(38, 18_000, resetIn: 90),
                                  weekly: window(59, 604_800, resetIn: 4_000), fetchedAt: now)
        let summary = WatchProviderSummary(id: "codex", name: "Codex", signedIn: true,
                                           usage: usage)
        XCTAssertEqual(summary.primary(at: now)?.shortLabel, "5h")
        XCTAssertEqual(summary.primary(at: now)?.label, "5-hour")
        XCTAssertEqual(summary.secondary(at: now)?.label, "Weekly")
        XCTAssertEqual(summary.pairText(at: now), "5h 62% · Week 41%")
        XCTAssertEqual(summary.constrainedFraction(at: now), 0.41, accuracy: 0.0001)
        XCTAssertEqual(summary.plan, "Pro")
        XCTAssertEqual(summary.nextReset(after: now)?.meter.kind, .short)
    }

    func testOtherProviderShapes() {
        let daily = UsageSnapshot(planType: "", allowed: true, limitReached: false,
                                  fiveHour: window(10, 86_400, resetIn: 300), weekly: nil,
                                  monthly: window(50, 2_592_000, resetIn: 9_000), fetchedAt: now)
        let meters = WatchMeter.meters(for: daily)
        XCTAssertEqual(meters.map(\.shortLabel), ["Day", "Month"])
        XCTAssertTrue(meters[1].isLongCadence)

        let named = UsageSnapshot(planType: "", allowed: true, limitReached: false,
                                  fiveHour: nil, weekly: nil,
                                  additionalLimits: [UsageLimit(id: "premium",
                                                                name: "Premium requests",
                                                                primary: window(80, 2_592_000,
                                                                                resetIn: 900),
                                                                secondary: nil)],
                                  fetchedAt: now)
        let limit = WatchMeter.meters(for: named)
        XCTAssertEqual(limit.first?.shortLabel, "Premium req…")
        XCTAssertEqual(limit.first?.label, "Premium requests")
        XCTAssertEqual(WatchMeter.meters(for: nil), [])
    }

    func testExpiredWindowsDropAtDisplayTime() {
        let usage = UsageSnapshot(planType: "", allowed: true, limitReached: false,
                                  fiveHour: window(90, 18_000, resetIn: -5),
                                  weekly: window(20, 604_800, resetIn: 2_000), fetchedAt: now)
        let summary = WatchProviderSummary(id: "codex", name: "Codex", signedIn: true,
                                           usage: usage)
        XCTAssertEqual(summary.meters.count, 2)
        XCTAssertEqual(summary.currentMeters(at: now).map(\.kind), [.weekly])
    }

    func testSignedOutCarriesNoUsage() {
        let usage = UsageSnapshot(planType: "pro", allowed: true, limitReached: false,
                                  fiveHour: window(10, 18_000, resetIn: 60), weekly: nil,
                                  fetchedAt: now)
        let summary = WatchProviderSummary(id: "Cursor", name: " ", signedIn: false, usage: usage)
        XCTAssertEqual(summary.id, "cursor")
        XCTAssertEqual(summary.name, "cursor")
        XCTAssertTrue(summary.meters.isEmpty)
        XCTAssertEqual(summary.plan, "")
        XCTAssertEqual(summary.pairText(at: now), "Sign in on iPhone")
    }

    func testPayloadRoundTripAndResolveOrder() throws {
        let payload = WatchSyncPayload(
            providers: [
                WatchProviderSummary(id: "codex", name: "Codex", signedIn: false),
                WatchProviderSummary(id: "cursor", name: "Cursor", signedIn: true),
                WatchProviderSummary(id: "copilot", name: "Copilot", signedIn: true),
                WatchProviderSummary(id: "cursor", name: "Duplicate", signedIn: true),
            ],
            phoneSelectedID: "COPILOT",
            sentAt: now
        )
        XCTAssertEqual(payload.providers.map(\.id), ["codex", "cursor", "copilot"])
        let decoded = try WatchSyncPayload.decode(payload.encoded())
        XCTAssertEqual(decoded, payload)
        XCTAssertEqual(payload.resolve(watchChoice: "cursor")?.id, "cursor")
        XCTAssertEqual(payload.resolve(watchChoice: "gone")?.id, "copilot")
        let noPick = WatchSyncPayload(providers: payload.providers, phoneSelectedID: "",
                                      sentAt: now)
        XCTAssertEqual(noPick.resolve(watchChoice: nil)?.id, "cursor")
        XCTAssertNil(WatchSyncPayload(providers: [], phoneSelectedID: "", sentAt: now)
            .resolve(watchChoice: "codex"))
        let json = String(decoding: try payload.encoded(), as: UTF8.self)
        XCTAssertFalse(json.contains("token"))
    }

    func testStoreKeepsNewestPayloadAndChoice() throws {
        let suite = "watch-sync-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = WatchPayloadStore(defaults: defaults)
        let newer = WatchSyncPayload.demo(now: now)
        XCTAssertTrue(store.save(newer))
        let older = WatchSyncPayload(providers: [], phoneSelectedID: "",
                                     sentAt: now.addingTimeInterval(-60))
        XCTAssertFalse(store.save(older))
        XCTAssertEqual(store.load()?.providers.count, 5)
        XCTAssertEqual(store.followed()?.id, "codex")
        store.watchChoice = "claude_code"
        XCTAssertEqual(store.followed()?.name, "Claude Code")
    }
}
