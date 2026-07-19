import Foundation

#if canImport(ActivityKit) && os(iOS)
import ActivityKit
#endif

/// Pure helpers for live-monitor auto-start decisions (Android `NowBarAutoStart` parity).
public enum LiveMonitorAutoStart {
    public static func shouldStart(
        enabled: Bool,
        metric: String,
        threshold: Int,
        fiveHour: UsageWindow?,
        weekly: UsageWindow?
    ) -> Bool {
        guard enabled else { return false }
        guard [10, 25, 50, 75, 100].contains(threshold) else { return false }
        let normalized = normalizeMetric(metric)
        if normalized != "weekly",
           let fiveHour,
           fiveHour.remainingPercent <= threshold {
            return true
        }
        if normalized != "fiveHour",
           let weekly,
           weekly.remainingPercent <= threshold {
            return true
        }
        return false
    }

    public static func normalizeMetric(_ metric: String) -> String {
        switch metric {
        case "fiveHour", "five_hour": return "fiveHour"
        case "weekly": return "weekly"
        default: return "both"
        }
    }

    /// Token that identifies the current monitor window pair for dismiss suppression.
    public static func windowToken(
        fiveHourReset: Date?,
        weeklyReset: Date?
    ) -> String {
        let five = fiveHourReset.map { Int($0.timeIntervalSince1970) } ?? 0
        let week = weeklyReset.map { Int($0.timeIntervalSince1970) } ?? 0
        return "\(five).\(week)"
    }
}

public enum LiveUsageFocus: String, Codable, Hashable, Sendable {
    case automatic
    case fiveHour
    case weekly
}

public enum LiveUsageWarningState: String, Codable, Hashable, Sendable {
    case normal
    case accelerated
}

/// Codable content rendered by the usage Live Activity.
public struct LiveUsageContentState: Codable, Hashable, Sendable {
    public var planLabel: String
    public var fiveHourRemainingPercent: Int?
    public var weeklyRemainingPercent: Int?
    public var fiveHourResetAt: Date?
    public var weeklyResetAt: Date?
    public var nextResetAt: Date?
    public var creditCount: Int
    public var isDemo: Bool
    public var isCached: Bool
    public var updatedAt: Date
    public var focusedMetric: LiveUsageFocus
    public var warningState: LiveUsageWarningState

    public init(
        planLabel: String = "",
        fiveHourRemainingPercent: Int? = nil,
        weeklyRemainingPercent: Int? = nil,
        fiveHourResetAt: Date? = nil,
        weeklyResetAt: Date? = nil,
        nextResetAt: Date? = nil,
        creditCount: Int = 0,
        isDemo: Bool = false,
        isCached: Bool = false,
        updatedAt: Date = Date(),
        focusedMetric: LiveUsageFocus = .automatic,
        warningState: LiveUsageWarningState = .normal
    ) {
        self.planLabel = planLabel
        self.fiveHourRemainingPercent = fiveHourRemainingPercent
        self.weeklyRemainingPercent = weeklyRemainingPercent
        self.fiveHourResetAt = fiveHourResetAt
        self.weeklyResetAt = weeklyResetAt
        self.nextResetAt = nextResetAt
        self.creditCount = max(0, creditCount)
        self.isDemo = isDemo
        self.isCached = isCached
        self.updatedAt = updatedAt
        self.focusedMetric = focusedMetric
        self.warningState = warningState
    }

    public var primaryRemainingPercent: Int? {
        switch focusedMetric {
        case .fiveHour: fiveHourRemainingPercent
        case .weekly: weeklyRemainingPercent
        case .automatic: [fiveHourRemainingPercent, weeklyRemainingPercent].compactMap { $0 }.min()
        }
    }

    public static func from(
        usage: UsageSnapshot,
        creditCount: Int,
        planLabel: String,
        isDemo: Bool,
        isCached: Bool,
        now: Date = Date(),
        focusedMetric: LiveUsageFocus = .automatic,
        warningState: LiveUsageWarningState = .normal
    ) -> Self {
        let fiveReset = usage.fiveHour?.effectiveResetDate(relativeTo: usage.fetchedAt)
        let weeklyReset = usage.weekly?.effectiveResetDate(relativeTo: usage.fetchedAt)
        let next = usage.nextReset(after: now)
        return Self(
            planLabel: planLabel,
            fiveHourRemainingPercent: usage.fiveHour?.remainingPercent,
            weeklyRemainingPercent: usage.weekly?.remainingPercent,
            fiveHourResetAt: fiveReset,
            weeklyResetAt: weeklyReset,
            nextResetAt: next,
            creditCount: creditCount,
            isDemo: isDemo,
            isCached: isCached,
            updatedAt: now,
            focusedMetric: focusedMetric,
            warningState: warningState
        )
    }
}

public typealias UsageLiveMonitorState = LiveUsageContentState

#if canImport(ActivityKit) && os(iOS)
/// Live Activity attributes for the ongoing Codex usage monitor.
public struct LiveUsageAttributes: ActivityAttributes {
    public typealias ContentState = LiveUsageContentState

    public var startedAt: Date

    public init(startedAt: Date = Date()) {
        self.startedAt = startedAt
    }
}

public typealias UsageLiveMonitorAttributes = LiveUsageAttributes
#endif
