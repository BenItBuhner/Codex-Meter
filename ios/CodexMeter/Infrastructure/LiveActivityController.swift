#if os(iOS)
import ActivityKit
import CodexMeterCore
import Foundation

/// Starts, updates and ends the live usage Live Activity (Lock Screen and Dynamic Island).
/// Updates come from the app's own refreshes, so the activity says when it last changed.
@MainActor
final class LiveActivityController {
    static let shared = LiveActivityController()

    private static let dismissedKey = "codex-meter.live-activity-dismissed-window-v1"
    private let defaults = UserDefaults.standard

    var isEnabledBySystem: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    var isRunning: Bool {
        Activity<LiveUsageAttributes>.activities.contains { $0.activityState == .active || $0.activityState == .stale }
    }

    /// Starts now, for the tightest limit (or replaces what is showing).
    func start(_ state: LiveUsageState) async {
        guard isEnabledBySystem else { return }
        if isRunning {
            await update(state)
            return
        }
        do {
            _ = try Activity.request(
                attributes: LiveUsageAttributes(),
                content: ActivityContent(state: state, staleDate: LiveUsage.staleDate(for: state)),
                pushType: nil
            )
            defaults.removeObject(forKey: Self.dismissedKey)
            DiagnosticLog.info("live_activity", "started", details: ["window": state.windowLabel])
        } catch {
            DiagnosticLog.error("live_activity", "start_failed", error: error)
        }
    }

    func update(_ state: LiveUsageState) async {
        for activity in Activity<LiveUsageAttributes>.activities {
            await activity.update(ActivityContent(state: state, staleDate: LiveUsage.staleDate(for: state)))
        }
    }

    /// Ends it and remembers the window, so auto-start leaves it alone until the next one.
    func stop(dismissing state: LiveUsageState?) async {
        if let state { defaults.set(state.windowToken, forKey: Self.dismissedKey) }
        for activity in Activity<LiveUsageAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// After a refresh: keep a running activity current, end it once its limit has reset and
    /// nothing is low any more, and start it on its own when the settings ask for that.
    func sync(_ state: LiveUsageState?, settings: AppSettings) async {
        // The person swiped it away from the Lock Screen: treat that as a stop for this window.
        for activity in Activity<LiveUsageAttributes>.activities where activity.activityState == .dismissed {
            defaults.set(activity.content.state.windowToken, forKey: Self.dismissedKey)
        }
        guard let state else {
            if isRunning { await stop(dismissing: nil) }
            return
        }
        if isRunning {
            await update(state)
        } else if LiveUsage.shouldAutoStart(state, threshold: settings.alertThreshold,
                                            whenLow: settings.liveActivityWhenLow,
                                            whenFast: settings.liveActivityWhenFast,
                                            dismissedToken: defaults.string(forKey: Self.dismissedKey)) {
            await start(state)
        }
    }
}
#endif
