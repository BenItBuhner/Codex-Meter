import XCTest
@testable import CodexMeterCore

final class CreditExpiryAutoApplyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    func testPlansAvailableUnexpiredCreditsSoonestFirst() {
        let soon = RateLimitResetCredit(
            id: "soon",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(90)
        )
        let later = RateLimitResetCredit(
            id: "later",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(3_600)
        )
        let redeemed = RateLimitResetCredit(
            id: "used",
            resetType: "both",
            status: RateLimitResetCredit.redeemedStatus,
            expiresAt: now.addingTimeInterval(30)
        )
        let expired = RateLimitResetCredit(
            id: "expired",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(-1)
        )
        let noExpiry = RateLimitResetCredit(
            id: "none",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus
        )

        let planned = CreditExpiryAutoApply.plan(
            credits: [later, redeemed, expired, noExpiry, soon],
            now: now
        )
        XCTAssertEqual(planned.map(\.creditId), ["soon", "later"])
        XCTAssertEqual(planned[0].triggerAt, soon.expiresAt!.addingTimeInterval(-60))
    }

    func testDueOnlyIncludesTheLastMinuteBeforeExpiry() {
        let due = RateLimitResetCredit(
            id: "due",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(45)
        )
        let later = RateLimitResetCredit(
            id: "later",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(120)
        )
        let expired = RateLimitResetCredit(
            id: "expired",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now
        )

        let result = CreditExpiryAutoApply.due(
            credits: [due, later, expired],
            now: now
        )
        XCTAssertEqual(result.map(\.creditId), ["due"])
        XCTAssertTrue(CreditExpiryAutoApply.isDue(expiresAt: due.expiresAt!, now: now))
        XCTAssertTrue(CreditExpiryAutoApply.isDue(
            expiresAt: now.addingTimeInterval(CreditExpiryAutoApply.applyLead),
            now: now
        ))
        XCTAssertFalse(CreditExpiryAutoApply.isDue(expiresAt: later.expiresAt!, now: now))
        XCTAssertFalse(CreditExpiryAutoApply.isDue(expiresAt: expired.expiresAt!, now: now))

        let alsoDue = RateLimitResetCredit(
            id: "also-due",
            resetType: "both",
            status: RateLimitResetCredit.availableStatus,
            expiresAt: now.addingTimeInterval(10)
        )
        XCTAssertEqual(
            CreditExpiryAutoApply.due(
                credits: [alsoDue, due, later],
                now: now
            ).map(\.creditId),
            ["also-due", "due"]
        )
    }
}
