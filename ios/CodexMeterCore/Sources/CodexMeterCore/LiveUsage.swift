import Foundation
#if os(iOS) && canImport(ActivityKit)
import ActivityKit
#endif

/// What the Live Activity shows: the one limit closest to running out across every connected
/// service, the iPhone's counterpart of Android's live notification.
public struct LiveUsageState: Codable, Hashable, Sendable {
    public var provider: String
    public var providerName: String
    /// "5-hour", "weekly" or "monthly".
    public var windowLabel: String
    public var remainingPercent: Int
    public var resetAt: Date?
    /// The limit is being used fast enough to run out before it resets.
    public var isFast: Bool
    /// "At this pace, runs out in 1h 10m", or empty.
    public var paceNote: String
    public var updatedAt: Date

    public init(provider: String, providerName: String, windowLabel: String, remainingPercent: Int,
                resetAt: Date?, isFast: Bool, paceNote: String, updatedAt: Date) {
        self.provider = provider
        self.providerName = providerName
        self.windowLabel = windowLabel
        self.remainingPercent = min(100, max(0, remainingPercent))
        self.resetAt = resetAt
        self.isFast = isFast
        self.paceNote = paceNote
        self.updatedAt = updatedAt
    }

    /// "Codex 5-hour".
    public var title: String { "\(providerName) \(windowLabel)" }

    /// Identifies the usage window, so a dismissed activity is not restarted for it.
    public var windowToken: String {
        "\(provider)|\(windowLabel)|\(Int(resetAt?.timeIntervalSince1970 ?? 0))"
    }
}

public enum LiveUsage {
    /// The tightest current window of each provider, then the tightest of those.
    public static func pick(_ inputs: [PriorityInput], now: Date = Date(),
                            sensitivity: UsagePaceSensitivity = .balanced) -> LiveUsageState? {
        var best: LiveUsageState?
        for input in inputs {
            guard let usage = input.usage else { continue }
            for (label, window) in PriorityReport.windows(usage) {
                let resetAt = window.effectiveResetDate(relativeTo: usage.fetchedAt)
                if let resetAt, resetAt <= now { continue }
                let pace = UsagePace.assess(window: window, observedAt: usage.fetchedAt, now: now, sensitivity: sensitivity)
                let fast = pace.isAvailable && pace.isAccelerated && window.remainingPercent > 0
                let state = LiveUsageState(
                    provider: input.provider.rawValue, providerName: input.provider.displayName, windowLabel: label,
                    remainingPercent: window.remainingPercent, resetAt: resetAt, isFast: fast,
                    paceNote: fast ? "At this pace, runs out in " + PriorityReport.duration(pace.estimatedRemaining) : "",
                    updatedAt: usage.fetchedAt
                )
                if best.map({ rank(state) < rank($0) }) ?? true { best = state }
            }
        }
        return best
    }

    /// Lower is more urgent: remaining allowance, with fast windows counted as half full.
    private static func rank(_ state: LiveUsageState) -> Double {
        Double(state.remainingPercent) * (state.isFast ? 0.5 : 1)
    }

    /// Whether to start the activity on its own: the window has reached the Low allowance
    /// level from Notifications, or is being used fast.
    public static func shouldAutoStart(_ state: LiveUsageState, threshold: Int, whenLow: Bool, whenFast: Bool,
                                       dismissedToken: String?) -> Bool {
        guard state.windowToken != dismissedToken, state.remainingPercent > 0 else { return false }
        return (whenLow && state.remainingPercent <= threshold) || (whenFast && state.isFast)
    }

    /// When the system may consider the activity stale: its reset, or two hours on.
    public static func staleDate(for state: LiveUsageState, now: Date = Date()) -> Date {
        min(state.resetAt ?? .distantFuture, now.addingTimeInterval(2 * 60 * 60))
    }
}

#if os(iOS) && canImport(ActivityKit)
/// The Live Activity and Dynamic Island for live usage. All content is in the state.
public struct LiveUsageAttributes: ActivityAttributes, Sendable {
    public typealias ContentState = LiveUsageState

    public init() {}
}
#endif
