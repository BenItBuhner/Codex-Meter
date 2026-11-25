@testable import CodexMeterCore
import XCTest

/// The same cases as Android's ProviderEventsSelfTest: surprise refills, new and used saved
/// resets, folding a refill into the reset that caused it, keeping and dismissing events.
final class ProviderEventsTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_794_000_000)
    private let hour: TimeInterval = 3_600
    private let day: TimeInterval = 86_400

    private func weekly(_ used: Int, resetsAt: Date?) -> UsageWindow {
        UsageWindow(usedPercent: used, windowSeconds: 604_800, resetAt: resetsAt)
    }

    private func snapshot(fiveHour: UsageWindow? = nil, weekly: UsageWindow?, extra: [UsageLimit] = [], at: Date) -> UsageSnapshot {
        UsageSnapshot(planType: "", allowed: true, limitReached: false, fiveHour: fiveHour, weekly: weekly,
                      additionalLimits: extra, fetchedAt: at)
    }

    private func fable(_ used: Int, resetsAt: Date) -> UsageLimit {
        UsageLimit(id: "claude_weekly_scoped_fable", name: "Fable only", limitReached: used >= 100,
                   primary: weekly(used, resetsAt: resetsAt), secondary: nil)
    }

    private func grant(_ id: String, _ left: Int, type: String = "", scope: String = "",
                       usable: Bool = true, endsAt: Date? = nil) -> ClaudeSavedResets.Grant {
        ClaudeSavedResets.Grant(id: id, resetsTotal: left, resetsLeft: left,
                                endsAt: endsAt ?? Date(timeIntervalSince1970: 3_187_296_000),
                                usableNow: usable, type: type, scope: scope)
    }

    private func saved(_ grants: ClaudeSavedResets.Grant..., eligible: Bool = true) -> ClaudeSavedResets {
        ClaudeSavedResets(eligible: eligible, grants: grants, fetchedAt: now)
    }

    func testRefillBeforeTheScheduledResetIsASurprise() throws {
        let before = snapshot(weekly: weekly(80, resetsAt: now + 3 * day), at: now - hour)
        let after = snapshot(weekly: weekly(0, resetsAt: now + 7 * day), at: now)
        let labels = ProviderEvents.refilledEarly(previous: before, current: after, shortLabel: "5-hour")
        XCTAssertEqual(labels, ["Weekly"])
        let event = try XCTUnwrap(ProviderEvents.surpriseRefill(.claudeCode, labels: labels, at: now))
        XCTAssertEqual(event.title, "Surprise Claude Code refill")
        XCTAssertEqual(event.detail, "Your weekly limit jumped back to 100% before its scheduled reset.")
        XCTAssertEqual(event.line, "Weekly refilled early")
        XCTAssertNil(ProviderEvents.surpriseRefill(.cursor, labels: [], at: now), "nothing refilled, nothing said")
    }

    func testOrdinaryResetUnknownResetAndFirstRefreshAreNot() {
        let after = snapshot(weekly: weekly(0, resetsAt: now + 7 * day), at: now)
        let onSchedule = snapshot(weekly: weekly(80, resetsAt: now - hour), at: now - 2 * hour)
        XCTAssertTrue(ProviderEvents.refilledEarly(previous: onSchedule, current: after, shortLabel: "").isEmpty)
        let unknown = snapshot(weekly: weekly(80, resetsAt: nil), at: now - hour)
        XCTAssertTrue(ProviderEvents.refilledEarly(previous: unknown, current: after, shortLabel: "").isEmpty,
                      "no reset time, no claim")
        XCTAssertTrue(ProviderEvents.refilledEarly(previous: nil, current: after, shortLabel: "").isEmpty)
        // Two minutes early is the scheduled reset arriving a little early.
        let nearly = snapshot(weekly: weekly(80, resetsAt: now + 120), at: now - hour)
        XCTAssertTrue(ProviderEvents.refilledEarly(previous: nearly, current: after, shortLabel: "").isEmpty)
        // Six minutes early is not.
        let early = snapshot(weekly: weekly(80, resetsAt: now + 360), at: now - hour)
        XCTAssertEqual(ProviderEvents.refilledEarly(previous: early, current: after, shortLabel: ""), ["Weekly"])
    }

    func testShortLimitUsesTheProvidersOwnCadence() {
        let daily = UsageWindow(usedPercent: 70, windowSeconds: 86_400, resetAt: now + 10 * hour)
        let before = snapshot(fiveHour: daily, weekly: nil, at: now - hour)
        let after = snapshot(fiveHour: UsageWindow(usedPercent: 0, windowSeconds: 86_400, resetAt: now + day),
                             weekly: nil, at: now)
        XCTAssertEqual(ProviderEvents.shortLabel(.kimi, before), "Daily")
        XCTAssertEqual(ProviderEvents.shortLabel(.codex, before), "5-hour", "Codex always says 5-hour")
        XCTAssertEqual(ProviderEvents.refilledEarly(previous: before, current: after,
                                                    shortLabel: ProviderEvents.shortLabel(.kimi, after)), ["Daily"])
        XCTAssertEqual(ProviderEvents.cadenceLabel(UsageWindow(usedPercent: 0, windowSeconds: 18_000)), "5-hour")
        XCTAssertEqual(ProviderEvents.cadenceLabel(UsageWindow(usedPercent: 0, windowSeconds: 2_592_000)), "Monthly")
        XCTAssertEqual(ProviderEvents.cadenceLabel(UsageWindow(usedPercent: 0, windowSeconds: 259_200)), "3-day")
        XCTAssertEqual(ProviderEvents.cadenceLabel(UsageWindow(usedPercent: 0, windowSeconds: 0)), "Usage")
    }

    func testModelLimitRefillIsNamed() throws {
        let before = snapshot(weekly: weekly(40, resetsAt: now + 2 * day), extra: [fable(100, resetsAt: now + 2 * day)], at: now - hour)
        let after = snapshot(weekly: weekly(40, resetsAt: now + 2 * day), extra: [fable(0, resetsAt: now + 7 * day)], at: now)
        let labels = ProviderEvents.refilledEarly(previous: before, current: after, shortLabel: "")
        XCTAssertEqual(labels, ["Fable only"])
        XCTAssertTrue(try XCTUnwrap(ProviderEvents.surpriseRefill(.claudeCode, labels: labels, at: now))
            .detail.hasPrefix("Your Fable only limit jumped back"))
        let both = try XCTUnwrap(ProviderEvents.surpriseRefill(.claudeCode, labels: ["Weekly", "Fable only"], at: now))
        XCTAssertTrue(both.detail.hasPrefix("Your weekly and Fable only limits jumped back"), both.detail)
        let three = try XCTUnwrap(ProviderEvents.surpriseRefill(.codex, labels: ["5-hour", "Weekly", "Monthly"], at: now))
        XCTAssertEqual(three.line, "5-hour, Weekly and Monthly refilled early")
    }

    func testNewGrantsAndTopUpsAreAnnounced() throws {
        let before = saved(grant("g_1", 1, type: "full"))
        let after = saved(grant("g_1", 1, type: "full"), grant("g_2", 3, scope: "Fable"))
        let added = ProviderEvents.addedSavedResets(previous: before, current: after, now: now)
        XCTAssertEqual(added.map(\.title), ["Fable reset"])
        XCTAssertEqual(added.map(\.count), [3])
        let event = try XCTUnwrap(ProviderEvents.newSavedResets(.claudeCode, added: added, availableNow: 4,
                                                               expiryText: "Mar 9", at: now))
        XCTAssertEqual(event.title, "Claude Code: 3 Fable resets added")
        XCTAssertEqual(event.detail, "You now have 4 saved resets. The soonest expires Mar 9.")
        XCTAssertEqual(event.line, "3 Fable resets added")
        XCTAssertTrue(ProviderEvents.addedSavedResets(previous: after, current: after, now: now).isEmpty)
        XCTAssertTrue(ProviderEvents.addedSavedResets(previous: after, current: before, now: now).isEmpty,
                      "a spent reset is not news")
        let more = saved(grant("g_1", 2, type: "full"))
        let grew = ProviderEvents.addedSavedResets(previous: before, current: more, now: now)
        XCTAssertEqual(grew.first?.count, 1)
        XCTAssertEqual(ProviderEvents.newSavedResets(.claudeCode, added: grew, availableNow: 2, expiryText: "", at: now)?.detail,
                       "You now have 2 saved resets.")
        XCTAssertEqual(ProviderEvents.newSavedResets(.claudeCode, added: [("Full reset", 1)], availableNow: 1,
                                                     expiryText: "Mar 9", at: now)?.detail,
                       "You now have 1 saved reset. It expires Mar 9.")
        XCTAssertNil(ProviderEvents.newSavedResets(.claudeCode, added: [], availableNow: 0, expiryText: "", at: now))
    }

    func testResetCreditCountRise() throws {
        let event = try XCTUnwrap(ProviderEvents.newResetCredits(.codex, previous: 1, current: 3, at: now))
        XCTAssertEqual(event.title, "Codex: 2 reset credits added")
        XCTAssertEqual(event.detail, "You now have 3.")
        XCTAssertEqual(ProviderEvents.newResetCredits(.codex, previous: 0, current: 1, at: now)?.line, "1 reset credit added")
        XCTAssertNil(ProviderEvents.newResetCredits(.codex, previous: 3, current: 3, at: now))
        XCTAssertNil(ProviderEvents.newResetCredits(.codex, previous: 3, current: 2, at: now), "a spent credit")
        XCTAssertNil(ProviderEvents.newResetCredits(.codex, previous: -1, current: 2, at: now), "no earlier count")
    }

    func testEventsAreKeptForADayNewestFirstAndRoundTrip() throws {
        let old = ProviderEvents.Event(kind: ProviderEvents.newResetsKind, provider: "codex", line: "a", title: "A",
                                       detail: "", at: now - 25 * hour)
        let recent = ProviderEvents.Event(kind: ProviderEvents.newResetsKind, provider: "codex", line: "b", title: "B",
                                          detail: "", at: now - 3 * hour)
        let fresh = ProviderEvents.Event(kind: ProviderEvents.surpriseRefillKind, provider: "cursor", line: "c", title: "C",
                                         detail: "d", at: now, labels: ["Monthly"])
        let future = ProviderEvents.Event(kind: ProviderEvents.newResetsKind, provider: "codex", line: "f", title: "F",
                                          detail: "", at: now + hour)
        let kept = ProviderEvents.remember([old, recent, future], fresh, now: now)
        XCTAssertEqual(kept.map(\.title), ["C", "B"], "newest first; too old and not yet are dropped")
        let back = try JSONDecoder().decode([ProviderEvents.Event].self, from: JSONEncoder().encode(kept))
        XCTAssertEqual(back, kept)
        XCTAssertEqual(back.first?.labels, ["Monthly"])
        let many = (0..<20).map {
            ProviderEvents.Event(kind: ProviderEvents.newResetsKind, provider: "codex", line: "\($0)", title: "\($0)",
                                 detail: "", at: now - Double($0) * 60)
        }
        XCTAssertEqual(ProviderEvents.recent(many, now: now).count, ProviderEvents.maxKept)
        XCTAssertEqual(ProviderEvents.ago(now - 3 * hour, now: now), "3h ago")
        XCTAssertEqual(ProviderEvents.ago(now - 30, now: now), "Just now")
        XCTAssertEqual(ProviderEvents.ago(now - 12 * 60, now: now), "12m ago")
        XCTAssertEqual(ProviderEvents.ago(now + 60, now: now), "Just now", "a clock that moved back says just now")
    }

    func testMonthlyLimitRenewingSoonAndDurations() {
        let soon = UsageWindow(usedPercent: 60, windowSeconds: 2_592_000, resetAt: now + 2 * day)
        let later = UsageWindow(usedPercent: 60, windowSeconds: 2_592_000, resetAt: now + 10 * day)
        XCTAssertTrue(ProviderEvents.renewsSoon(soon, observedAt: now, now: now))
        XCTAssertFalse(ProviderEvents.renewsSoon(later, observedAt: now, now: now))
        XCTAssertFalse(ProviderEvents.renewsSoon(nil, observedAt: now, now: now))
        XCTAssertEqual(ProviderEvents.duration(2 * day + 4 * hour), "2d 4h")
        XCTAssertEqual(ProviderEvents.duration(5 * hour + 12 * 60), "5h 12m")
        XCTAssertEqual(ProviderEvents.duration(9 * 60), "9m")
        XCTAssertEqual(ProviderEvents.duration(0), "1m")
    }

    func testUsedResetsAreFound() throws {
        let before = saved(grant("g_1", 2, type: "full"), grant("g_2", 1, scope: "Fable"))
        let fableGone = saved(grant("g_1", 2, type: "full"))
        var used = ProviderEvents.usedSavedResets(previous: before, current: fableGone, now: now)
        XCTAssertEqual(used.map(\.title), ["Fable reset"])
        XCTAssertEqual(used.first?.scope, "Fable")
        let oneFewer = saved(grant("g_1", 1, type: "full"), grant("g_2", 1, scope: "Fable"))
        used = ProviderEvents.usedSavedResets(previous: before, current: oneFewer, now: now)
        XCTAssertEqual(used.map(\.title), ["Full reset"])
        XCTAssertTrue(used[0].fiveHour && used[0].weekly)
        XCTAssertTrue(ProviderEvents.usedSavedResets(previous: before, current: before, now: now).isEmpty)
        XCTAssertTrue(ProviderEvents.usedSavedResets(previous: nil, current: before, now: now).isEmpty)
        XCTAssertTrue(ProviderEvents.usedSavedResets(previous: before, current: saved(eligible: false), now: now).isEmpty,
                      "an account no longer offered resets used none")
        let expiring = saved(grant("g_9", 1, type: "full", endsAt: now + 30))
        XCTAssertTrue(ProviderEvents.usedSavedResets(previous: expiring, current: saved(), now: now).isEmpty,
                      "a grant that expired was not used")
        let event = try XCTUnwrap(ProviderEvents.resetUsed(
            .claudeCode, uses: ProviderEvents.usedSavedResets(previous: before, current: fableGone, now: now),
            refilled: [], at: now))
        XCTAssertEqual(event.line, "Fable reset used")
        XCTAssertEqual(event.detail, "It refills your Fable limit only.")
        XCTAssertEqual(event.kind, ProviderEvents.resetUsedKind)
        let two = Array(repeating: ProviderEvents.Use.of(grant("g", 1, type: "full"), at: now), count: 2)
        XCTAssertEqual(ProviderEvents.resetUsed(.claudeCode, uses: two, refilled: [], at: now)?.line, "2 Full resets used")
        XCTAssertNil(ProviderEvents.resetUsed(.claudeCode, uses: [], refilled: [], at: now))
    }

    func testAFableResetExplainsOnlyTheFableRefill() throws {
        let before = snapshot(weekly: weekly(40, resetsAt: now + 2 * day), extra: [fable(100, resetsAt: now + 2 * day)], at: now - hour)
        let after = snapshot(weekly: weekly(40, resetsAt: now + 2 * day), extra: [fable(0, resetsAt: now + 7 * day)], at: now)
        let labels = ProviderEvents.refilledEarly(previous: before, current: after, shortLabel: "5-hour")
        let fableUse = ProviderEvents.Use(title: "Fable reset", scope: "Fable", fiveHour: false, weekly: false,
                                          clearsLabel: "Fable limit", at: now - 20 * 60)
        var split = ProviderEvents.explainedByUses(labels, shortLabel: "5-hour", uses: [fableUse], now: now)
        XCTAssertEqual(split.explained, ["Fable only"])
        XCTAssertTrue(split.rest.isEmpty)
        XCTAssertEqual(split.by.count, 1)
        XCTAssertEqual(ProviderEvents.resetUsed(.claudeCode, uses: split.by, refilled: split.explained, at: now)?.line,
                       "Fable reset used · Fable only refilled")
        split = ProviderEvents.explainedByUses(["Weekly"], shortLabel: "5-hour", uses: [fableUse], now: now)
        XCTAssertTrue(split.explained.isEmpty)
        XCTAssertEqual(split.rest, ["Weekly"], "a Fable reset leaves the weekly limit a surprise")
        let full = ProviderEvents.Use(title: "Full reset", scope: "", fiveHour: true, weekly: true,
                                      clearsLabel: "5-hour and weekly limits", at: now - hour)
        split = ProviderEvents.explainedByUses(["5-hour", "Weekly", "Fable only"], shortLabel: "5-hour", uses: [full], now: now)
        XCTAssertEqual(split.explained.count, 3, "a full reset explains them all")
        let session = ProviderEvents.Use(title: "Session reset", scope: "", fiveHour: true, weekly: false,
                                         clearsLabel: "5-hour limit", at: now - hour)
        split = ProviderEvents.explainedByUses(["5-hour", "Weekly"], shortLabel: "5-hour", uses: [session], now: now)
        XCTAssertEqual(split.explained, ["5-hour"])
        XCTAssertEqual(split.rest, ["Weekly"])
        let old = ProviderEvents.Use(title: "Fable reset", scope: "Fable", fiveHour: false, weekly: false,
                                     clearsLabel: "Fable limit", at: now - 7 * hour)
        XCTAssertTrue(ProviderEvents.explainedByUses(labels, shortLabel: "5-hour", uses: [old], now: now).explained.isEmpty,
                      "too old to explain it")
    }

    func testAUseFoundLaterFoldsInTheEarlierSurprise() throws {
        let surprise = try XCTUnwrap(ProviderEvents.surpriseRefill(.claudeCode, labels: ["Fable only"], at: now - 30 * 60))
        let other = try XCTUnwrap(ProviderEvents.surpriseRefill(.cursor, labels: ["Monthly"], at: now - 40 * 60))
        let uses = [ProviderEvents.Use(title: "Fable reset", scope: "Fable", fiveHour: false, weekly: false,
                                       clearsLabel: "Fable limit", at: now)]
        let use = ProviderEvents.resetUsed(.claudeCode, uses: uses, refilled: [], at: now)
        let out = ProviderEvents.explainEarlierRefills([surprise, other], useEvent: use, uses: uses, now: now)
        XCTAssertEqual(out.count, 2, "the surprise is folded into the use")
        XCTAssertEqual(out[0].line, "Fable reset used · Fable only refilled")
        XCTAssertEqual(out[1].provider, "cursor", "another provider's refill stays")

        let mixed = try XCTUnwrap(ProviderEvents.surpriseRefill(.claudeCode, labels: ["Weekly", "Fable only"], at: now - 600))
        let partly = ProviderEvents.explainEarlierRefills([mixed], useEvent: use, uses: uses, now: now)
        XCTAssertEqual(partly.map(\.line), ["Fable reset used · Fable only refilled", "Weekly refilled early"],
                       "what the reset doesn't explain stays a surprise")
    }

    func testAttachRefillsAddsToTheNewestUse() throws {
        let uses = [ProviderEvents.Use(title: "Full reset", scope: "", fiveHour: true, weekly: true,
                                       clearsLabel: "5-hour and weekly limits", at: now - 600)]
        let use = try XCTUnwrap(ProviderEvents.resetUsed(.claudeCode, uses: uses, refilled: [], at: now - 600))
        let out = ProviderEvents.attachRefills([use], provider: .claudeCode, by: uses, refilled: ["5-hour"], at: now)
        XCTAssertEqual(out.map(\.line), ["Full reset used · 5-hour refilled"])
        let fresh = ProviderEvents.attachRefills([], provider: .claudeCode, by: uses, refilled: ["Weekly"], at: now)
        XCTAssertEqual(fresh.map(\.line), ["Full reset used · Weekly refilled"])
    }

    func testEventsCanBeDismissedOneAtATime() {
        let a = ProviderEvents.Event(kind: ProviderEvents.surpriseRefillKind, provider: "claude_code",
                                     line: "Fable only refilled early", title: "A", detail: "", at: now - hour)
        let b = ProviderEvents.Event(kind: ProviderEvents.newResetsKind, provider: "claude_code",
                                     line: "1 Weekly reset added", title: "B", detail: "", at: now - 9 * hour)
        XCTAssertEqual(ProviderEvents.without([a, b], key: a.key).map(\.title), ["B"])
        XCTAssertNotEqual(a.key, b.key)
        XCTAssertEqual(ProviderEvents.countsLabel([("Fable reset", 3), ("Full reset", 1)]), "3 Fable resets and 1 Full reset")
        XCTAssertEqual(ProviderEvents.countsLabel([("A", 1), ("B", 2), ("C", 0), ("D", 1)]), "1 A, 2 Bs and 1 D")
    }
}
