import Foundation

public enum UsagePaceSensitivity: String, Codable, Sendable, CaseIterable {
    case off
    case sensitive
    case balanced
    case relaxed
}

public enum UsagePaceWindow: String, Codable, Sendable {
    case fiveHour
    case weekly
    case monthly
}

public struct UsagePaceAssessment: Sendable, Equatable {
    public let isAvailable: Bool
    public let isAccelerated: Bool
    public let estimatedRemaining: TimeInterval
    public let estimatedTotal: TimeInterval
    public let actualRemaining: TimeInterval
    public let estimatedExhaustionAt: Date?
    public let resetAt: Date?
    public let observedDuration: TimeInterval
    public let usedPercent: Int

    public var coverageRatio: Double {
        guard isAvailable, actualRemaining > 0 else { return .infinity }
        return estimatedRemaining / actualRemaining
    }

    public static let unavailable = UsagePaceAssessment(
        isAvailable: false,
        isAccelerated: false,
        estimatedRemaining: 0,
        estimatedTotal: 0,
        actualRemaining: 0,
        estimatedExhaustionAt: nil,
        resetAt: nil,
        observedDuration: 0,
        usedPercent: 0
    )
}

/// Estimates quota exhaustion from full-window usage and optional recent local history.
public enum UsagePace {
    public static func assess(
        window: UsageWindow?,
        history: UsageHistory? = nil,
        observedAt: Date,
        now: Date = Date(),
        sensitivity: UsagePaceSensitivity = .balanced
    ) -> UsagePaceAssessment {
        let baseline = baselineAssessment(
            window: window,
            observedAt: observedAt,
            now: now,
            sensitivity: sensitivity
        )
        guard baseline.isAvailable,
              let window,
              let history,
              history.observedBurnRate > 0 else {
            return baseline
        }

        let averageRate = Double(window.usedPercent) / baseline.observedDuration
        guard averageRate.isFinite, averageRate > 0 else {
            return baseline
        }
        let observedRate = min(
            averageRate * 4,
            max(averageRate * 0.25, history.observedBurnRate)
        )
        let blendedRate = averageRate * 0.4 + observedRate * 0.6
        guard blendedRate.isFinite, blendedRate > 0,
              let resetAt = baseline.resetAt else {
            return baseline
        }

        let remainingAtObservation = max(
            0,
            Double(100 - window.usedPercent) / blendedRate
        )
        let exhaustionAt = observedAt.addingTimeInterval(remainingAtObservation)
        let estimatedRemaining = max(0, exhaustionAt.timeIntervalSince(now))
        let estimatedTotal = baseline.observedDuration + remainingAtObservation
        let policy = policy(for: sensitivity, windowDuration: TimeInterval(window.windowSeconds))
        let accelerated = sensitivity != .off
            && remainingAtObservation * 100
                <= resetAt.timeIntervalSince(observedAt) * Double(policy.maximumCoveragePercent)

        return UsagePaceAssessment(
            isAvailable: true,
            isAccelerated: accelerated,
            estimatedRemaining: estimatedRemaining,
            estimatedTotal: estimatedTotal,
            actualRemaining: baseline.actualRemaining,
            estimatedExhaustionAt: exhaustionAt,
            resetAt: resetAt,
            observedDuration: baseline.observedDuration,
            usedPercent: window.usedPercent
        )
    }

    public static func mostAcceleratedWindow(
        in snapshot: UsageSnapshot?,
        histories: [UsageHistoryKind: UsageHistory] = [:],
        now: Date = Date(),
        sensitivity: UsagePaceSensitivity = .balanced
    ) -> UsagePaceWindow? {
        guard sensitivity != .off, let snapshot else { return nil }
        let fiveHour = assess(
            window: snapshot.fiveHour,
            history: histories[.fiveHour],
            observedAt: snapshot.fetchedAt,
            now: now,
            sensitivity: sensitivity
        )
        let weekly = assess(
            window: snapshot.weekly,
            history: histories[.weekly],
            observedAt: snapshot.fetchedAt,
            now: now,
            sensitivity: sensitivity
        )
        let monthly = assess(
            window: snapshot.monthly,
            history: histories[.monthly],
            observedAt: snapshot.fetchedAt,
            now: now,
            sensitivity: sensitivity
        )

        let assessments: [(UsagePaceWindow, UsagePaceAssessment)] = [
            (.fiveHour, fiveHour),
            (.weekly, weekly),
            (.monthly, monthly)
        ]
        return assessments
            .filter(\.1.isAccelerated)
            .min { $0.1.coverageRatio < $1.1.coverageRatio }?
            .0
    }

    private static func baselineAssessment(
        window: UsageWindow?,
        observedAt: Date,
        now: Date,
        sensitivity: UsagePaceSensitivity
    ) -> UsagePaceAssessment {
        guard let window,
              window.windowSeconds > 0,
              observedAt.timeIntervalSince1970.isFinite,
              observedAt.timeIntervalSince1970 > 0,
              now.timeIntervalSince1970.isFinite,
              let resetAt = window.effectiveResetDate(relativeTo: observedAt),
              resetAt > observedAt,
              resetAt > now else {
            return .unavailable
        }

        let windowDuration = TimeInterval(window.windowSeconds)
        let windowStart = resetAt.addingTimeInterval(-windowDuration)
        let elapsed = observedAt.timeIntervalSince(windowStart)
        guard elapsed.isFinite,
              elapsed > 0,
              elapsed <= windowDuration,
              window.usedPercent > 0 else {
            return .unavailable
        }

        let policy = policy(for: sensitivity, windowDuration: windowDuration)
        guard elapsed >= policy.minimumElapsed,
              window.usedPercent >= policy.minimumUsedPercent else {
            return .unavailable
        }

        let estimatedRemainingAtObservation =
            elapsed * Double(100 - window.usedPercent) / Double(window.usedPercent)
        let estimatedTotal = elapsed * 100 / Double(window.usedPercent)
        guard estimatedRemainingAtObservation.isFinite, estimatedTotal.isFinite else {
            return .unavailable
        }
        let exhaustionAt = observedAt.addingTimeInterval(estimatedRemainingAtObservation)
        let actualRemainingAtObservation = resetAt.timeIntervalSince(observedAt)
        let accelerated = sensitivity != .off
            && estimatedRemainingAtObservation * 100
                <= actualRemainingAtObservation * Double(policy.maximumCoveragePercent)

        return UsagePaceAssessment(
            isAvailable: true,
            isAccelerated: accelerated,
            estimatedRemaining: max(0, exhaustionAt.timeIntervalSince(now)),
            estimatedTotal: estimatedTotal,
            actualRemaining: max(0, resetAt.timeIntervalSince(now)),
            estimatedExhaustionAt: exhaustionAt,
            resetAt: resetAt,
            observedDuration: elapsed,
            usedPercent: window.usedPercent
        )
    }

    private static func policy(
        for sensitivity: UsagePaceSensitivity,
        windowDuration: TimeInterval
    ) -> (
        minimumUsedPercent: Int,
        minimumElapsed: TimeInterval,
        maximumCoveragePercent: Int
    ) {
        switch sensitivity {
        case .sensitive:
            (2, max(2 * 60, windowDuration / 400), 100)
        case .relaxed:
            (10, max(10 * 60, windowDuration / 100), 50)
        case .off, .balanced:
            (5, max(5 * 60, windowDuration / 200), 75)
        }
    }
}
