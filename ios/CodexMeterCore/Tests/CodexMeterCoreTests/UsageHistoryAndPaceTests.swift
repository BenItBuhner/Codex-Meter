import Foundation
import XCTest
@testable import CodexMeterCore

final class UsageHistoryAndPaceTests: XCTestCase {
    func testHistoryDedupesGroupsWindowsAndCalculatesBurn() {
        let base = Date(timeIntervalSince1970: 2_000_000_000)
        let reset = base.addingTimeInterval(4 * 60 * 60)
        var history = UsageHistory.empty(.fiveHour)

        history = history.appending(
            window: window(used: 10, reset: reset),
            observedAt: base
        )
        history = history.appending(
            window: window(used: 10, reset: reset),
            observedAt: base.addingTimeInterval(2 * 60)
        )
        XCTAssertEqual(history.samples.count, 1)
        XCTAssertEqual(history.samples.first?.observedAt, base.addingTimeInterval(2 * 60))

        history = history.appending(
            window: window(used: 12, reset: reset),
            observedAt: base.addingTimeInterval(10 * 60)
        )
        history = history.appending(
            window: window(used: 20, reset: reset),
            observedAt: base.addingTimeInterval(22 * 60)
        )
        XCTAssertEqual(history.currentWindowSamples.count, 3)
        XCTAssertEqual(history.observedBurnRate, 10.0 / (20 * 60), accuracy: 0.000_000_1)

        let nextReset = base.addingTimeInterval(24 * 60 * 60)
        history = history.appending(
            window: window(used: 3, reset: nextReset),
            observedAt: base.addingTimeInterval(3 * 60 * 60)
        )
        XCTAssertEqual(history.completedWindowCount, 1)
        XCTAssertEqual(history.currentWindowSamples.map(\.usedPercent), [3])
        XCTAssertEqual(history.recentWindows(maximum: 1).count, 1)
        XCTAssertEqual(history.recentWindows(maximum: 2).map(\.count), [3, 1])

        func window(used: Int, reset: Date) -> UsageWindow {
            UsageWindow(
                usedPercent: used,
                windowSeconds: 18_000,
                resetAt: reset
            )
        }
    }

    func testHistoryIsBoundedAndCodable() throws {
        let base = Date(timeIntervalSince1970: 2_000_000_000)
        let reset = base.addingTimeInterval(10 * 24 * 60 * 60)
        var history = UsageHistory.empty(.fiveHour)
        for index in 0..<300 {
            history = history.appending(
                window: UsageWindow(
                    usedPercent: index % 100,
                    windowSeconds: 18_000,
                    resetAt: reset
                ),
                observedAt: base.addingTimeInterval(TimeInterval(index * 60))
            )
        }
        XCTAssertEqual(history.samples.count, 288)

        let encoded = try JSONEncoder().encode(history)
        XCTAssertEqual(try JSONDecoder().decode(UsageHistory.self, from: encoded), history)
    }

    func testPaceAssessmentUsesSensitivityAndRecentHistory() {
        let observedAt = Date(timeIntervalSince1970: 2_000_000_000)
        let reset = observedAt.addingTimeInterval(3 * 60 * 60)
        let current = UsageWindow(
            usedPercent: 50,
            windowSeconds: 18_000,
            resetAt: reset
        )
        let baseline = UsagePace.assess(
            window: current,
            observedAt: observedAt,
            now: observedAt
        )
        XCTAssertTrue(baseline.isAvailable)

        var history = UsageHistory.empty(.fiveHour)
        history = history.appending(
            window: UsageWindow(
                usedPercent: 30,
                windowSeconds: 18_000,
                resetAt: reset
            ),
            observedAt: observedAt.addingTimeInterval(-20 * 60)
        )
        history = history.appending(window: current, observedAt: observedAt)
        let trendAware = UsagePace.assess(
            window: current,
            history: history,
            observedAt: observedAt,
            now: observedAt
        )
        XCTAssertTrue(trendAware.isAvailable)
        XCTAssertLessThan(trendAware.estimatedRemaining, baseline.estimatedRemaining)

        let fast = UsageWindow(
            usedPercent: 90,
            windowSeconds: 18_000,
            resetAt: observedAt.addingTimeInterval(60 * 60)
        )
        XCTAssertTrue(
            UsagePace.assess(
                window: fast,
                observedAt: observedAt,
                now: observedAt,
                sensitivity: .balanced
            ).isAccelerated
        )
        XCTAssertFalse(
            UsagePace.assess(
                window: fast,
                observedAt: observedAt,
                now: observedAt,
                sensitivity: .off
            ).isAccelerated
        )
        XCTAssertFalse(
            UsagePace.assess(
                window: UsageWindow(
                    usedPercent: 1,
                    windowSeconds: 18_000,
                    resetAt: observedAt.addingTimeInterval(60 * 60)
                ),
                observedAt: observedAt,
                now: observedAt
            ).isAvailable
        )
    }

    func testMostAcceleratedWindowSelectsTheMostUrgentAssessment() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let snapshot = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: 90,
                windowSeconds: 18_000,
                resetAt: now.addingTimeInterval(60 * 60)
            ),
            weekly: UsageWindow(
                usedPercent: 20,
                windowSeconds: 604_800,
                resetAt: now.addingTimeInterval(4 * 24 * 60 * 60)
            ),
            fetchedAt: now
        )

        XCTAssertEqual(
            UsagePace.mostAcceleratedWindow(in: snapshot, now: now),
            .fiveHour
        )

        let monthlySnapshot = UsageSnapshot(
            planType: "free",
            allowed: true,
            limitReached: false,
            fiveHour: nil,
            weekly: nil,
            monthly: UsageWindow(
                usedPercent: 90,
                windowSeconds: 2_592_000,
                resetAt: now.addingTimeInterval(20 * 24 * 60 * 60)
            ),
            fetchedAt: now
        )
        XCTAssertEqual(
            UsagePace.mostAcceleratedWindow(in: monthlySnapshot, now: now),
            .monthly
        )
        XCTAssertNil(
            UsagePace.mostAcceleratedWindow(
                in: snapshot,
                now: now,
                sensitivity: .off
            )
        )
    }
}
