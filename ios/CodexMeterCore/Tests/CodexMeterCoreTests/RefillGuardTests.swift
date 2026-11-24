import Foundation
import XCTest
@testable import CodexMeterCore

/// An early full refill is held until a second reading agrees; an on-time reset never is.
final class RefillGuardTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func fiveHour(_ used: Int, _ resetAt: Date) -> UsageWindow {
        UsageWindow(usedPercent: used, windowSeconds: 18_000, resetAt: resetAt)
    }

    private func snapshot(_ window: UsageWindow?, at: Date, extra: [UsageLimit] = []) -> UsageSnapshot {
        UsageSnapshot(planType: "", allowed: true, limitReached: false, fiveHour: window, weekly: nil,
                      additionalLimits: extra, fetchedAt: at)
    }

    func testResetAMinuteOrTwoEarlyIsTheScheduledReset() {
        let before = snapshot(fiveHour(70, now.addingTimeInterval(120)), at: now.addingTimeInterval(-600))
        let after = snapshot(fiveHour(0, now.addingTimeInterval(18_000)), at: now)
        XCTAssertFalse(RefillGuard.suspect(previous: before, current: after))
        XCTAssertFalse(RefillGuard.check(previous: before, current: after, heldSince: nil, now: now).hold)
        let early = snapshot(fiveHour(70, now.addingTimeInterval(360)), at: now.addingTimeInterval(-600))
        XCTAssertTrue(RefillGuard.suspect(previous: early, current: after))
    }

    func testEarlyFullRefillIsHeldThenConfirmed() {
        let before = snapshot(fiveHour(60, now.addingTimeInterval(3 * 3600)), at: now.addingTimeInterval(-300))
        let full = snapshot(fiveHour(0, now.addingTimeInterval(18_000)), at: now)
        let first = RefillGuard.check(previous: before, current: full, heldSince: nil, now: now)
        XCTAssertEqual(first, RefillGuard.Verdict(hold: true, heldSince: now))
        let soon = RefillGuard.check(previous: before, current: full, heldSince: now, now: now.addingTimeInterval(10))
        XCTAssertEqual(soon, RefillGuard.Verdict(hold: true, heldSince: now))
        let confirmed = RefillGuard.check(previous: before, current: full, heldSince: now,
                                          now: now.addingTimeInterval(RefillGuard.confirmDelay))
        XCTAssertEqual(confirmed, RefillGuard.Verdict(hold: false, heldSince: nil))
        let late = now.addingTimeInterval(RefillGuard.holdExpires + 60)
        XCTAssertEqual(RefillGuard.check(previous: before, current: full, heldSince: now, now: late),
                       RefillGuard.Verdict(hold: true, heldSince: late))
    }

    func testFlukeThatRevertsIsSavedWithNoSurprise() {
        let before = snapshot(fiveHour(60, now.addingTimeInterval(3 * 3600)), at: now.addingTimeInterval(-300))
        let real = snapshot(fiveHour(61, now.addingTimeInterval(3 * 3600)), at: now.addingTimeInterval(45))
        XCTAssertEqual(RefillGuard.check(previous: before, current: real, heldSince: now, now: now.addingTimeInterval(45)),
                       RefillGuard.Verdict(hold: false, heldSince: nil))
    }

    func testUsedUpBackEarlyAndModelLimitsAreHeld() {
        XCTAssertTrue(RefillGuard.suspect(
            previous: snapshot(fiveHour(100, now.addingTimeInterval(7200)), at: now.addingTimeInterval(-300)),
            current: snapshot(fiveHour(30, now.addingTimeInterval(7200)), at: now)))
        XCTAssertFalse(RefillGuard.suspect(
            previous: snapshot(fiveHour(100, now.addingTimeInterval(60)), at: now.addingTimeInterval(-300)),
            current: snapshot(fiveHour(30, now.addingTimeInterval(18_000)), at: now)))
        func model(_ used: Int, _ reset: Date) -> UsageLimit {
            UsageLimit(id: "m", name: "Fable only",
                       primary: UsageWindow(usedPercent: used, windowSeconds: 604_800, resetAt: reset),
                       secondary: nil)
        }
        XCTAssertTrue(RefillGuard.suspect(
            previous: snapshot(nil, at: now.addingTimeInterval(-3600), extra: [model(60, now.addingTimeInterval(2 * 86_400))]),
            current: snapshot(nil, at: now, extra: [model(0, now.addingTimeInterval(7 * 86_400))])))
    }

    func testOrdinaryReadingsAreNeverHeld() {
        let before = snapshot(fiveHour(60, now.addingTimeInterval(3 * 3600)), at: now.addingTimeInterval(-300))
        XCTAssertFalse(RefillGuard.check(previous: before,
                                         current: snapshot(fiveHour(65, now.addingTimeInterval(3 * 3600)), at: now),
                                         heldSince: nil, now: now).hold)
        XCTAssertFalse(RefillGuard.check(previous: nil,
                                         current: snapshot(fiveHour(0, now.addingTimeInterval(18_000)), at: now),
                                         heldSince: nil, now: now).hold)
    }
}
