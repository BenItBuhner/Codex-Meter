import XCTest
@testable import CodexMeter
import CodexMeterCore

final class NotificationPlanningTests: XCTestCase {
    func testCreditExpiryPlanSchedulesEachLeadTimeForAvailableCredits() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let credit = RateLimitResetCredit(
            id: "credit-a",
            resetType: "rate_limit",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(48 * 3_600)
        )
        let redeemed = RateLimitResetCredit(
            id: "credit-b",
            resetType: "rate_limit",
            status: RateLimitResetCredit.redeemedStatus,
            expiresAt: now.addingTimeInterval(48 * 3_600)
        )

        let planned = CreditExpiryReminder.plan(
            credits: [credit, redeemed],
            leadTimes: [3_600, 24 * 3_600, 30], // 30s is below minimum
            now: now
        )

        XCTAssertEqual(planned.count, 2)
        XCTAssertEqual(Set(planned.map(\.creditId)), ["credit-a"])
        XCTAssertEqual(Set(planned.map(\.lead)), [3_600, 24 * 3_600])
        // Earliest trigger first (longer lead time).
        XCTAssertEqual(planned[0].lead, 24 * 3_600)
        XCTAssertEqual(
            planned[0].triggerAt,
            credit.expiresAt!.addingTimeInterval(-(24 * 3_600))
        )
        XCTAssertEqual(planned[1].lead, 3_600)
    }

    func testCreditExpiryPlanSkipsExpiredCredits() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let expired = RateLimitResetCredit(
            id: "old",
            resetType: "rate_limit",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(-60)
        )
        let planned = CreditExpiryReminder.plan(
            credits: [expired],
            leadTimes: [3_600],
            now: now
        )
        XCTAssertTrue(planned.isEmpty)
    }

    func testUnexpectedRefillDetectionRequiresPriorUsageAndFutureReset() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let previous = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: 40,
                windowSeconds: 18_000,
                resetAt: now.addingTimeInterval(3_600)
            ),
            weekly: UsageWindow(
                usedPercent: 10,
                windowSeconds: 604_800,
                resetAt: now.addingTimeInterval(86_400)
            ),
            fetchedAt: now.addingTimeInterval(-600)
        )
        let current = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: 0,
                windowSeconds: 18_000,
                resetAt: now.addingTimeInterval(18_000)
            ),
            weekly: UsageWindow(
                usedPercent: 10,
                windowSeconds: 604_800,
                resetAt: now.addingTimeInterval(86_400)
            ),
            fetchedAt: now
        )

        let mask = CelebrationDetector.detectUnexpectedRefills(
            previous: previous,
            current: current
        )
        XCTAssertEqual(mask, [.fiveHour])
    }

    func testUserResetSuppressionFiltersRefills() {
        let observed = Date(timeIntervalSince1970: 1_800_000_000)
        let filtered = CelebrationDetector.withoutUserResetRefills(
            [.fiveHour, .weekly],
            observedAt: observed,
            fiveHourSuppressUntil: observed.addingTimeInterval(3_600),
            weeklySuppressUntil: observed.addingTimeInterval(-1)
        )
        XCTAssertEqual(filtered, [.weekly])
    }

    func testResetCreditsAddedOnlyOnIncrease() {
        XCTAssertEqual(CelebrationDetector.resetCreditsAdded(previous: 1, current: 3), 2)
        XCTAssertEqual(CelebrationDetector.resetCreditsAdded(previous: 3, current: 3), 0)
        XCTAssertEqual(CelebrationDetector.resetCreditsAdded(previous: 3, current: 1), 0)
    }

    func testAlertMetricIncludesSelectedWindowsOnly() {
        XCTAssertTrue(AlertMetric.both.includes(.fiveHour))
        XCTAssertTrue(AlertMetric.both.includes(.weekly))
        XCTAssertFalse(AlertMetric.both.includes(.both))

        XCTAssertTrue(AlertMetric.fiveHour.includes(.fiveHour))
        XCTAssertFalse(AlertMetric.fiveHour.includes(.weekly))

        XCTAssertTrue(AlertMetric.weekly.includes(.weekly))
        XCTAssertFalse(AlertMetric.weekly.includes(.fiveHour))
    }

    func testMissingAppGroupMarksWidgetCacheUnavailableAndThrowsOnPublish() async {
        let cache = WidgetSnapshotCache(
            appGroupIdentifier: "group.com.bukovinafilip.CodexMeter.missing-for-tests"
        )
        XCTAssertFalse(cache.isAvailable)
        do {
            try await cache.publishSignedOut()
            XCTFail("Expected App Group storage failure")
        } catch let error as CodexServiceError {
            XCTAssertEqual(error, .storage(WidgetSnapshotCache.unavailableMessage))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testPublishSignedOutClearsStaleSnapshotWhenSaveFails() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("widget-clear-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let fileURL = directory.appendingPathComponent(SharedWidgetSnapshot.defaultFileName)
        let cache = WidgetSnapshotCache(fileURL: fileURL)
        let now = Date(timeIntervalSince1970: 1_900_000_000)
        try await cache.publish(
            mode: .live,
            usage: UsageSnapshot(
                planType: "plus",
                allowed: true,
                limitReached: false,
                fiveHour: UsageWindow(usedPercent: 40, windowSeconds: 18_000, resetAt: now.addingTimeInterval(60)),
                weekly: nil,
                fetchedAt: now
            ),
            credits: .summary(availableCount: 1, fetchedAt: now),
            now: now
        )
        let published = try await cache.load()
        XCTAssertNotNil(published)

        // Turn the snapshot path into a directory so the signed-out write fails.
        try FileManager.default.removeItem(at: fileURL)
        try FileManager.default.createDirectory(at: fileURL, withIntermediateDirectories: true)

        do {
            try await cache.publishSignedOut()
            XCTFail("Expected signed-out publish to fail")
        } catch {
            // Expected — previous authenticated snapshot must still be wiped.
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
        let afterFailure = try await cache.load()
        XCTAssertNil(afterFailure)
    }

    func testAppSettingsDecodesMissing22FieldsWithDefaults() throws {
        let legacy = """
        {
          "appearance": "system",
          "refreshOnLaunch": true,
          "refreshMinutes": 30,
          "notificationsEnabled": true,
          "alertMetric": "both",
          "alertThreshold": 25
        }
        """.data(using: .utf8)!

        let settings = try JSONDecoder().decode(AppSettings.self, from: legacy)
        XCTAssertTrue(settings.creditIncreaseAlertsEnabled)
        XCTAssertTrue(settings.unexpectedRefillAlertsEnabled)
        XCTAssertTrue(settings.creditExpiryRemindersEnabled)
        XCTAssertEqual(settings.creditExpiryLeadMinutes, AppSettings.defaultCreditExpiryLeadMinutes)
    }

    func testObsoleteExpiryIdentifiersPreserveActiveOnes() {
        let keep = NotificationDeduplication.creditExpiryIdentifier(token: "a:1:3600")
        let drop = NotificationDeduplication.creditExpiryIdentifier(token: "b:2:3600")
        let other = NotificationDeduplication.creditIdentifier(fetchedAt: .now, count: 1)
        let obsolete = NotificationDeduplication.obsoleteExpiryIdentifiers(
            among: [keep, drop, other],
            keeping: [keep]
        )
        XCTAssertEqual(obsolete, [drop])
    }
}
