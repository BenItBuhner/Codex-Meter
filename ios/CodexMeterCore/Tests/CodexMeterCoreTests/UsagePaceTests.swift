import XCTest
@testable import CodexMeterCore

final class UsagePaceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 2_000_000_000)
    private let hour: TimeInterval = 3_600
    private let week: TimeInterval = 7 * 24 * 3_600

    func testFastWeeklyConsumptionIsAccelerated() {
        let fastWeekly = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(week),
            resetAt: now.addingTimeInterval(-hour + week)
        )
        let fast = UsagePace.assess(
            window: fastWeekly,
            observedAt: now,
            now: now,
            sensitivity: .balanced
        )
        XCTAssertTrue(fast.available)
        XCTAssertTrue(fast.accelerated)
        // 15% per hour → ~6h40m total
        XCTAssertEqual(fast.estimatedTotal, 400 * 60, accuracy: 60)
    }

    func testResetAfterFallbackMatchesAbsoluteReset() {
        let fallbackWeekly = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(week),
            resetAfterSeconds: Int64(week - hour)
        )
        let fallback = UsagePace.assess(
            window: fallbackWeekly,
            observedAt: now,
            now: now,
            sensitivity: .balanced
        )
        XCTAssertTrue(fallback.available)
        XCTAssertTrue(fallback.accelerated)
    }

    func testSameRateOnFiveHourIsSustainable() {
        let fiveHour: TimeInterval = 5 * hour
        let sameRate = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(fiveHour),
            resetAt: now.addingTimeInterval(-hour + fiveHour)
        )
        let sustainable = UsagePace.assess(
            window: sameRate,
            observedAt: now,
            now: now,
            sensitivity: .balanced
        )
        XCTAssertTrue(sustainable.available)
        XCTAssertFalse(sustainable.accelerated)
    }

    func testIdleTimeReducesNoise() {
        let pausedWeekly = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(week),
            resetAt: now.addingTimeInterval(3 * 24 * hour + 12 * hour)
        )
        let paused = UsagePace.assess(
            window: pausedWeekly,
            observedAt: now,
            now: now,
            sensitivity: .balanced
        )
        XCTAssertTrue(paused.available)
        XCTAssertFalse(paused.accelerated)
    }

    func testSensitivityPolicies() {
        let borderline = UsageWindow(
            usedPercent: 55,
            windowSeconds: Int64(5 * hour),
            resetAt: now.addingTimeInterval(3 * hour)
        )
        XCTAssertTrue(
            UsagePace.assess(window: borderline, observedAt: now, now: now, sensitivity: .sensitive)
                .accelerated
        )
        XCTAssertTrue(
            UsagePace.assess(window: borderline, observedAt: now, now: now, sensitivity: .balanced)
                .accelerated
        )
        XCTAssertFalse(
            UsagePace.assess(window: borderline, observedAt: now, now: now, sensitivity: .relaxed)
                .accelerated
        )
    }

    func testTinySampleThresholds() {
        let tinySample = UsageWindow(
            usedPercent: 4,
            windowSeconds: Int64(week),
            resetAt: now.addingTimeInterval(-hour + week)
        )
        XCTAssertFalse(
            UsagePace.assess(window: tinySample, observedAt: now, now: now, sensitivity: .balanced)
                .available
        )
        XCTAssertTrue(
            UsagePace.assess(window: tinySample, observedAt: now, now: now, sensitivity: .sensitive)
                .available
        )
    }

    func testMostAcceleratedWindowSelection() {
        let fiveHour: TimeInterval = 5 * hour
        let sameRateFiveHour = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(fiveHour),
            resetAt: now.addingTimeInterval(-hour + fiveHour)
        )
        let fastWeekly = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(week),
            resetAt: now.addingTimeInterval(-hour + week)
        )
        let both = UsageSnapshot(
            planType: "pro",
            allowed: true,
            limitReached: false,
            fiveHour: sameRateFiveHour,
            weekly: fastWeekly,
            fetchedAt: now
        )
        XCTAssertEqual(
            UsagePace.mostAcceleratedWindow(snapshot: both, now: now, sensitivity: .balanced),
            .weekly
        )
    }

    func testInvalidSensitivityFallsBackToBalanced() {
        XCTAssertEqual(UsagePace.normalizeSensitivity("invalid"), .balanced)
    }

    func testExpiredWindowsUnavailable() {
        let fastWeekly = UsageWindow(
            usedPercent: 15,
            windowSeconds: Int64(week),
            resetAt: now.addingTimeInterval(-hour + week)
        )
        let assessment = UsagePace.assess(
            window: fastWeekly,
            observedAt: now,
            now: fastWeekly.resetAt!,
            sensitivity: .balanced
        )
        XCTAssertFalse(assessment.available)
    }

    func testEstimatedRemainingFormatting() {
        XCTAssertEqual(UsageFormat.estimatedRemaining(.unavailable), "")
        XCTAssertEqual(UsageFormat.compactDuration(90 * 60), "1h 30m")
        XCTAssertEqual(UsageFormat.compactDuration(25 * 60), "25m")
        XCTAssertEqual(UsageFormat.compactDuration(2 * 24 * 3_600 + 3 * 3_600), "2d 3h")
    }
}
