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

    // Issue #214: the wait before a refill is a setting, separate for 5-hour and weekly.

    private func usage(fiveUsed: Int, fiveResetIn: Int64, weeklyUsed: Int, weeklyResetIn: Int64) -> UsageSnapshot {
        UsageSnapshot(
            planType: "pro", allowed: true, limitReached: false,
            fiveHour: UsageWindow(usedPercent: fiveUsed, windowSeconds: 18_000, resetAfterSeconds: fiveResetIn),
            weekly: UsageWindow(usedPercent: weeklyUsed, windowSeconds: 604_800, resetAfterSeconds: weeklyResetIn),
            fetchedAt: now
        )
    }

    private func decide(_ snapshot: UsageSnapshot, fiveWait: Int, weeklyWait: Int) -> LowUsageAutoUse {
        LowUsageAutoUse.evaluate(snapshot: snapshot, metric: .both, threshold: 1, availableCredits: 1,
                                 lastFiredToken: nil, fiveHourWaitMinutes: fiveWait,
                                 weeklyWaitMinutes: weeklyWait, now: now)
    }

    func testWeeklyWaitKeepsTheResetUpToAndIncludingTheBoundary() {
        let weeklyOut = usage(fiveUsed: 30, fiveResetIn: 7_200, weeklyUsed: 100, weeklyResetIn: 86_400)
        XCTAssertTrue(LowUsageAutoUse.evaluate(snapshot: weeklyOut, metric: .both, threshold: 1,
                                               availableCredits: 1, lastFiredToken: nil, now: now).fires,
                      "the default 15 minutes still spends a reset on a week that refills tomorrow")
        let held = decide(weeklyOut, fiveWait: 15, weeklyWait: 1_440)
        XCTAssertEqual(held.outcome, .skipResetImminent)
        XCTAssertEqual(held.windowLabel, "weekly")
        XCTAssertEqual(held.resetAt, now.addingTimeInterval(86_400))
        XCTAssertTrue(decide(weeklyOut, fiveWait: 15, weeklyWait: 720).fires)
        XCTAssertTrue(decide(weeklyOut, fiveWait: 240, weeklyWait: 15).fires,
                      "a long 5-hour wait doesn't hold back the weekly limit")
    }

    func testFiveHourWaitAndDontWait() {
        let fiveOut = usage(fiveUsed: 100, fiveResetIn: 5_400, weeklyUsed: 40, weeklyResetIn: 259_200)
        XCTAssertTrue(decide(fiveOut, fiveWait: 60, weeklyWait: 4_320).fires)
        XCTAssertEqual(decide(fiveOut, fiveWait: 120, weeklyWait: 0).outcome, .skipResetImminent)
        let refillsInAMinute = usage(fiveUsed: 100, fiveResetIn: 60, weeklyUsed: 40, weeklyResetIn: 259_200)
        XCTAssertTrue(decide(refillsInAMinute, fiveWait: 0, weeklyWait: 0).fires)
        let monthly = UsageSnapshot(
            planType: "free", allowed: true, limitReached: false, fiveHour: nil, weekly: nil,
            monthly: UsageWindow(usedPercent: 100, windowSeconds: 2_592_000, resetAfterSeconds: 172_800),
            fetchedAt: now)
        XCTAssertEqual(LowUsageAutoUse.evaluate(snapshot: monthly, metric: .weekly, threshold: 0,
                                                availableCredits: 1, lastFiredToken: nil,
                                                fiveHourWaitMinutes: 15, weeklyWaitMinutes: 2_880,
                                                now: now).outcome,
                       .skipResetImminent, "a monthly window uses the weekly wait")
    }

    func testWaitOptionsLabelsAndFallbacks() {
        XCTAssertEqual(LowUsageAutoUse.normalizedWeeklyWait(1_440), 1_440)
        XCTAssertEqual(LowUsageAutoUse.normalizedWeeklyWait(999), 15)
        XCTAssertEqual(LowUsageAutoUse.normalizedFiveHourWait(1_440), 15)
        XCTAssertEqual(LowUsageAutoUse.normalizedFiveHourWait(0), 0)
        XCTAssertEqual(LowUsageAutoUse.fiveHourWaitOptions.map(LowUsageAutoUse.waitLabel),
                       ["Don't wait", "5 minutes", "15 minutes", "30 minutes", "1 hour", "2 hours",
                        "3 hours", "4 hours"])
        XCTAssertEqual(LowUsageAutoUse.waitLabel(720), "12 hours")
        XCTAssertEqual(LowUsageAutoUse.waitLabel(1_440), "1 day")
        XCTAssertEqual(LowUsageAutoUse.waitLabel(4_320), "3 days")
    }

    func testAHeldBackUseIsRememberedUntilTheLimitRefillsOrAResetIsUsed() throws {
        let weeklyOut = usage(fiveUsed: 30, fiveResetIn: 7_200, weeklyUsed: 100, weeklyResetIn: 86_400)
        let wait = try XCTUnwrap(RefillWait.after(nil, decision: decide(weeklyOut, fiveWait: 15, weeklyWait: 1_440),
                                                  now: now))
        XCTAssertEqual(wait, RefillWait(windowLabel: "weekly", resetAt: now.addingTimeInterval(86_400), skippedAt: now))
        let waiting = LowUsageAutoUse.evaluate(snapshot: nil, metric: .both, threshold: 1, availableCredits: 1,
                                               lastFiredToken: nil, now: now)
        XCTAssertEqual(RefillWait.after(wait, decision: waiting, now: now.addingTimeInterval(3_600)), wait)
        XCTAssertNil(RefillWait.after(wait, decision: waiting, now: now.addingTimeInterval(86_400)))
        XCTAssertNil(RefillWait.after(wait, decision: decide(weeklyOut, fiveWait: 15, weeklyWait: 15), now: now))
        XCTAssertEqual(wait.summary { _ in "tomorrow at 9:00 AM" },
                       "Your weekly limit refills on its own tomorrow at 9:00 AM, so no reset was used.")
        let encoded = try JSONEncoder().encode(wait)
        XCTAssertEqual(try JSONDecoder().decode(RefillWait.self, from: encoded), wait)
    }

    func testRefillWaitStoreKeepsOneWaitPerProviderUntilTheRefill() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "refill-wait-\(UUID().uuidString)"))
        XCTAssertNil(RefillWaitStore.load(RefillWaitStore.codexKey, defaults: defaults))
        let wait = RefillWait(windowLabel: "weekly", resetAt: now.addingTimeInterval(72_000), skippedAt: now)
        RefillWaitStore.save(wait, RefillWaitStore.codexKey, defaults: defaults)
        XCTAssertEqual(RefillWaitStore.active(RefillWaitStore.codexKey, now: now, defaults: defaults), wait)
        XCTAssertNil(RefillWaitStore.active(RefillWaitStore.claudeKey, now: now, defaults: defaults))
        XCTAssertNil(RefillWaitStore.active(RefillWaitStore.codexKey, now: now.addingTimeInterval(72_000),
                                            defaults: defaults))
        RefillWaitStore.save(nil, RefillWaitStore.codexKey, defaults: defaults)
        XCTAssertNil(RefillWaitStore.load(RefillWaitStore.codexKey, defaults: defaults))
    }
}
