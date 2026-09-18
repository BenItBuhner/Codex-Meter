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
        XCTAssertTrue(snapshot.usageCredits?.shouldDisplay == true)
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
        XCTAssertNil(snapshot.monthly)
        XCTAssertFalse(snapshot.hasDisplayableData)
    }

    func testMonthlyWindowClassifiesFreeTierAndCalendarDrift() throws {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let resetAt = Int(now.addingTimeInterval(14 * 86_400).timeIntervalSince1970)
        let snapshot = try UsageParser.parse(
            """
            {
              "plan_type": "free",
              "rate_limit": {
                "allowed": true,
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 28,
                  "limit_window_seconds": 2592000,
                  "reset_after_seconds": 1209600,
                  "reset_at": \(resetAt)
                }
              }
            }
            """,
            fetchedAt: now
        )
        XCTAssertEqual(snapshot.planType, "free")
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
        XCTAssertEqual(snapshot.monthly?.usedPercent, 28)
        XCTAssertEqual(snapshot.monthly?.windowSeconds, 2_592_000)
        XCTAssertTrue(snapshot.hasDisplayableData)
        XCTAssertTrue(snapshot.longWindowIsMonthly)
        XCTAssertEqual(snapshot.longWindow, snapshot.monthly)
        XCTAssertEqual(snapshot.nextReset(after: now), Date(timeIntervalSince1970: TimeInterval(resetAt)))

        let encoded = try JSONEncoder().encode(snapshot)
        let restored = try JSONDecoder().decode(UsageSnapshot.self, from: encoded)
        XCTAssertEqual(restored.monthly?.usedPercent, 28)
        XCTAssertEqual(restored.monthly?.windowSeconds, 2_592_000)

        let shortMonth = try UsageParser.parse(
            """
            {"plan_type":"free","rate_limit":{"primary_window":{"used_percent":5,"limit_window_seconds":2419200}}}
            """,
            fetchedAt: now
        )
        XCTAssertNotNil(shortMonth.monthly)

        let longMonth = try UsageParser.parse(
            """
            {"plan_type":"free","rate_limit":{"primary_window":{"used_percent":5,"limit_window_seconds":2678400}}}
            """,
            fetchedAt: now
        )
        XCTAssertNotNil(longMonth.monthly)

        let pro = try UsageParser.parse(
            """
            {
              "plan_type": "pro",
              "rate_limit": {
                "primary_window": {"used_percent": 10, "limit_window_seconds": 18000},
                "secondary_window": {"used_percent": 72, "limit_window_seconds": 604800}
              }
            }
            """,
            fetchedAt: now
        )
        XCTAssertNotNil(pro.fiveHour)
        XCTAssertNotNil(pro.weekly)
        XCTAssertNil(pro.monthly)
        XCTAssertFalse(pro.longWindowIsMonthly)
        XCTAssertEqual(pro.longWindow, pro.weekly)

        XCTAssertEqual(
            AdaptiveRefreshPolicy.chooseMinutes(
                snapshot: snapshot,
                attentionScore: 0,
                localHour: 12,
                consecutiveFailures: 0,
                now: now
            ),
            30
        )

        let lowMonthly = try UsageParser.parse(
            """
            {
              "plan_type": "free",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 92,
                  "limit_window_seconds": 2592000,
                  "reset_at": \(Int(now.addingTimeInterval(3 * 86_400).timeIntervalSince1970))
                }
              }
            }
            """,
            fetchedAt: now
        )
        XCTAssertEqual(
            AdaptiveRefreshPolicy.chooseMinutes(
                snapshot: lowMonthly,
                attentionScore: 0,
                localHour: 12,
                consecutiveFailures: 0,
                now: now
            ),
            5
        )
    }

    func testUnrecognizedSingleWindowStillDisplaysForGoStylePlans() throws {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let snapshot = try UsageParser.parse(
            """
            {
              "plan_type": "go",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 41,
                  "limit_window_seconds": 5184000
                }
              }
            }
            """,
            fetchedAt: now
        )
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
        XCTAssertEqual(snapshot.monthly?.usedPercent, 41)
        XCTAssertEqual(snapshot.monthly?.windowSeconds, 5_184_000)
        XCTAssertTrue(snapshot.hasDisplayableData)
        XCTAssertTrue(snapshot.longWindowIsMonthly)
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

    func testEmptyPurchasedCreditsAreNotStandaloneUsageData() throws {
        let snapshot = try UsageParser.parse(
            """
            {"credits":{"has_credits":false,"unlimited":false,"balance":"0"}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertNotNil(snapshot.usageCredits)
        XCTAssertFalse(snapshot.usageCredits?.shouldDisplay == true)
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
