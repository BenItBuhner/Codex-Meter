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
