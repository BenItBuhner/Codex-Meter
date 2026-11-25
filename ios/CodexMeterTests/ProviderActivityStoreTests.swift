import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// Recent activity, saved-reset announcements and "usage is back" for every provider, the same
/// rules as Android's ProviderAlerts and UsageRefillAlerts.
@MainActor
final class ProviderActivityStoreTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_794_000_000)
    private let hour: TimeInterval = 3_600
    private let day: TimeInterval = 86_400
    private var defaults: UserDefaults!
    private var store: ProviderActivityStore!

    override func setUp() async throws {
        let suite = "ProviderActivityStoreTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
        store = ProviderActivityStore(defaults: defaults)
    }

    private func snapshot(weeklyUsed: Int, resetsAt: Date?, at: Date) -> UsageSnapshot {
        UsageSnapshot(planType: "", allowed: true, limitReached: weeklyUsed >= 100, fiveHour: nil,
                      weekly: UsageWindow(usedPercent: weeklyUsed, windowSeconds: 604_800, resetAt: resetsAt),
                      fetchedAt: at)
    }

    private func saved(_ left: Int, id: String = "g1") -> ClaudeSavedResets {
        ClaudeSavedResets(eligible: true, grants: [
            ClaudeSavedResets.Grant(id: id, resetsTotal: max(left, 1), resetsLeft: left,
                                    endsAt: now.addingTimeInterval(20 * day), usableNow: true, type: "full")
        ], fetchedAt: now)
    }

    func testASurpriseRefillIsKeptForPriorityUntilDismissed() throws {
        let before = snapshot(weeklyUsed: 80, resetsAt: now + 3 * day, at: now - hour)
        let after = snapshot(weeklyUsed: 0, resetsAt: now + 7 * day, at: now)
        let event = try XCTUnwrap(store.usageSaved(.cursor, previous: before, current: after))
        XCTAssertEqual(event.kind, ProviderEvents.surpriseRefillKind)
        XCTAssertEqual(store.recent(now: now).map(\.key), [event.key])

        XCTAssertNil(store.usageSaved(.cursor, previous: after, current: after), "the same reading again says nothing")
        store.dismiss(keys: [event.key])
        XCTAssertTrue(store.recent(now: now).isEmpty)
        XCTAssertTrue(store.recent(now: now + 2 * day).isEmpty)
    }

    func testNoPreviousReadingOrAnOnTimeResetIsNotASurprise() {
        let after = snapshot(weeklyUsed: 0, resetsAt: now + 7 * day, at: now)
        XCTAssertNil(store.usageSaved(.cursor, previous: nil, current: after), "first reading after launch")
        let due = snapshot(weeklyUsed: 90, resetsAt: now + 60, at: now - hour)
        XCTAssertNil(store.usageSaved(.cursor, previous: due, current: after), "within the on-time tolerance")
    }

    func testARefillRightAfterTheUserSpentAResetIsTheirs() {
        store.markUserReset(.claudeCode, now: now - 60)
        let before = snapshot(weeklyUsed: 80, resetsAt: now + 3 * day, at: now - hour)
        let after = snapshot(weeklyUsed: 0, resetsAt: now + 7 * day, at: now)
        XCTAssertNil(store.usageSaved(.claudeCode, previous: before, current: after))
        let later = snapshot(weeklyUsed: 0, resetsAt: now + 7 * day, at: now + ProviderActivityStore.userResetQuiet)
        XCTAssertNotNil(store.usageSaved(.claudeCode, previous: before, current: later), "the quiet time is over")
    }

    func testTheFirstSavedResetListIsOnlyRemembered() throws {
        XCTAssertNil(store.savedResetsChanging(.claudeCode, stored: nil, current: saved(1), now: now),
                     "signing in never announces resets the person already had")
        let added = try XCTUnwrap(store.savedResetsChanging(.claudeCode, stored: saved(1), current: saved(3), now: now))
        XCTAssertEqual(added.kind, ProviderEvents.newResetsKind)
        XCTAssertTrue(added.title.hasPrefix("Claude Code: 2 "), added.title)
        XCTAssertNil(store.savedResetsChanging(.claudeCode, stored: saved(3), current: saved(3), now: now + 60))
    }

    func testAnAnswerWithoutTheListDoesNotReannounceItWhenItComesBack() {
        _ = store.savedResetsChanging(.claudeCode, stored: nil, current: saved(2), now: now)
        // The app passes the list it showed; a missing list never reaches the store, so the next
        // list is compared with the last one Anthropic sent.
        XCTAssertNil(store.savedResetsChanging(.claudeCode, stored: nil, current: saved(2), now: now + hour))
    }

    func testAUsedResetExplainsTheRefillThatFollows() {
        _ = store.savedResetsChanging(.claudeCode, stored: nil, current: saved(2), now: now - 120)
        XCTAssertNil(store.savedResetsChanging(.claudeCode, stored: saved(2), current: saved(1), now: now - 60))
        XCTAssertEqual(store.uses(.claudeCode).count, 1)
        XCTAssertTrue(store.recent(now: now).contains { $0.kind == ProviderEvents.resetUsedKind })

        let before = snapshot(weeklyUsed: 100, resetsAt: now + 3 * day, at: now - hour)
        let after = snapshot(weeklyUsed: 0, resetsAt: now + 7 * day, at: now)
        XCTAssertNil(store.usageSaved(.claudeCode, previous: before, current: after),
                     "the person's own reset refilled it: nothing to announce")
        XCTAssertFalse(store.recent(now: now).contains { $0.kind == ProviderEvents.surpriseRefillKind })
    }

    func testCodexCreditCountRecordsOnlyARise() {
        store.codexCreditCount(1, now: now)
        XCTAssertTrue(store.recent(now: now).isEmpty, "the first count is only remembered")
        store.codexCreditCount(1, now: now + 60)
        XCTAssertTrue(store.recent(now: now + 60).isEmpty)
        store.codexCreditCount(3, now: now + 120)
        XCTAssertEqual(store.recent(now: now + 120).count, 1)
        store.codexCreditCount(2, now: now + 180)
        XCTAssertEqual(store.recent(now: now + 180).count, 1, "a used credit is not news")
    }

    func testUsageBackIsScheduledForTheResetAndCancelledOnceItPassed() throws {
        let reset = now + 2 * hour
        let usedUp = snapshot(weeklyUsed: 100, resetsAt: reset, at: now)
        let first = store.usageBack(.cursor, snapshot: usedUp, now: now)
        let id = ProviderActivityStore.backIdentifier(.cursor, 1)
        XCTAssertEqual(first, [.schedule(id: id, title: "Cursor usage is back",
                                         body: "Your weekly limit has reset. You can use Cursor again.", at: reset)])
        XCTAssertEqual(store.usageBack(.cursor, snapshot: usedUp, now: now + 60), [], "armed once")

        let back = snapshot(weeklyUsed: 0, resetsAt: reset + 7 * day, at: reset + 60)
        XCTAssertEqual(store.usageBack(.cursor, snapshot: back, now: reset + 60), [.cancel(id: id)],
                       "the scheduled request already said so")
        XCTAssertEqual(store.usageBack(.cursor, snapshot: back, now: reset + 120), [])
    }

    func testUsageBackEarlyIsSaidAtOnce() {
        let usedUp = snapshot(weeklyUsed: 100, resetsAt: now + day, at: now)
        _ = store.usageBack(.kimi, snapshot: usedUp, now: now)
        let early = snapshot(weeklyUsed: 30, resetsAt: now + day, at: now + hour)
        let id = ProviderActivityStore.backIdentifier(.kimi, 1)
        XCTAssertEqual(store.usageBack(.kimi, snapshot: early, now: now + hour),
                       [.postNow(id: id, title: "Kimi Code usage is back",
                                 body: "Your weekly limit refilled to 70% left. You can use Kimi Code again.")])
    }

    func testSignOutForgetsEverything() {
        let before = snapshot(weeklyUsed: 80, resetsAt: now + 3 * day, at: now - hour)
        _ = store.usageSaved(.cursor, previous: before, current: snapshot(weeklyUsed: 0, resetsAt: now + 7 * day, at: now))
        _ = store.usageBack(.cursor, snapshot: snapshot(weeklyUsed: 100, resetsAt: now + day, at: now), now: now)
        let ids = store.clear(.cursor)
        XCTAssertEqual(ids.count, 3)
        XCTAssertTrue(store.recent(now: now).isEmpty)
        XCTAssertEqual(store.usageBack(.cursor, snapshot: snapshot(weeklyUsed: 10, resetsAt: now + day, at: now), now: now),
                       [], "nothing is armed after sign-out")
    }

    func testPriorityLayoutSettingsRoundTripAndIgnoreUnknownKeys() throws {
        let legacy = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(legacy.prioritySectionOrder, PrioritySectionKey.allCases)
        XCTAssertTrue(legacy.priorityHidden.isEmpty)

        var custom = AppSettings()
        custom.priorityOrder = ["models", "bogus", "watch", "models"]
        custom.priorityHiddenSections = ["alerts", "nope"]
        let decoded = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(custom))
        XCTAssertEqual(decoded.priorityOrder, ["models", "watch"])
        XCTAssertEqual(decoded.priorityHiddenSections, ["alerts"])
        // Sections missing from a saved order slot back in at their default places (Android's
        // rule), so only the saved two keep their relative order.
        let order = decoded.prioritySectionOrder
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: .models)), try XCTUnwrap(order.firstIndex(of: .watch)))
        XCTAssertEqual(Set(decoded.prioritySectionOrder), Set(PrioritySectionKey.allCases))
        XCTAssertEqual(decoded.priorityHidden, [.alerts])
    }
}
