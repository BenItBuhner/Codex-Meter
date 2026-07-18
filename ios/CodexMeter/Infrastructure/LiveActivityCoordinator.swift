import ActivityKit
import CodexMeterCore
import Foundation

/// Starts, updates, and ends the Codex usage Live Activity.
@MainActor
final class LiveActivityCoordinator {
    static let shared = LiveActivityCoordinator()

    private static let dismissedWindowTokenKey = "codex-meter.live-monitor.dismissed-window"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var isSupported: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    var isActive: Bool {
        !Activity<UsageLiveMonitorAttributes>.activities.isEmpty
    }

    var activeCount: Int {
        Activity<UsageLiveMonitorAttributes>.activities.count
    }

    func start(
        usage: UsageSnapshot,
        creditCount: Int,
        planLabel: String,
        isDemo: Bool,
        isCached: Bool
    ) async throws {
        guard isSupported else {
            throw LiveActivityError.disabled
        }
        let state = UsageLiveMonitorState.from(
            usage: usage,
            creditCount: creditCount,
            planLabel: planLabel,
            isDemo: isDemo,
            isCached: isCached
        )
        defaults.removeObject(forKey: Self.dismissedWindowTokenKey)

        if let existing = Activity<UsageLiveMonitorAttributes>.activities.first {
            let content = ActivityContent(state: state, staleDate: state.nextResetAt)
            await existing.update(content)
            return
        }

        let attributes = UsageLiveMonitorAttributes(startedAt: Date())
        let content = ActivityContent(state: state, staleDate: state.nextResetAt)
        _ = try Activity.request(
            attributes: attributes,
            content: content,
            pushType: nil
        )
    }

    func update(
        usage: UsageSnapshot,
        creditCount: Int,
        planLabel: String,
        isDemo: Bool,
        isCached: Bool,
        now: Date = Date()
    ) async {
        let activities = Activity<UsageLiveMonitorAttributes>.activities
        guard !activities.isEmpty else { return }

        let state = UsageLiveMonitorState.from(
            usage: usage,
            creditCount: creditCount,
            planLabel: planLabel,
            isDemo: isDemo,
            isCached: isCached,
            now: now
        )

        if let next = state.nextResetAt, next <= now {
            await end(dismissed: false)
            return
        }

        let content = ActivityContent(state: state, staleDate: state.nextResetAt)
        for activity in activities {
            await activity.update(content)
        }
    }

    /// Ends all usage monitor activities.
    /// - Parameter dismissed: when true, suppress auto-start for the current window token.
    func end(
        dismissed: Bool,
        usage: UsageSnapshot? = nil
    ) async {
        if dismissed, let usage {
            let five = usage.fiveHour?.effectiveResetDate(relativeTo: usage.fetchedAt)
            let weekly = usage.weekly?.effectiveResetDate(relativeTo: usage.fetchedAt)
            defaults.set(
                LiveMonitorAutoStart.windowToken(fiveHourReset: five, weeklyReset: weekly),
                forKey: Self.dismissedWindowTokenKey
            )
        }
        for activity in Activity<UsageLiveMonitorAttributes>.activities {
            let content = ActivityContent(
                state: activity.content.state,
                staleDate: nil
            )
            await activity.end(content, dismissalPolicy: .immediate)
        }
    }

    func shouldAutoStart(
        settings: AppSettings,
        usage: UsageSnapshot
    ) -> Bool {
        guard settings.liveMonitorAutoStartEnabled else { return false }
        guard !isActive else { return false }

        let five = usage.fiveHour?.effectiveResetDate(relativeTo: usage.fetchedAt)
        let weekly = usage.weekly?.effectiveResetDate(relativeTo: usage.fetchedAt)
        let token = LiveMonitorAutoStart.windowToken(fiveHourReset: five, weeklyReset: weekly)
        if defaults.string(forKey: Self.dismissedWindowTokenKey) == token {
            return false
        }

        return LiveMonitorAutoStart.shouldStart(
            enabled: true,
            metric: settings.liveMonitorAutoStartMetric.rawValue,
            threshold: settings.liveMonitorAutoStartThreshold,
            fiveHour: usage.fiveHour,
            weekly: usage.weekly
        )
    }
}

enum LiveActivityError: LocalizedError {
    case disabled
    case noUsage

    var errorDescription: String? {
        switch self {
        case .disabled:
            return "Live Activities are disabled. Enable them in Settings → Codex Meter."
        case .noUsage:
            return "No usage data available to start the monitor."
        }
    }
}
