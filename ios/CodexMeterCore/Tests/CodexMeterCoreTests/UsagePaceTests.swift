import XCTest
@testable import CodexMeterCore

final class UsagePaceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 2_000_000_000)

    func testWeeklyProjectionAndSensitivityBoundaries() {
        let week = TimeInterval(7 * 24 * 60 * 60)
        let fast = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(week),
            resetAt: now.addingTimeInterval(-3_600 + week)
        )
        let result = UsagePace.assess(fast, observedAt: now, now: now, sensitivity: .balanced)
        XCTAssertTrue(result.isAvailable)
        XCTAssertTrue(result.isAccelerated)
        XCTAssertEqual(result.estimatedTotal, 24_000, accuracy: 1)

        let borderline = UsageWindow(
            usedPercent: 55,
            windowSeconds: 18_000,
            resetAt: now.addingTimeInterval(10_800)
        )
        XCTAssertTrue(UsagePace.assess(borderline, observedAt: now, now: now, sensitivity: .sensitive).isAccelerated)
        XCTAssertTrue(UsagePace.assess(borderline, observedAt: now, now: now, sensitivity: .balanced).isAccelerated)
        XCTAssertFalse(UsagePace.assess(borderline, observedAt: now, now: now, sensitivity: .relaxed).isAccelerated)
    }

    func testOffKeepsEstimateButSuppressesWarning() {
        let window = UsageWindow(
            usedPercent: 15,
            windowSeconds: 604_800,
            resetAt: now.addingTimeInterval(601_200)
        )
        let result = UsagePace.assess(window, observedAt: now, now: now, sensitivity: .off)
        XCTAssertTrue(result.isAvailable)
        XCTAssertFalse(result.isAccelerated)
        XCTAssertFalse(UsagePaceSensitivity.off.warningsEnabled)
    }

    func testTinyAndExpiredSamplesAreUnavailable() {
        let tiny = UsageWindow(
            usedPercent: 4,
            windowSeconds: 604_800,
            resetAt: now.addingTimeInterval(601_200)
        )
        XCTAssertFalse(UsagePace.assess(tiny, observedAt: now, now: now, sensitivity: .balanced).isAvailable)
        XCTAssertTrue(UsagePace.assess(tiny, observedAt: now, now: now, sensitivity: .sensitive).isAvailable)
        XCTAssertFalse(UsagePace.assess(tiny, observedAt: now, now: now.addingTimeInterval(601_200), sensitivity: .sensitive).isAvailable)
    }

    func testMostAcceleratedWindow() {
        let five = UsageWindow(usedPercent: 15, windowSeconds: 18_000, resetAt: now.addingTimeInterval(14_400))
        let week = UsageWindow(usedPercent: 15, windowSeconds: 604_800, resetAt: now.addingTimeInterval(601_200))
        let snapshot = UsageSnapshot(planType: "plus", allowed: true, limitReached: false, fiveHour: five, weekly: week, fetchedAt: now)
        XCTAssertEqual(UsagePace.mostAcceleratedWindow(in: snapshot, now: now, sensitivity: .balanced), .weekly)
        XCTAssertNil(UsagePace.mostAcceleratedWindow(in: snapshot, now: now, sensitivity: .off))
    }
}
