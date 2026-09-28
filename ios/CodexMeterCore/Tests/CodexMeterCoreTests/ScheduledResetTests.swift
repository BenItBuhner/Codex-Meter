import Foundation
import XCTest
@testable import CodexMeterCore

final class ScheduledResetTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 2_000_000_000)

    func testSharedFixtureCasesReachTheExpectedDecision() throws {
        let root = try fixtureRoot()
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        let leeway = TimeInterval(root["imminentResetLeewaySeconds"] as? Int ?? 900)
        let now = Date(timeIntervalSince1970: TimeInterval(root["nowEpochSeconds"] as? Int ?? 0))

        for testCase in cases {
            let name = try XCTUnwrap(testCase["name"] as? String)
            let trigger = try trigger(from: XCTUnwrap(testCase["trigger"] as? [String: Any]), now: now)
            let usage = try usage(from: XCTUnwrap(testCase["usage"] as? [String: Any]), now: now)
            let credits = try XCTUnwrap(testCase["availableCredits"] as? Int)
            let expected = try XCTUnwrap(testCase["expected"] as? String)

            let decision = ScheduledResetPolicy.evaluate(
                trigger,
                usage: usage,
                availableCredits: credits,
                now: now,
                imminentResetLeeway: leeway
            )
            XCTAssertEqual(code(for: decision), expected, name)

            if let expectedWindow = testCase["expectedWindow"] as? String,
               case let .skip(.naturalResetImminent(window, _)) = decision {
                XCTAssertEqual(window.rawValue, expectedWindow, name)
            }
            if testCase.keys.contains("expectedRemainingPercent"),
               case let .skip(.conditionNotMet(remaining, _)) = decision {
                XCTAssertEqual(remaining, testCase["expectedRemainingPercent"] as? Int, name)
            }
        }
    }

    func testSharedCreditExpiryCases() throws {
        let root = try fixtureRoot()
        let cases = try XCTUnwrap(root["creditExpiryCases"] as? [[String: Any]])
        let now = Date(timeIntervalSince1970: TimeInterval(root["nowEpochSeconds"] as? Int ?? 0))

        for testCase in cases {
            let name = try XCTUnwrap(testCase["name"] as? String)
            let fireAt = now.addingTimeInterval(TimeInterval(try XCTUnwrap(testCase["fireAtOffsetSeconds"] as? Int)))
            let credits = try XCTUnwrap(testCase["credits"] as? [[String: Any]]).map { credit in
                RateLimitResetCredit(
                    id: credit["id"] as? String ?? "",
                    resetType: "rate_limit",
                    status: credit["status"] as? String ?? "",
                    expiresAt: (credit["expiresAtOffsetSeconds"] as? Int).map {
                        now.addingTimeInterval(TimeInterval($0))
                    }
                )
            }
            let snapshot = ResetCreditsSnapshot(
                availableCount: credits.filter(\.isAvailable).count,
                credits: credits,
                fetchedAt: now
            )
            XCTAssertEqual(
                ScheduledResetPolicy.creditsExpireBefore(fireAt: fireAt, credits: snapshot),
                try XCTUnwrap(testCase["expected"] as? Bool),
                name
            )
        }
    }

    func testCopyMatchesTheSharedSpec() {
        let threshold = ScheduledResetTrigger.threshold(window: .fiveHour, remainingPercent: 5)
        XCTAssertEqual(ScheduledResetCopy.featureName, "Scheduled reset")
        XCTAssertEqual(
            ScheduledResetCopy.confirmation(threshold, now: now),
            "Codex Meter will use 1 reset credit when your 5-hour limit reaches 5% remaining."
        )
        XCTAssertEqual(
            ScheduledResetCopy.armedLine(threshold, now: now),
            "Scheduled · when 5-hour reaches 5%"
        )
        XCTAssertEqual(
            ScheduledResetCopy.armedLine(.threshold(window: .weekly, remainingPercent: 10), now: now),
            "Scheduled · when weekly reaches 10%"
        )

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let locale = Locale(identifier: "en_US_POSIX")
        let fireAt = now.addingTimeInterval(2 * 60 * 60)
        XCTAssertEqual(
            ScheduledResetCopy.confirmation(
                .dateTime(fireAt: fireAt, onlyIfRemainingAtMost: nil),
                now: now,
                calendar: calendar,
                locale: locale
            ),
            "Codex Meter will use 1 reset credit today at 5:33 AM."
        )
        XCTAssertEqual(
            ScheduledResetCopy.confirmation(
                .dateTime(fireAt: fireAt, onlyIfRemainingAtMost: 10),
                now: now,
                calendar: calendar,
                locale: locale
            ),
            "Codex Meter will use 1 reset credit today at 5:33 AM if remaining is at or below 10%."
        )
        XCTAssertEqual(
            ScheduledResetCopy.armedLine(
                .dateTime(fireAt: fireAt, onlyIfRemainingAtMost: nil),
                now: now,
                calendar: calendar,
                locale: locale
            ),
            "Scheduled · today at 5:33 AM"
        )
    }

    func testOutcomeCopyNamesTheCauseAndTheCreditsLeft() {
        let threshold = ScheduledResetTrigger.threshold(window: .fiveHour, remainingPercent: 5)
        let fired = ScheduledResetOutcome(
            trigger: threshold,
            kind: .fired(creditsRemaining: 1, windowsReset: 2),
            observedRemainingPercent: 2,
            at: now
        )
        XCTAssertEqual(
            ScheduledResetCopy.outcomeMessage(fired),
            "Scheduled reset used 1 credit — Your 5-hour limit reached 2% remaining. 1 reset credit left."
        )

        let noCredit = ScheduledResetOutcome(trigger: threshold, kind: .skipped(.noCreditAvailable), at: now)
        XCTAssertEqual(
            ScheduledResetCopy.outcomeMessage(noCredit),
            "Scheduled reset skipped — No reset credit was available, so nothing was spent."
        )

        let imminent = ScheduledResetOutcome(
            trigger: threshold,
            kind: .skipped(.naturalResetImminent(window: .fiveHour, resetAt: now.addingTimeInterval(12 * 60))),
            at: now
        )
        XCTAssertEqual(
            ScheduledResetCopy.outcomeDetail(imminent),
            "Your 5-hour limit resets on its own in 12m, so the credit was kept."
        )

        let condition = ScheduledResetOutcome(
            trigger: .dateTime(fireAt: now, onlyIfRemainingAtMost: 10),
            kind: .skipped(.conditionNotMet(remainingPercent: 36, requiredAtMost: 10)),
            at: now
        )
        XCTAssertEqual(
            ScheduledResetCopy.outcomeDetail(condition),
            "Remaining usage was 36%, above the 10% condition, so the credit was kept."
        )

        let failed = ScheduledResetOutcome(
            trigger: threshold,
            kind: .failed(message: "The server returned an invalid response."),
            at: now
        )
        XCTAssertEqual(
            ScheduledResetCopy.outcomeMessage(failed),
            "Scheduled reset failed — The server returned an invalid response. The schedule stays armed and retries on the next refresh."
        )
        XCTAssertTrue(failed.isFailure)
        XCTAssertFalse(fired.isFailure)
    }

    func testRefreshCapTightensAsAThresholdApproaches() {
        let trigger = ScheduledResetTrigger.threshold(window: .fiveHour, remainingPercent: 5)
        XCTAssertNil(ScheduledResetPolicy.refreshMinutesCap(for: trigger, usage: snapshot(fiveHourUsed: 40), now: now))
        XCTAssertEqual(ScheduledResetPolicy.refreshMinutesCap(for: trigger, usage: snapshot(fiveHourUsed: 70), now: now), 10)
        XCTAssertEqual(ScheduledResetPolicy.refreshMinutesCap(for: trigger, usage: snapshot(fiveHourUsed: 88), now: now), 5)
        XCTAssertNil(ScheduledResetPolicy.refreshMinutesCap(for: trigger, usage: nil, now: now))

        let soon = ScheduledResetTrigger.dateTime(fireAt: now.addingTimeInterval(10 * 60), onlyIfRemainingAtMost: nil)
        let later = ScheduledResetTrigger.dateTime(fireAt: now.addingTimeInterval(45 * 60), onlyIfRemainingAtMost: nil)
        let far = ScheduledResetTrigger.dateTime(fireAt: now.addingTimeInterval(5 * 60 * 60), onlyIfRemainingAtMost: nil)
        XCTAssertEqual(ScheduledResetPolicy.refreshMinutesCap(for: soon, usage: nil, now: now), 5)
        XCTAssertEqual(ScheduledResetPolicy.refreshMinutesCap(for: later, usage: nil, now: now), 10)
        XCTAssertNil(ScheduledResetPolicy.refreshMinutesCap(for: far, usage: nil, now: now))

        XCTAssertEqual(ScheduledResetPolicy.earliestCheck(for: far, now: now), now.addingTimeInterval(5 * 60 * 60))
        XCTAssertEqual(
            ScheduledResetPolicy.earliestCheck(
                for: .dateTime(fireAt: now.addingTimeInterval(-60), onlyIfRemainingAtMost: nil),
                now: now
            ),
            now
        )
        XCTAssertNil(ScheduledResetPolicy.earliestCheck(for: trigger, now: now))
    }

    func testAdaptiveRefreshHonorsAnArmedThresholdEvenInQuietHours() {
        let usage = snapshot(fiveHourUsed: 88)
        let idle = AdaptiveRefreshPolicy.chooseMinutes(
            snapshot: snapshot(fiveHourUsed: 5),
            attentionScore: 0,
            localHour: 3,
            consecutiveFailures: 0,
            now: now
        )
        XCTAssertEqual(idle, 120)
        let armed = AdaptiveRefreshPolicy.chooseMinutes(
            snapshot: usage,
            attentionScore: 0,
            localHour: 3,
            consecutiveFailures: 0,
            scheduledReset: .threshold(window: .fiveHour, remainingPercent: 5),
            now: now
        )
        XCTAssertEqual(armed, 5)
        let unrelated = AdaptiveRefreshPolicy.chooseMinutes(
            snapshot: snapshot(fiveHourUsed: 5),
            attentionScore: 0,
            localHour: 3,
            consecutiveFailures: 0,
            scheduledReset: .threshold(window: .fiveHour, remainingPercent: 5),
            now: now
        )
        XCTAssertEqual(unrelated, 120)
    }

    func testFreshnessDefaultDateAndPersistence() throws {
        XCTAssertFalse(ScheduledResetPolicy.requiresFreshSnapshot(fetchedAt: now.addingTimeInterval(-30), now: now))
        XCTAssertTrue(ScheduledResetPolicy.requiresFreshSnapshot(fetchedAt: now.addingTimeInterval(-61), now: now))

        // now is 03:33:20 UTC; one hour on from 03:33:37 lands on the whole minute 04:33:00.
        let fireAt = ScheduledResetPolicy.defaultFireDate(now: now.addingTimeInterval(17))
        XCTAssertEqual(fireAt, Date(timeIntervalSince1970: 2_000_003_580))
        XCTAssertEqual(fireAt.timeIntervalSince1970.truncatingRemainder(dividingBy: 60), 0)

        XCTAssertEqual(ScheduledResetPolicy.clamp(-5), 0)
        XCTAssertEqual(ScheduledResetPolicy.clamp(150), 99)

        let schedule = ScheduledReset(
            trigger: .dateTime(fireAt: fireAt, onlyIfRemainingAtMost: 10),
            createdAt: now,
            failureCount: -2
        )
        XCTAssertEqual(schedule.failureCount, 0)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(ScheduledReset.self, from: try encoder.encode(schedule))
        XCTAssertEqual(decoded, schedule)
        XCTAssertTrue(decoded.trigger.isDateTime)
        XCTAssertEqual(decoded.trigger.fireAt, fireAt)

        let outcome = ScheduledResetOutcome(
            trigger: .threshold(window: .weekly, remainingPercent: 0),
            kind: .skipped(.naturalResetImminent(window: .weekly, resetAt: now)),
            at: now
        )
        let roundTrip = try decoder.decode(ScheduledResetOutcome.self, from: try encoder.encode(outcome))
        XCTAssertEqual(roundTrip, outcome)
    }

    private func fixtureRoot() throws -> [String: Any] {
        let data = try FixtureLoader.data(named: "scheduled-reset-cases")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func trigger(from object: [String: Any], now: Date) throws -> ScheduledResetTrigger {
        switch try XCTUnwrap(object["kind"] as? String) {
        case "threshold":
            return .threshold(
                window: try XCTUnwrap(ScheduledResetWindow(rawValue: try XCTUnwrap(object["window"] as? String))),
                remainingPercent: try XCTUnwrap(object["remainingPercent"] as? Int)
            )
        case "dateTime":
            return .dateTime(
                fireAt: now.addingTimeInterval(TimeInterval(try XCTUnwrap(object["fireAtOffsetSeconds"] as? Int))),
                onlyIfRemainingAtMost: object["onlyIfRemainingAtMost"] as? Int
            )
        case let other:
            throw XCTSkip("Unknown trigger kind \(other)")
        }
    }

    private func usage(from object: [String: Any], now: Date) throws -> UsageSnapshot {
        let fetchedAt = now.addingTimeInterval(TimeInterval(object["fetchedAtOffsetSeconds"] as? Int ?? 0))
        func window(_ key: String) -> UsageWindow? {
            guard let raw = object[key] as? [String: Any] else { return nil }
            return UsageWindow(
                usedPercent: raw["usedPercent"] as? Int ?? 0,
                windowSeconds: Int64(raw["windowSeconds"] as? Int ?? 0),
                resetAfterSeconds: Int64(raw["resetAfterSeconds"] as? Int ?? 0),
                resetAt: (raw["resetAtOffsetSeconds"] as? Int).map { now.addingTimeInterval(TimeInterval($0)) }
            )
        }
        return UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: window("fiveHour"),
            weekly: window("weekly"),
            monthly: window("monthly"),
            fetchedAt: fetchedAt
        )
    }

    private func code(for decision: ScheduledResetDecision) -> String {
        switch decision {
        case .wait: "wait"
        case .fire: "fire"
        case .skip(.noCreditAvailable): "skip:noCreditAvailable"
        case .skip(.naturalResetImminent): "skip:naturalResetImminent"
        case .skip(.conditionNotMet): "skip:conditionNotMet"
        case .skip(.declined): "skip:declined"
        }
    }

    private func snapshot(fiveHourUsed: Int) -> UsageSnapshot {
        UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: fiveHourUsed,
                windowSeconds: 18_000,
                resetAt: now.addingTimeInterval(4 * 60 * 60)
            ),
            weekly: nil,
            fetchedAt: now
        )
    }
}
