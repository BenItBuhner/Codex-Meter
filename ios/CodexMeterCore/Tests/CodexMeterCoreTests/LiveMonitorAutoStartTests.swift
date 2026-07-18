import XCTest
@testable import CodexMeterCore

final class LiveMonitorAutoStartTests: XCTestCase {
    func testDisabledNeverStarts() {
        let window = UsageWindow(usedPercent: 90, windowSeconds: 18_000, resetAt: Date().addingTimeInterval(3_600))
        XCTAssertFalse(
            LiveMonitorAutoStart.shouldStart(
                enabled: false,
                metric: "both",
                threshold: 25,
                fiveHour: window,
                weekly: nil
            )
        )
    }

    func testThresholdOnFiveHourOnly() {
        let high = UsageWindow(usedPercent: 10, windowSeconds: 18_000, resetAt: Date().addingTimeInterval(3_600)) // 90% remaining
        let low = UsageWindow(usedPercent: 80, windowSeconds: 18_000, resetAt: Date().addingTimeInterval(3_600)) // 20% remaining
        XCTAssertFalse(
            LiveMonitorAutoStart.shouldStart(
                enabled: true,
                metric: "both",
                threshold: 25,
                fiveHour: high,
                weekly: high
            )
        )
        XCTAssertTrue(
            LiveMonitorAutoStart.shouldStart(
                enabled: true,
                metric: "fiveHour",
                threshold: 25,
                fiveHour: low,
                weekly: high
            )
        )
        XCTAssertFalse(
            LiveMonitorAutoStart.shouldStart(
                enabled: true,
                metric: "weekly",
                threshold: 25,
                fiveHour: low,
                weekly: high
            )
        )
    }

    func testContentStateFromUsage() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let usage = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(usedPercent: 40, windowSeconds: 18_000, resetAt: now.addingTimeInterval(3_600)),
            weekly: UsageWindow(usedPercent: 70, windowSeconds: 604_800, resetAt: now.addingTimeInterval(86_400)),
            resetCreditsAvailable: 2,
            fetchedAt: now
        )
        let state = UsageLiveMonitorState.from(
            usage: usage,
            creditCount: 2,
            planLabel: "Plus",
            isDemo: true,
            isCached: false,
            now: now
        )
        XCTAssertEqual(state.fiveHourRemainingPercent, 60)
        XCTAssertEqual(state.weeklyRemainingPercent, 30)
        XCTAssertEqual(state.creditCount, 2)
        XCTAssertEqual(state.planLabel, "Plus")
        XCTAssertTrue(state.isDemo)
        XCTAssertEqual(state.primaryRemainingPercent, 30)
    }

    func testWindowTokenStable() {
        let a = Date(timeIntervalSince1970: 100)
        let b = Date(timeIntervalSince1970: 200)
        XCTAssertEqual(
            LiveMonitorAutoStart.windowToken(fiveHourReset: a, weeklyReset: b),
            LiveMonitorAutoStart.windowToken(fiveHourReset: a, weeklyReset: b)
        )
        XCTAssertNotEqual(
            LiveMonitorAutoStart.windowToken(fiveHourReset: a, weeklyReset: b),
            LiveMonitorAutoStart.windowToken(fiveHourReset: b, weeklyReset: a)
        )
    }
}
