import Foundation
import XCTest
@testable import CodexMeterCore

final class ResetCreditsParserTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_783_807_200)

    func testParsesCreditsAndSelectsSoonestUnexpiredAvailableCredit() throws {
        let snapshot = try ResetCreditsParser.parse(
            FixtureLoader.data(named: "reset-credits"),
            fetchedAt: now
        )
        XCTAssertEqual(snapshot.availableCount, 4)
        XCTAssertEqual(snapshot.credits.count, 5)
        XCTAssertEqual(snapshot.preferredCreditID(at: now), "soon")
        XCTAssertEqual(snapshot.nextExpiringAvailable(at: now)?.title, "Soon credit")
        XCTAssertEqual(
            snapshot.nextExpiry(after: now),
            Date(timeIntervalSince1970: 1_784_543_400)
        )
        XCTAssertNil(snapshot.credits[3].grantedAt)
        XCTAssertNil(snapshot.credits[3].expiresAt)
    }

    func testDerivesAvailableCountWhenFieldIsAbsent() throws {
        let snapshot = try ResetCreditsParser.parse(
            FixtureLoader.data(named: "reset-credits-derived-count"),
            fetchedAt: now
        )
        XCTAssertEqual(snapshot.availableCount, 2)
        XCTAssertEqual(snapshot.credits.count, 3)
    }

    func testNegativeExplicitAvailableCountClampsToZero() throws {
        let snapshot = try ResetCreditsParser.parse(
            "{\"available_count\":-9}",
            fetchedAt: now
        )
        XCTAssertEqual(snapshot.availableCount, 0)
    }

    func testNilStringMatchesEmptyObject() throws {
        let snapshot = try ResetCreditsParser.parse(nil, fetchedAt: now)
        XCTAssertEqual(snapshot, .summary(availableCount: 0, fetchedAt: now))
    }

    func testNoExpirySortsAfterPositiveExpiryButRemainsEligible() {
        let noExpiry = RateLimitResetCredit(id: "none", resetType: "", status: "available")
        let expired = RateLimitResetCredit(
            id: "expired",
            resetType: "",
            status: "available",
            expiresAt: now.addingTimeInterval(-1)
        )
        let snapshot = ResetCreditsSnapshot(
            availableCount: 2,
            credits: [expired, noExpiry],
            fetchedAt: now
        )
        XCTAssertEqual(snapshot.preferredCreditID(at: now), "none")
        XCTAssertNil(snapshot.nextExpiry(after: now))
    }
}
