import Foundation

/// Estimates quota exhaustion from average consumption over the complete current window.
///
/// The elapsed sample begins at `resetAt - windowDuration`, so pauses and idle periods
/// remain part of the average instead of making a later burst look permanently representative.
///
/// Port of Android `UsagePace` (shared module, Codex Meter 2.4).
public enum UsagePace {
    public enum Sensitivity: String, Codable, Sendable, CaseIterable, Identifiable {
        case sensitive
        case balanced
        case relaxed

        public var id: String { rawValue }

        public var title: String {
            switch self {
            case .sensitive: "Sensitive"
            case .balanced: "Balanced"
            case .relaxed: "Relaxed"
            }
        }
    }

    public enum AcceleratedWindow: Int, Sendable, Equatable {
        case none = 0
        case fiveHour = 1
        case weekly = 2
    }

    public struct Assessment: Sendable, Equatable {
        public let available: Bool
        public let accelerated: Bool
        public let estimatedRemaining: TimeInterval
        public let estimatedTotal: TimeInterval
        public let actualRemaining: TimeInterval
        public let estimatedExhaustionAt: Date?
        public let resetAt: Date?
        public let observedDuration: TimeInterval
        public let usedPercent: Int

        public static let unavailable = Assessment(
            available: false,
            accelerated: false,
            estimatedRemaining: 0,
            estimatedTotal: 0,
            actualRemaining: 0,
            estimatedExhaustionAt: nil,
            resetAt: nil,
            observedDuration: 0,
            usedPercent: 0
        )

        public var coverageRatio: Double {
            guard available, actualRemaining > 0 else {
                return .infinity
            }
            return estimatedRemaining / actualRemaining
        }
    }

    public static func normalizeSensitivity(_ raw: String?) -> Sensitivity {
        guard let raw, let value = Sensitivity(rawValue: raw) else {
            return .balanced
        }
        return value
    }

    public static func assess(
        window: UsageWindow?,
        observedAt: Date,
        now: Date = Date(),
        sensitivity: Sensitivity = .balanced
    ) -> Assessment {
        guard let window, window.windowSeconds > 0 else {
            return .unavailable
        }
        let windowDuration = TimeInterval(window.windowSeconds)
        guard let resetAt = window.effectiveResetDate(relativeTo: observedAt),
              resetAt > observedAt,
              resetAt > now
        else {
            return .unavailable
        }

        let windowStart = resetAt.addingTimeInterval(-windowDuration)
        let elapsed = observedAt.timeIntervalSince(windowStart)
        guard elapsed > 0,
              elapsed <= windowDuration,
              window.usedPercent > 0
        else {
            return .unavailable
        }

        let policy = policy(for: sensitivity, windowDuration: windowDuration)
        guard elapsed >= policy.minimumElapsed,
              window.usedPercent >= policy.minimumUsedPercent
        else {
            return .unavailable
        }

        let used = Double(window.usedPercent)
        let estimatedRemainingAtObservation = elapsed * (100.0 - used) / used
        let estimatedTotal = elapsed * 100.0 / used
        let estimatedExhaustionAt = observedAt.addingTimeInterval(estimatedRemainingAtObservation)
        let actualRemainingAtObservation = resetAt.timeIntervalSince(observedAt)
        let accelerated =
            estimatedRemainingAtObservation * 100.0
            <= actualRemainingAtObservation * Double(policy.maximumCoveragePercent)

        let estimatedRemainingNow = max(0, estimatedExhaustionAt.timeIntervalSince(now))
        let actualRemainingNow = max(0, resetAt.timeIntervalSince(now))

        return Assessment(
            available: true,
            accelerated: accelerated,
            estimatedRemaining: estimatedRemainingNow,
            estimatedTotal: estimatedTotal,
            actualRemaining: actualRemainingNow,
            estimatedExhaustionAt: estimatedExhaustionAt,
            resetAt: resetAt,
            observedDuration: elapsed,
            usedPercent: window.usedPercent
        )
    }

    public static func mostAcceleratedWindow(
        snapshot: UsageSnapshot?,
        now: Date = Date(),
        sensitivity: Sensitivity = .balanced
    ) -> AcceleratedWindow {
        guard let snapshot else { return .none }
        let fiveHour = assess(
            window: snapshot.fiveHour,
            observedAt: snapshot.fetchedAt,
            now: now,
            sensitivity: sensitivity
        )
        let weekly = assess(
            window: snapshot.weekly,
            observedAt: snapshot.fetchedAt,
            now: now,
            sensitivity: sensitivity
        )
        if !fiveHour.accelerated && !weekly.accelerated { return .none }
        if !fiveHour.accelerated { return .weekly }
        if !weekly.accelerated { return .fiveHour }
        return fiveHour.coverageRatio <= weekly.coverageRatio ? .fiveHour : .weekly
    }

    private struct Policy {
        let minimumUsedPercent: Int
        let minimumElapsed: TimeInterval
        let maximumCoveragePercent: Int
    }

    private static func policy(for sensitivity: Sensitivity, windowDuration: TimeInterval) -> Policy {
        switch sensitivity {
        case .sensitive:
            return Policy(
                minimumUsedPercent: 2,
                minimumElapsed: max(2 * 60, windowDuration / 400),
                maximumCoveragePercent: 100
            )
        case .relaxed:
            return Policy(
                minimumUsedPercent: 10,
                minimumElapsed: max(10 * 60, windowDuration / 100),
                maximumCoveragePercent: 50
            )
        case .balanced:
            return Policy(
                minimumUsedPercent: 5,
                minimumElapsed: max(5 * 60, windowDuration / 200),
                maximumCoveragePercent: 75
            )
        }
    }
}
