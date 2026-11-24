import Foundation

/// Guards against one-off false refills: an answer that shows a limit full again (or a used-up
/// limit with some left) well before its reset time, which the next refresh takes back. Such a
/// reading is held: the app keeps the last real numbers, records and alerts nothing, and asks again
/// after `confirmDelay`. A second reading at least `minConfirmGap` later that still shows the
/// refill confirms it. A refill within `onTimeTolerance` of its reset time, or after it, is the
/// normal reset and is never held. Same rules as Android's and the desktop apps' RefillGuard.
public enum RefillGuard {
    /// A reset this close to its scheduled time is the scheduled reset arriving a little early.
    public static let onTimeTolerance: TimeInterval = 5 * 60
    /// How long after a held reading the provider is asked again.
    public static let confirmDelay: TimeInterval = 45
    /// A second suspect reading this long after the first confirms the refill.
    public static let minConfirmGap: TimeInterval = 30
    /// A hold this old confirms nothing; the next suspect reading starts a new one.
    public static let holdExpires: TimeInterval = 30 * 60

    public struct Verdict: Equatable, Sendable {
        public let hold: Bool
        /// The hold to keep for the next reading, or nil.
        public let heldSince: Date?
    }

    /// Whether to hold `current`. `heldSince` is when the first held reading of this run came in.
    public static func check(
        previous: UsageSnapshot?,
        current: UsageSnapshot,
        heldSince: Date?,
        now: Date
    ) -> Verdict {
        guard suspect(previous: previous, current: current) else { return Verdict(hold: false, heldSince: nil) }
        if confirms(heldSince: heldSince, now: now) { return Verdict(hold: false, heldSince: nil) }
        if let heldSince, heldSince <= now, now.timeIntervalSince(heldSince) <= holdExpires {
            return Verdict(hold: true, heldSince: heldSince)
        }
        return Verdict(hold: true, heldSince: now)
    }

    public static func confirms(heldSince: Date?, now: Date) -> Bool {
        guard let heldSince, heldSince <= now else { return false }
        let age = now.timeIntervalSince(heldSince)
        return age >= minConfirmGap && age <= holdExpires
    }

    /// True when a limit refilled well before the reset time `previous` gave.
    public static func suspect(previous: UsageSnapshot?, current: UsageSnapshot) -> Bool {
        guard let previous else { return false }
        let at = current.fetchedAt
        if suspect(previous: previous, before: previous.fiveHour, after: current.fiveHour, observedAt: at)
            || suspect(previous: previous, before: previous.weekly, after: current.weekly, observedAt: at)
            || suspect(previous: previous, before: previous.monthly, after: current.monthly, observedAt: at) {
            return true
        }
        for limit in current.additionalLimits where !limit.id.isEmpty {
            guard let before = previous.additionalLimits.first(where: { $0.id == limit.id }) else { continue }
            if suspect(previous: previous, before: before.primary, after: limit.primary, observedAt: at) {
                return true
            }
        }
        return false
    }

    public static func suspect(
        previous: UsageSnapshot,
        before: UsageWindow?,
        after: UsageWindow?,
        observedAt: Date
    ) -> Bool {
        guard let before, let after else { return false }
        let refilled = before.usedPercent > 0 && after.usedPercent == 0
        let back = before.windowSeconds > 0 && before.remainingPercent <= 0 && after.remainingPercent > 0
        guard refilled || back else { return false }
        guard let due = before.effectiveResetDate(relativeTo: previous.fetchedAt) else { return back }
        return wellBeforeReset(due, observedAt: observedAt)
    }

    /// True when `observedAt` is more than `onTimeTolerance` before `due`.
    public static func wellBeforeReset(_ due: Date, observedAt: Date) -> Bool {
        due.addingTimeInterval(-onTimeTolerance) > observedAt
    }
}
