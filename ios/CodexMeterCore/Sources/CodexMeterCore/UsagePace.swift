import Foundation

public enum UsagePaceSensitivity: String, Codable, CaseIterable, Identifiable, Sendable {
    case off
    case sensitive
    case balanced
    case relaxed

    public var id: String { rawValue }
    public var warningsEnabled: Bool { self != .off }
}

public struct UsagePaceAssessment: Codable, Equatable, Sendable {
    public let isAvailable: Bool
    public let isAccelerated: Bool
    public let estimatedRemaining: TimeInterval
    public let estimatedTotal: TimeInterval
    public let actualRemaining: TimeInterval
    public let estimatedExhaustionAt: Date?
    public let resetAt: Date?
    public let observedDuration: TimeInterval
    public let usedPercent: Int

    public static let unavailable = Self(
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

    public var coverageRatio: Double {
        guard isAvailable, actualRemaining > 0 else { return .infinity }
        return estimatedRemaining / actualRemaining
    }
}

public enum UsagePaceWindow: String, Codable, Sendable {
    case fiveHour
    case weekly
}

/// Full-window average pace estimation, matching the v2.4.3 Android sample gates.
public enum UsagePace {
    public static func assess(
        _ window: UsageWindow?,
        observedAt: Date,
        now: Date = Date(),
        sensitivity: UsagePaceSensitivity
    ) -> UsagePaceAssessment {
        guard let window, window.windowSeconds > 0 else { return .unavailable }
        let windowDuration = TimeInterval(window.windowSeconds)
        guard let resetAt = window.effectiveResetDate(relativeTo: observedAt),
              resetAt > observedAt,
              resetAt > now else { return .unavailable }

        let windowStart = resetAt.addingTimeInterval(-windowDuration)
        let elapsed = observedAt.timeIntervalSince(windowStart)
        guard elapsed > 0, elapsed <= windowDuration, window.usedPercent > 0 else {
            return .unavailable
        }

        let policy = policy(for: sensitivity, windowDuration: windowDuration)
        guard elapsed >= policy.minimumElapsed,
              window.usedPercent >= policy.minimumUsedPercent else {
            return .unavailable
        }

        let estimatedRemainingAtObservation = elapsed * Double(100 - window.usedPercent)
            / Double(window.usedPercent)
        let estimatedTotal = elapsed * 100 / Double(window.usedPercent)
        let estimatedExhaustionAt = observedAt.addingTimeInterval(estimatedRemainingAtObservation)
        let actualRemainingAtObservation = resetAt.timeIntervalSince(observedAt)
        let accelerated = sensitivity.warningsEnabled
            && estimatedRemainingAtObservation * 100
                <= actualRemainingAtObservation * Double(policy.maximumCoveragePercent)

        return UsagePaceAssessment(
            isAvailable: true,
            isAccelerated: accelerated,
            estimatedRemaining: max(0, estimatedExhaustionAt.timeIntervalSince(now)),
            estimatedTotal: max(0, estimatedTotal),
            actualRemaining: max(0, resetAt.timeIntervalSince(now)),
            estimatedExhaustionAt: estimatedExhaustionAt,
            resetAt: resetAt,
            observedDuration: elapsed,
            usedPercent: window.usedPercent
        )
    }

    public static func mostAcceleratedWindow(
        in snapshot: UsageSnapshot?,
        now: Date = Date(),
        sensitivity: UsagePaceSensitivity
    ) -> UsagePaceWindow? {
        guard let snapshot, sensitivity.warningsEnabled else { return nil }
        let five = assess(snapshot.fiveHour, observedAt: snapshot.fetchedAt, now: now, sensitivity: sensitivity)
        let week = assess(snapshot.weekly, observedAt: snapshot.fetchedAt, now: now, sensitivity: sensitivity)
        switch (five.isAccelerated, week.isAccelerated) {
        case (false, false): return nil
        case (true, false): return .fiveHour
        case (false, true): return .weekly
        case (true, true): return five.coverageRatio <= week.coverageRatio ? .fiveHour : .weekly
        }
    }

    private struct Policy {
        let minimumUsedPercent: Int
        let minimumElapsed: TimeInterval
        let maximumCoveragePercent: Int
    }

    private static func policy(
        for sensitivity: UsagePaceSensitivity,
        windowDuration: TimeInterval
    ) -> Policy {
        switch sensitivity {
        case .sensitive:
            Policy(minimumUsedPercent: 2, minimumElapsed: max(120, windowDuration / 400), maximumCoveragePercent: 100)
        case .relaxed:
            Policy(minimumUsedPercent: 10, minimumElapsed: max(600, windowDuration / 100), maximumCoveragePercent: 50)
        case .off, .balanced:
            Policy(minimumUsedPercent: 5, minimumElapsed: max(300, windowDuration / 200), maximumCoveragePercent: 75)
        }
    }
}
