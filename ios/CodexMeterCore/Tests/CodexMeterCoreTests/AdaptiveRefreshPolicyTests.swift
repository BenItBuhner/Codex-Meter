import Foundation
import XCTest
@testable import CodexMeterCore

final class AdaptiveRefreshPolicyTests: XCTestCase {
    func testQuotaAttentionResetQuietHoursAndFailureBackoff() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let healthy = UsageWindow(
            usedPercent: 5,
            windowSeconds: 18_000,
            resetAt: now.addingTimeInterval(4 * 60 * 60)
        )
        let medium = UsageWindow(
            usedPercent: 60,
            windowSeconds: 18_000,
            resetAt: now.addingTimeInterval(3 * 60 * 60)
        )
        let low = UsageWindow(
            usedPercent: 92,
            windowSeconds: 18_000,
            resetAt: now.addingTimeInterval(2 * 60 * 60)
        )
        let nearReset = UsageWindow(
            usedPercent: 20,
            windowSeconds: 18_000,
            resetAt: now.addingTimeInterval(10 * 60)
        )

        XCTAssertEqual(choose(nil), 30)
        XCTAssertEqual(choose(snapshot(healthy), hour: 12), 60)
        XCTAssertEqual(choose(snapshot(medium), hour: 12), 15)
        XCTAssertEqual(choose(snapshot(low), hour: 12), 5)
        XCTAssertEqual(choose(snapshot(healthy), attention: 3, hour: 12), 10)
        XCTAssertEqual(choose(snapshot(healthy), hour: 3), 120)
        XCTAssertEqual(choose(snapshot(nearReset), hour: 12), 5)
        XCTAssertEqual(choose(snapshot(low), hour: 12, failures: 2), 15)
        XCTAssertEqual(
            choose(snapshot(low, allowed: false, limited: true), hour: 12, failures: 3),
            30
        )

        func choose(
            _ usage: UsageSnapshot?,
            attention: Double = 0,
            hour: Int = 12,
            failures: Int = 0
        ) -> Int {
            AdaptiveRefreshPolicy.chooseMinutes(
                snapshot: usage,
                attentionScore: attention,
                localHour: hour,
                consecutiveFailures: failures,
                now: now
            )
        }

        func snapshot(
            _ window: UsageWindow,
            allowed: Bool = true,
            limited: Bool = false
        ) -> UsageSnapshot {
            UsageSnapshot(
                planType: "plus",
                allowed: allowed,
                limitReached: limited,
                fiveHour: window,
                weekly: nil,
                fetchedAt: now
            )
        }
    }
}
