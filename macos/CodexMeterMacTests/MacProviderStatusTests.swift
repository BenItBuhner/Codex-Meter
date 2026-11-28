import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// What the sidebar rows and the menu bar panel say about each provider.
final class MacProviderStatusTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_900_000_000)

    private func status(
        signedIn: Bool = true,
        demo: Bool = false,
        refreshing: Bool = false,
        error: String? = nil,
        snapshot: UsageSnapshot? = nil
    ) -> MacProviderStatus {
        MacProviderStatus(
            provider: .codex,
            isSignedIn: signedIn,
            isDemo: demo,
            isRefreshing: refreshing,
            plan: "Plus",
            email: "dev@example.com",
            error: error,
            snapshot: snapshot
        )
    }

    private func usage(fiveHour: Int?, weekly: Int?, monthly: Int? = nil) -> UsageSnapshot {
        UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: fiveHour.map { UsageWindow(usedPercent: $0, windowSeconds: 18_000) },
            weekly: weekly.map { UsageWindow(usedPercent: $0, windowSeconds: 604_800) },
            monthly: monthly.map { UsageWindow(usedPercent: $0, windowSeconds: 2_592_000) },
            fetchedAt: now
        )
    }

    func testMetersFollowTheDashboardOrderAndTheTightestLeads() {
        let shown = status(snapshot: usage(fiveHour: 30, weekly: 80, monthly: 10))
        XCTAssertEqual(shown.meters.map(\.id), ["five-hour", "weekly", "monthly"])
        XCTAssertEqual(shown.meters.map(\.remainingPercent), [70, 20, 90])
        XCTAssertEqual(shown.tightestMeter?.id, "weekly")
        XCTAssertEqual(shown.summary, "20% left · Weekly")
        XCTAssertTrue(shown.hasData)
    }

    func testWithoutDataTheSummarySaysWhy() {
        XCTAssertEqual(status(signedIn: false).summary, "Not signed in")
        XCTAssertEqual(status(error: "Codex is rate-limiting checks.").summary, "Codex is rate-limiting checks.")
        XCTAssertEqual(status(refreshing: true).summary, "Refreshing…")
        XCTAssertEqual(status().summary, "Waiting for data")
        // Demo data counts as connected even though nobody signed in.
        XCTAssertEqual(status(signedIn: false, demo: true).summary, "Waiting for data")
        XCTAssertTrue(status().meters.isEmpty)
        XCTAssertNil(status().tightestMeter)
    }

    func testNumbersWinOverAnOlderError() {
        // A failed refresh keeps the last reading on screen; the number stays the headline.
        XCTAssertEqual(status(error: "Offline", snapshot: usage(fiveHour: 25, weekly: nil)).summary, "75% left · 5-hour")
    }

    func testTintsTurnOrangeUnderAQuarterAndRedUnderATenth() {
        XCTAssertEqual(MacMeterLevel(remainingPercent: 100), .healthy)
        XCTAssertEqual(MacMeterLevel(remainingPercent: 25), .healthy)
        XCTAssertEqual(MacMeterLevel(remainingPercent: 24), .low)
        XCTAssertEqual(MacMeterLevel(remainingPercent: 10), .low)
        XCTAssertEqual(MacMeterLevel(remainingPercent: 9), .critical)
        XCTAssertEqual(MacMeterLevel(remainingPercent: 0), .critical)
    }

    /// Regression: Copilot, Grok, Kimi and the other added providers always read as signed out
    /// with no usage on the Mac (sidebar row, menu bar panel and menu bar item), and Refresh and
    /// opening the panel skipped them, because `macStatus` only knew the three built-in providers.
    @MainActor
    func testAddedProvidersShowTheirOwnUsageAndRefresh() async throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "codex-meter-mac-tests-\(UUID().uuidString)"))
        let model = AppModel(defaults: defaults, preview: true)
        let copilot = try XCTUnwrap(model.addedProviders[.copilot])

        XCTAssertFalse(model.macStatus(for: .copilot).hasData)
        XCTAssertEqual(model.macStatus(for: .copilot).summary, "Not signed in")
        XCTAssertFalse(model.macIsConnected(.copilot))

        copilot.enterDemo()
        let shown = model.macStatus(for: .copilot)
        XCTAssertTrue(shown.isDemo)
        XCTAssertFalse(shown.isSignedIn)
        XCTAssertEqual(shown.email, "demo@local")
        XCTAssertEqual(shown.snapshot, copilot.usage)
        XCTAssertEqual(shown.summary, "58% left · 5-hour")
        XCTAssertTrue(model.macIsConnected(.copilot))
        XCTAssertTrue(model.macConnectedProviders.contains(.copilot))

        // Usage → Refresh on its dashboard asks the provider's own model again.
        await model.macRefresh(.copilot)
        XCTAssertEqual(model.macStatus(for: .copilot).summary, "57% left · 5-hour")
    }

    @MainActor
    func testBuiltInProvidersReadTheirOwnModels() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "codex-meter-mac-tests-\(UUID().uuidString)"))
        let model = AppModel(defaults: defaults, preview: true)
        let codex = model.macStatus(for: .codex)
        XCTAssertTrue(codex.isDemo)
        XCTAssertEqual(codex.snapshot, model.usage)
        let claude = model.macStatus(for: .claudeCode)
        XCTAssertEqual(claude.isDemo, model.claude.phase == .demo)
        XCTAssertEqual(claude.snapshot, model.claude.usage)
        let cursor = model.macStatus(for: .cursor)
        XCTAssertEqual(cursor.isDemo, model.cursor.phase == .demo)
        XCTAssertEqual(cursor.snapshot, model.cursor.usage)
    }
}
