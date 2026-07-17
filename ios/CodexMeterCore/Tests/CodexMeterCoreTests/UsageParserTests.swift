import Foundation
import XCTest
@testable import CodexMeterCore

final class UsageParserTests: XCTestCase {
    private let fetchedAt = Date(timeIntervalSince1970: 1_900_000_000)

    func testStandardSnapshot() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-standard"),
            fetchedAt: fetchedAt
        )

        XCTAssertEqual(snapshot.planType, "plus")
        XCTAssertTrue(snapshot.allowed)
        XCTAssertFalse(snapshot.limitReached)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 37)
        XCTAssertEqual(snapshot.fiveHour?.remainingPercent, 63)
        XCTAssertEqual(snapshot.fiveHour?.windowSeconds, 18_000)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 62)
        XCTAssertEqual(snapshot.resetCreditsAvailable, 3)
        XCTAssertEqual(snapshot.fetchedAt, fetchedAt)
    }

    func testIdentifiesReversedWindowsByDuration() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-reversed"),
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 88)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 12)
    }

    func testAdditionalWindowsClampValuesAndNegativeResetFields() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-additional"),
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 100)
        XCTAssertEqual(snapshot.fiveHour?.remainingPercent, 0)
        XCTAssertEqual(snapshot.fiveHour?.resetAfterSeconds, 0)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 0)
        XCTAssertEqual(snapshot.weekly?.remainingPercent, 100)
    }

    func testMainRateLimitTakesPrecedenceOverCloserAdditionalWindow() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-primary-precedence"),
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 10)
        XCTAssertEqual(snapshot.fiveHour?.windowSeconds, 21_600)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 20)
    }

    func testMalformedWindowsAreIgnoredAndDirectAdditionalShapeIsAccepted() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-malformed"),
            fetchedAt: fetchedAt
        )
        XCTAssertFalse(snapshot.allowed)
        XCTAssertTrue(snapshot.limitReached)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 45)
        XCTAssertEqual(snapshot.fiveHour?.resetAfterSeconds, 0)
        XCTAssertNil(snapshot.fiveHour?.resetAt)
        XCTAssertNil(snapshot.weekly)
        XCTAssertNil(snapshot.resetCreditsAvailable)
    }

    func testAdditionalLimitsOnlyFillMissingPrimarySlots() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-fill-missing"),
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 30)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 70)
    }

    func testDefaultsWhenRateLimitIsMissing() throws {
        let snapshot = try UsageParser.parse("{\"plan_type\":\"free\"}", fetchedAt: fetchedAt)
        XCTAssertTrue(snapshot.allowed)
        XCTAssertFalse(snapshot.limitReached)
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
    }

    func testTieChoosesFirstWindow() throws {
        let json = """
        {
          "rate_limit": {
            "primary_window": {"used_percent": 11, "limit_window_seconds": 14400},
            "secondary_window": {"used_percent": 22, "limit_window_seconds": 21600}
          }
        }
        """
        let snapshot = try UsageParser.parse(json, fetchedAt: fetchedAt)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 11)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 22)
    }

    func testInvalidRootThrows() {
        XCTAssertThrowsError(try UsageParser.parse("[]", fetchedAt: fetchedAt)) { error in
            XCTAssertEqual(error as? CodexMeterParsingError, .invalidRootObject)
        }
        XCTAssertThrowsError(try UsageParser.parse("not json", fetchedAt: fetchedAt))
    }

    func testBooleanNumericFieldsAreMalformedRatherThanCoerced() throws {
        let snapshot = try UsageParser.parse(
            """
            {
              "rate_limit": {
                "allowed": 1,
                "primary_window": {
                  "used_percent": true,
                  "limit_window_seconds": 18000
                },
                "secondary_window": {
                  "used_percent": 2,
                  "limit_window_seconds": false
                }
              }
            }
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertTrue(snapshot.allowed)
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
    }
}
