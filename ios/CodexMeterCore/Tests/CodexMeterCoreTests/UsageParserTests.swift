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
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
        let limit = try XCTUnwrap(snapshot.additionalLimits.first)
        XCTAssertEqual(limit.displayName, "GPT-5.3-Codex-Spark")
        XCTAssertEqual(limit.meteredFeature, "codex_bengalfox")
        XCTAssertFalse(limit.allowed)
        XCTAssertTrue(limit.limitReached)
        XCTAssertEqual(limit.primary?.usedPercent, 100)
        XCTAssertEqual(limit.primary?.remainingPercent, 0)
        XCTAssertEqual(limit.primary?.resetAfterSeconds, 0)
        XCTAssertEqual(limit.secondary?.usedPercent, 0)
        XCTAssertEqual(limit.secondary?.remainingPercent, 100)
        XCTAssertEqual(snapshot.usageCredits?.balance, "2500.5")
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
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
        XCTAssertEqual(snapshot.additionalLimits.first?.primary?.usedPercent, 45)
        XCTAssertEqual(snapshot.additionalLimits.first?.primary?.resetAfterSeconds, 0)
        XCTAssertNil(snapshot.additionalLimits.first?.primary?.resetAt)
        XCTAssertNil(snapshot.resetCreditsAvailable)
    }

    func testAdditionalLimitsRemainIndependentFromMissingPrimarySlots() throws {
        let snapshot = try UsageParser.parse(
            FixtureLoader.data(named: "usage-fill-missing"),
            fetchedAt: fetchedAt
        )
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 70)
        XCTAssertEqual(snapshot.additionalLimits.first?.primary?.usedPercent, 30)
        XCTAssertEqual(snapshot.additionalLimits.first?.secondary?.usedPercent, 99)
    }

    func testDefaultsWhenRateLimitIsMissing() throws {
        let snapshot = try UsageParser.parse("{\"plan_type\":\"free\"}", fetchedAt: fetchedAt)
        XCTAssertTrue(snapshot.allowed)
        XCTAssertFalse(snapshot.limitReached)
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
        XCTAssertFalse(snapshot.hasDisplayableData)
    }

    func testWeeklyAndCreditsCanExistWithoutFiveHourWindow() throws {
        let snapshot = try UsageParser.parse(
            """
            {
              "rate_limit": {
                "secondary_window": {
                  "used_percent": 25,
                  "limit_window_seconds": 604800
                }
              },
              "credits": {
                "has_credits": true,
                "unlimited": false,
                "balance": 2500
              }
            }
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 25)
        XCTAssertEqual(snapshot.usageCredits?.balance, "2500")
        XCTAssertTrue(snapshot.hasDisplayableData)
    }

    func testNoCreditsBalanceIsNotStandaloneUsageData() throws {
        let snapshot = try UsageParser.parse(
            """
            {"credits":{"has_credits":false,"unlimited":false,"balance":"0"}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertNotNil(snapshot.usageCredits)
        XCTAssertNil(snapshot.usageCredits?.balance)
        XCTAssertFalse(snapshot.hasDisplayableData)
    }

    func testFiveHourTieChoosesFirstAndDoesNotInventWeeklyWindow() throws {
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
        XCTAssertNil(snapshot.weekly)
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
