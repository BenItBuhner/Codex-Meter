import XCTest
@testable import CodexMeterCore

final class CreditExpiryAutoApplyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    private func credit(_ id: String, in seconds: TimeInterval?,
                        status: String = RateLimitResetCredit.availableStatus) -> RateLimitResetCredit {
        RateLimitResetCredit(
            id: id,
            resetType: "both",
            status: status,
            expiresAt: seconds.map { now.addingTimeInterval($0) }
        )
    }

    func testDefaultLeadIsThirtyMinutes() {
        XCTAssertEqual(CreditExpiryAutoApply.defaultLeadMinutes, 30)
        XCTAssertEqual(CreditExpiryAutoApply.normalizedLeadMinutes(0), 30)
        XCTAssertEqual(CreditExpiryAutoApply.normalizedLeadMinutes(50), 60)
        XCTAssertEqual(CreditExpiryAutoApply.leadLabel(30), "30 minutes")
        XCTAssertEqual(CreditExpiryAutoApply.leadLabel(60), "1 hour")
        XCTAssertEqual(CreditExpiryAutoApply.leadLabel(1), "1 minute")
    }

    func testPlansAvailableUnexpiredCreditsSoonestFirst() {
        let soon = credit("soon", in: 20 * 60)
        let later = credit("later", in: 3_600)
        let planned = CreditExpiryAutoApply.plan(
            credits: [later, credit("used", in: 30, status: RateLimitResetCredit.redeemedStatus),
                      credit("expired", in: -1), credit("none", in: nil), soon],
            now: now
        )
        XCTAssertEqual(planned.map(\.creditId), ["soon", "later"])
        XCTAssertEqual(planned[0].triggerAt, soon.expiresAt!.addingTimeInterval(-30 * 60))
    }

    func testDueFollowsTheChosenLeadTime() {
        let due = credit("due", in: 20 * 60)
        let later = credit("later", in: 90 * 60)
        XCTAssertEqual(
            CreditExpiryAutoApply.due(credits: [due, later, credit("expired", in: 0)], now: now)
                .map(\.creditId),
            ["due"]
        )
        XCTAssertTrue(CreditExpiryAutoApply.isDue(expiresAt: now.addingTimeInterval(30 * 60), now: now))
        XCTAssertFalse(CreditExpiryAutoApply.isDue(expiresAt: now.addingTimeInterval(31 * 60), now: now))
        XCTAssertTrue(CreditExpiryAutoApply.isDue(expiresAt: later.expiresAt!, now: now, leadMinutes: 120))
        XCTAssertFalse(CreditExpiryAutoApply.isDue(expiresAt: due.expiresAt!, now: now, leadMinutes: 1))
        XCTAssertEqual(
            CreditExpiryAutoApply.due(credits: [credit("also-due", in: 300), due, later], now: now)
                .map(\.creditId),
            ["also-due", "due"]
        )
    }

    private func snapshot(fiveHourUsed: Int, weeklyUsed: Int, resetAfter: Int64,
                          fetchedAt: Date? = nil) -> UsageSnapshot {
        UsageSnapshot(
            planType: "pro", allowed: true, limitReached: false,
            fiveHour: UsageWindow(usedPercent: fiveHourUsed, windowSeconds: 18_000,
                                  resetAfterSeconds: resetAfter),
            weekly: UsageWindow(usedPercent: weeklyUsed, windowSeconds: 604_800,
                                resetAfterSeconds: resetAfter),
            fetchedAt: fetchedAt ?? now
        )
    }

    func testLowUsageFiresOncePerWindowAtTheThreshold() {
        let low = snapshot(fiveHourUsed: 99, weeklyUsed: 60, resetAfter: 3_600)
        let fire = LowUsageAutoUse.evaluate(snapshot: low, metric: .both, threshold: 1,
                                            availableCredits: 2, lastFiredToken: nil, now: now)
        XCTAssertTrue(fire.fires)
        XCTAssertEqual(fire.windowLabel, "5-hour")
        XCTAssertEqual(fire.remainingPercent, 1)
        XCTAssertFalse(LowUsageAutoUse.evaluate(snapshot: low, metric: .weekly, threshold: 1,
                                                availableCredits: 2, lastFiredToken: nil, now: now).fires)
        XCTAssertFalse(LowUsageAutoUse.evaluate(
            snapshot: snapshot(fiveHourUsed: 97, weeklyUsed: 60, resetAfter: 3_600),
            metric: .both, threshold: 2, availableCredits: 2, lastFiredToken: nil, now: now).fires)
        let later = now.addingTimeInterval(60)
        let again = LowUsageAutoUse.evaluate(
            snapshot: snapshot(fiveHourUsed: 100, weeklyUsed: 60, resetAfter: 3_540, fetchedAt: later),
            metric: .both, threshold: 1, availableCredits: 1,
            lastFiredToken: fire.windowToken, now: later)
        XCTAssertEqual(again.outcome, .skipAlreadyUsed)
    }

    func testLowUsageGuards() {
        let low = snapshot(fiveHourUsed: 99, weeklyUsed: 60, resetAfter: 3_600)
        XCTAssertEqual(LowUsageAutoUse.evaluate(snapshot: low, metric: .both, threshold: 1,
                                                availableCredits: 0, lastFiredToken: nil, now: now).outcome,
                       .skipNoCredit)
        XCTAssertEqual(LowUsageAutoUse.evaluate(
            snapshot: snapshot(fiveHourUsed: 100, weeklyUsed: 60, resetAfter: 600),
            metric: .both, threshold: 1, availableCredits: 1, lastFiredToken: nil, now: now).outcome,
                       .skipResetImminent)
        XCTAssertEqual(LowUsageAutoUse.evaluate(snapshot: low, metric: .both, threshold: 1,
                                                availableCredits: 1, lastFiredToken: nil,
                                                now: now.addingTimeInterval(300)).outcome,
                       .wait)
        let monthly = UsageSnapshot(
            planType: "free", allowed: true, limitReached: false, fiveHour: nil, weekly: nil,
            monthly: UsageWindow(usedPercent: 100, windowSeconds: 2_592_000, resetAfterSeconds: 86_400),
            fetchedAt: now)
        let decision = LowUsageAutoUse.evaluate(snapshot: monthly, metric: .weekly, threshold: 0,
                                                availableCredits: 1, lastFiredToken: nil, now: now)
        XCTAssertTrue(decision.fires)
        XCTAssertEqual(decision.windowLabel, "monthly")
        XCTAssertEqual(LowUsageAutoUse.normalizedThreshold(7), 1)
    }
}
