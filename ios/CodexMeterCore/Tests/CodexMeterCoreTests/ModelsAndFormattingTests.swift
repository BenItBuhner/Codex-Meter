import Foundation
import XCTest
@testable import CodexMeterCore

final class ModelsAndFormattingTests: XCTestCase {
    func testUsageWindowAndSnapshotBusinessRules() {
        let now = Date(timeIntervalSince1970: 1_000)
        let fiveHour = UsageWindow(
            usedPercent: 125,
            windowSeconds: -1,
            resetAfterSeconds: 60,
            resetAt: nil
        )
        let weekly = UsageWindow(
            usedPercent: -10,
            windowSeconds: 604_800,
            resetAt: now.addingTimeInterval(120)
        )
        XCTAssertEqual(fiveHour.usedPercent, 100)
        XCTAssertEqual(fiveHour.remainingPercent, 0)
        XCTAssertEqual(fiveHour.windowSeconds, 0)
        XCTAssertEqual(fiveHour.effectiveResetDate(relativeTo: now), now.addingTimeInterval(60))
        XCTAssertTrue(fiveHour.hasRemainingAllowance(atOrBelow: 10))
        XCTAssertNil(
            UsageWindow(
                usedPercent: 1,
                windowSeconds: 1,
                resetAt: Date(timeIntervalSince1970: 0)
            ).resetAt
        )

        let snapshot = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: fiveHour,
            weekly: weekly,
            resetCreditsAvailable: -5,
            fetchedAt: now
        )
        XCTAssertEqual(snapshot.resetCreditsAvailable, 0)
        XCTAssertEqual(snapshot.nextReset(after: now), weekly.resetAt)
        XCTAssertTrue(snapshot.isStale(at: now.addingTimeInterval(61), maxAge: 60))
    }

    func testSharedSnapshotIsSanitizedAndCodable() throws {
        let now = Date(timeIntervalSince1970: 2_000)
        let shared = SharedWidgetSnapshot(
            version: -1,
            mode: .demo,
            fetchedAt: now,
            planType: "plus",
            fiveHour: UsageWindow(usedPercent: -1, windowSeconds: -2),
            weekly: nil,
            resetCreditsAvailable: -3,
            freshness: .fresh
        )
        XCTAssertEqual(shared.version, 1)
        XCTAssertEqual(shared.fiveHour?.usedPercent, 0)
        XCTAssertEqual(shared.fiveHour?.windowSeconds, 0)
        XCTAssertEqual(shared.resetCreditsAvailable, 0)

        let encoded = try JSONEncoder().encode(shared)
        let decoded = try JSONDecoder().decode(SharedWidgetSnapshot.self, from: encoded)
        XCTAssertEqual(decoded, shared)
        XCTAssertEqual(SharedWidgetSnapshot.defaultFileName, "widget-snapshot.json")
        XCTAssertEqual(SharedWidgetSnapshot.signedOut.mode, .signedOut)
    }

    func testCacheModelsDecodeTolerantlyAndReapplySanitization() throws {
        let decoder = JSONDecoder()
        let usage = try decoder.decode(
            UsageSnapshot.self,
            from: Data("{\"resetCreditsAvailable\":-4}".utf8)
        )
        XCTAssertEqual(usage.planType, "")
        XCTAssertTrue(usage.allowed)
        XCTAssertFalse(usage.limitReached)
        XCTAssertEqual(usage.resetCreditsAvailable, 0)
        XCTAssertEqual(usage.fetchedAt, Date(timeIntervalSince1970: 0))

        let credits = try decoder.decode(
            ResetCreditsSnapshot.self,
            from: Data("{\"availableCount\":-2}".utf8)
        )
        XCTAssertEqual(credits.availableCount, 0)
        XCTAssertTrue(credits.credits.isEmpty)

        let result = try decoder.decode(
            ResetConsumeResult.self,
            from: Data("{\"windowsReset\":-3,\"refreshWarning\":\"\"}".utf8)
        )
        XCTAssertEqual(result.outcome, .unknown(""))
        XCTAssertEqual(result.windowsReset, 0)
        XCTAssertNil(result.refreshWarning)
    }

    func testFreshnessEvaluation() {
        let now = Date(timeIntervalSince1970: 100)
        XCTAssertEqual(
            WidgetSnapshotFreshness.evaluate(fetchedAt: nil, now: now, staleAfter: 10),
            .unavailable
        )
        XCTAssertEqual(
            WidgetSnapshotFreshness.evaluate(
                fetchedAt: now.addingTimeInterval(-10),
                now: now,
                staleAfter: 10
            ),
            .fresh
        )
        XCTAssertEqual(
            WidgetSnapshotFreshness.evaluate(
                fetchedAt: now.addingTimeInterval(-11),
                now: now,
                staleAfter: 10
            ),
            .stale
        )
    }

    func testResetOutcomeMessagesAndRoundTrip() throws {
        let success = ResetConsumeResult(
            outcome: .reset,
            windowsReset: 2,
            refreshWarning: "Refresh pending."
        )
        XCTAssertTrue(success.applied)
        XCTAssertEqual(
            success.userMessage,
            "Reset applied to 2 usage windows. Refresh pending."
        )
        XCTAssertEqual(
            try JSONDecoder().decode(
                ResetConsumeResult.self,
                from: JSONEncoder().encode(success)
            ),
            success
        )
        XCTAssertEqual(
            ResetConsumeResult(outcome: .nothingToReset, windowsReset: 0).userMessage,
            "There is no used Codex allowance to reset right now."
        )
        XCTAssertEqual(ResetConsumeOutcome(code: "future_code"), .unknown("future_code"))
    }

    func testUsageFormattingMatchesUpstreamLabelsAndDurations() {
        XCTAssertEqual(UsageFormat.planLabel(" pro-lite "), "Pro 5x")
        XCTAssertEqual(UsageFormat.planLabel("pro_20x"), "Pro 20x")
        XCTAssertEqual(UsageFormat.planLabel("team"), "")
        let window = UsageWindow(usedPercent: 37, windowSeconds: 18_000)
        XCTAssertEqual(UsageFormat.percent(window), "63% left")
        XCTAssertEqual(UsageFormat.percent(window, display: .used, compact: true), "37%")
        XCTAssertEqual(UsageFormat.percent(nil, compact: false), "Unavailable")
        XCTAssertEqual(UsageFormat.percent(nil, compact: true), "—")

        let now = Date(timeIntervalSince1970: 0)
        XCTAssertEqual(
            UsageFormat.relative(until: now.addingTimeInterval(2 * 86_400 + 3 * 3_600 + 59), from: now),
            "in 2d 3h"
        )
        XCTAssertEqual(
            UsageFormat.relative(until: now.addingTimeInterval(3_600 + 5 * 60), from: now),
            "in 1h 5m"
        )
        XCTAssertEqual(UsageFormat.relative(until: now.addingTimeInterval(59), from: now), "now")
        XCTAssertEqual(UsageFormat.updated(fetchedAt: now, now: now), "Updated just now")
        XCTAssertEqual(
            UsageFormat.updated(fetchedAt: now, now: now.addingTimeInterval(25 * 3_600)),
            "Updated 1d ago"
        )
    }
}
