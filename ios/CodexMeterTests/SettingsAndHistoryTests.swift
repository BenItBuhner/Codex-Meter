import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

final class SettingsAndHistoryTests: XCTestCase {
    func testDashboardSettingsOrderVisibilityAndTransferRoundTrip() throws {
        let spark = "limit:codex-spark"
        var settings = AppSettings()
        settings.setDashboardSectionVisible(DashboardSections.weekly, visible: false)
        settings.setDashboardSectionVisible(spark, visible: false)
        settings.setDashboardOrder([
            DashboardSections.usageHistory,
            spark,
            DashboardSections.fiveHour,
            DashboardSections.usageHistory
        ])

        XCTAssertFalse(settings.isDashboardSectionVisible(DashboardSections.weekly))
        XCTAssertFalse(settings.isDashboardSectionVisible(spark))
        XCTAssertEqual(
            settings.dashboardOrder,
            [DashboardSections.usageHistory, spark, DashboardSections.fiveHour]
        )

        let data = try JSONEncoder().encode(settings)
        let decoded = try SettingsTransferDocument.decode(data)
        XCTAssertEqual(decoded, settings)

        var shown = decoded
        shown.setDashboardSectionVisible(spark, visible: true)
        XCTAssertTrue(shown.showAdditionalLimits)
        XCTAssertTrue(shown.isDashboardSectionVisible(spark))
        XCTAssertFalse(shown.dashboardHiddenSections.contains(spark))
    }

    func testUsageHistoryStoreRecordsPersistsAndClearsBoundedSamples() async throws {
        let fileURL = temporaryFileURL("usage-history.json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = UsageHistoryStore(fileURL: fileURL)
        let observedAt = Date(timeIntervalSince1970: 2_000_000_000)

        let first = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: 20,
                windowSeconds: 18_000,
                resetAt: observedAt.addingTimeInterval(3_600)
            ),
            weekly: UsageWindow(
                usedPercent: 40,
                windowSeconds: 604_800,
                resetAt: observedAt.addingTimeInterval(300_000)
            ),
            fetchedAt: observedAt
        )
        let recorded = try await store.record(first)
        XCTAssertEqual(recorded.fiveHour.samples.count, 1)
        XCTAssertEqual(recorded.weekly.samples.count, 1)
        XCTAssertTrue(recorded.monthly.samples.isEmpty)

        let duplicate = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: 20,
                windowSeconds: 18_000,
                resetAt: observedAt.addingTimeInterval(3_600)
            ),
            weekly: first.weekly,
            fetchedAt: observedAt.addingTimeInterval(60)
        )
        let coalesced = try await store.record(duplicate)
        XCTAssertEqual(coalesced.fiveHour.samples.count, 1)
        XCTAssertEqual(coalesced.fiveHour.samples.first?.observedAt, duplicate.fetchedAt)

        let reloaded = await UsageHistoryStore(fileURL: fileURL).load()
        XCTAssertEqual(reloaded, coalesced)

        try await store.clear()
        let cleared = await store.load()
        XCTAssertEqual(cleared, .empty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    }
}
