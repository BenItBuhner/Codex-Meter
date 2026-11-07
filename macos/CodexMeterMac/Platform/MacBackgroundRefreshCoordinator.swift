import CodexMeterCore
import Foundation

/// The Mac counterpart of the iOS `BackgroundRefreshCoordinator` (which wraps BGTaskScheduler,
/// an iOS-only API). Codex Meter keeps running in the menu bar after its window closes, so a
/// timer in the app itself is enough: it fires at the earlier of the preferred interval and
/// the next reset, runs the same refresh the iOS background task runs (usage, notifications,
/// widgets, auto-use of expiring resets), and schedules the next run from the outcome.
@MainActor
public final class BackgroundRefreshCoordinator {
    public static let taskIdentifier = "com.bukovinafilip.CodexMeter.refresh"
    public typealias RefreshHandler = @Sendable () async throws -> BackgroundRefreshOutcome

    /// Never wake more often than this, however close the next reset is.
    nonisolated static let minimumDelay: TimeInterval = 60

    private let refreshHandler: RefreshHandler
    private var pending: Task<Void, Never>?
    private var runGate = BackgroundRefreshRunGate()
    private(set) var nextRunDate: Date?

    public init(refreshHandler: @escaping RefreshHandler) {
        self.refreshHandler = refreshHandler
    }

    /// Nothing to register on the Mac; kept so the shared `AppModel` code is unchanged.
    @discardableResult
    public func register() -> Bool {
        true
    }

    public func schedule(
        preferredMinutes: Int,
        nextReset: Date?,
        now: Date = Date()
    ) throws {
        let minutes = AppSettings.allowedRefreshMinutes.contains(preferredMinutes)
            ? preferredMinutes
            : 30
        let fireDate = Self.fireDate(preferredMinutes: minutes, nextReset: nextReset, now: now)
        let futureReset = nextReset.flatMap { $0 > now ? $0 : nil }

        pending?.cancel()
        let generation = runGate.begin()
        nextRunDate = fireDate
        let delay = fireDate.timeIntervalSince(now)
        pending = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self, self.runGate.isCurrent(generation) else { return }
            await self.run(generation: generation, fallbackMinutes: minutes, fallbackReset: futureReset)
        }
    }

    public func cancel() {
        runGate.invalidate()
        pending?.cancel()
        pending = nil
        nextRunDate = nil
    }

    /// The earlier of the preferred interval and a reset still ahead, never sooner than
    /// `minimumDelay` from now.
    nonisolated static func fireDate(preferredMinutes: Int, nextReset: Date?, now: Date) -> Date {
        let intervalDate = now.addingTimeInterval(TimeInterval(preferredMinutes * 60))
        let futureReset = nextReset.flatMap { $0 > now ? $0 : nil }
        let earliest = min(intervalDate, futureReset ?? intervalDate)
        return max(earliest, now.addingTimeInterval(minimumDelay))
    }

    private func run(generation: UInt64, fallbackMinutes: Int, fallbackReset: Date?) async {
        do {
            let outcome = try await refreshHandler()
            guard runGate.isCurrent(generation) else { return }
            try? schedule(preferredMinutes: outcome.preferredMinutes, nextReset: outcome.nextReset)
        } catch {
            guard runGate.isCurrent(generation) else { return }
            try? schedule(preferredMinutes: fallbackMinutes, nextReset: fallbackReset)
        }
    }
}
