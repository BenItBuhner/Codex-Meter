import CodexMeterCore
import Foundation

/// Pure decision logic for unexpected allowance refills and reset-credit increases
/// (parity with upstream CelebrationDetector from Codex Meter 2.1+).
nonisolated public enum CelebrationDetector {
    public struct RefillMask: OptionSet, Sendable, Equatable {
        public let rawValue: Int

        public init(rawValue: Int) {
            self.rawValue = rawValue
        }

        public static let fiveHour = RefillMask(rawValue: 1 << 0)
        public static let weekly = RefillMask(rawValue: 1 << 1)
    }

    public static let unknownUserResetSuppression: TimeInterval = 15 * 60

    /// Windows that became completely available before their previously advertised reset deadline.
    public static func detectUnexpectedRefills(
        previous: UsageSnapshot?,
        current: UsageSnapshot
    ) -> RefillMask {
        guard let previous else { return [] }
        var mask: RefillMask = []
        if isUnexpectedRefill(
            previous: previous,
            oldWindow: previous.fiveHour,
            newWindow: current.fiveHour,
            observedAt: current.fetchedAt
        ) {
            mask.insert(.fiveHour)
        }
        if isUnexpectedRefill(
            previous: previous,
            oldWindow: previous.weekly,
            newWindow: current.weekly,
            observedAt: current.fetchedAt
        ) {
            mask.insert(.weekly)
        }
        return mask
    }

    public static func resetCreditsAdded(previous: Int, current: Int) -> Int {
        guard previous >= 0, current > previous else { return 0 }
        return current - previous
    }

    public static func withoutUserResetRefills(
        _ refills: RefillMask,
        observedAt: Date,
        fiveHourSuppressUntil: Date?,
        weeklySuppressUntil: Date?
    ) -> RefillMask {
        var result = refills
        if let fiveHourSuppressUntil, fiveHourSuppressUntil > observedAt {
            result.remove(.fiveHour)
        }
        if let weeklySuppressUntil, weeklySuppressUntil > observedAt {
            result.remove(.weekly)
        }
        return result
    }

    public static func expectedResetDate(
        snapshot: UsageSnapshot,
        window: UsageWindow?
    ) -> Date? {
        guard let window else { return nil }
        return window.effectiveResetDate(relativeTo: snapshot.fetchedAt)
    }

    public static func userResetSuppressionDeadline(
        snapshot: UsageSnapshot,
        window: UsageWindow?,
        now: Date
    ) -> Date? {
        guard let window, window.usedPercent > 0 else { return nil }
        if let expected = expectedResetDate(snapshot: snapshot, window: window), expected > now {
            return expected
        }
        return now.addingTimeInterval(unknownUserResetSuppression)
    }

    private static func isUnexpectedRefill(
        previous: UsageSnapshot,
        oldWindow: UsageWindow?,
        newWindow: UsageWindow?,
        observedAt: Date
    ) -> Bool {
        guard let oldWindow, let newWindow else { return false }
        guard oldWindow.usedPercent > 0, newWindow.usedPercent == 0 else { return false }
        guard let expectedReset = expectedResetDate(snapshot: previous, window: oldWindow) else {
            return false
        }
        return expectedReset > observedAt
    }
}
