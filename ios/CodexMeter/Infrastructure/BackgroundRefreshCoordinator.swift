import BackgroundTasks
import Foundation

nonisolated public struct BackgroundRefreshOutcome: Sendable, Equatable {
    public let preferredMinutes: Int
    public let nextReset: Date?

    public init(preferredMinutes: Int, nextReset: Date?) {
        self.preferredMinutes = AppSettings.allowedRefreshMinutes.contains(preferredMinutes)
            ? preferredMinutes
            : 30
        self.nextReset = nextReset
    }
}

/// A tiny value-type gate that distinguishes cancellation by app policy from
/// expiration of the currently running system task. Explicit cancellation and
/// replacement advance the generation; expiration deliberately does not.
nonisolated struct BackgroundRefreshRunGate: Sendable, Equatable {
    private(set) var currentGeneration: UInt64 = 0

    mutating func begin() -> UInt64 {
        currentGeneration &+= 1
        return currentGeneration
    }

    mutating func invalidate() {
        currentGeneration &+= 1
    }

    func isCurrent(_ generation: UInt64) -> Bool {
        generation == currentGeneration
    }
}

/// Owns the single best-effort BGAppRefresh request used by the app.
/// Registration must be called during app launch before the scene finishes
/// connecting.
@MainActor
public final class BackgroundRefreshCoordinator {
    public static let taskIdentifier = "com.bukovinafilip.CodexMeter.refresh"
    public typealias RefreshHandler = @Sendable () async throws -> BackgroundRefreshOutcome

    private let scheduler: BGTaskScheduler
    private let refreshHandler: RefreshHandler
    private var isRegistered = false
    private var runningOperation: Task<Void, Never>?
    private var fallbackPreferredMinutes = 30
    private var fallbackNextReset: Date?
    private var runGate = BackgroundRefreshRunGate()
    private var expiredGeneration: UInt64?

    public init(
        scheduler: BGTaskScheduler = .shared,
        refreshHandler: @escaping RefreshHandler
    ) {
        self.scheduler = scheduler
        self.refreshHandler = refreshHandler
    }

    @discardableResult
    public func register() -> Bool {
        guard !isRegistered else { return true }
        let registered = scheduler.register(
            forTaskWithIdentifier: Self.taskIdentifier,
            using: .main
        ) { [weak self] task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            MainActor.assumeIsolated {
                self?.handle(refreshTask)
            }
        }
        isRegistered = registered
        return registered
    }

    public func schedule(
        preferredMinutes: Int,
        nextReset: Date?,
        now: Date = Date()
    ) throws {
        let minutes = AppSettings.allowedRefreshMinutes.contains(preferredMinutes)
            ? preferredMinutes
            : 30
        let intervalDate = now.addingTimeInterval(TimeInterval(minutes * 60))
        let futureReset = nextReset.flatMap { $0 > now ? $0 : nil }
        let earliest = min(intervalDate, futureReset ?? intervalDate)
        fallbackPreferredMinutes = minutes
        fallbackNextReset = futureReset

        scheduler.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        let request = BGAppRefreshTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = earliest
        try scheduler.submit(request)
    }

    public func cancel() {
        runGate.invalidate()
        expiredGeneration = nil
        runningOperation?.cancel()
        runningOperation = nil
        scheduler.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
    }

    private func handle(_ task: BGAppRefreshTask) {
        let generation = runGate.begin()
        expiredGeneration = nil
        runningOperation?.cancel()
        let handler = refreshHandler
        let operation = Task { @MainActor [weak self] in
            do {
                let outcome = try await handler()
                if let self, self.runGate.isCurrent(generation) {
                    if Task.isCancelled {
                        if self.expiredGeneration == generation {
                            try? self.schedule(
                                preferredMinutes: self.fallbackPreferredMinutes,
                                nextReset: self.fallbackNextReset
                            )
                        }
                    } else {
                        try? self.schedule(
                            preferredMinutes: outcome.preferredMinutes,
                            nextReset: outcome.nextReset
                        )
                    }
                }
                let isCurrent = self?.runGate.isCurrent(generation) == true
                task.setTaskCompleted(success: !Task.isCancelled && isCurrent)
            } catch {
                if let self, self.runGate.isCurrent(generation) {
                    try? self.schedule(
                        preferredMinutes: self.fallbackPreferredMinutes,
                        nextReset: self.fallbackNextReset
                    )
                }
                task.setTaskCompleted(success: false)
            }
            if let self, self.runGate.isCurrent(generation) {
                self.runningOperation = nil
                if self.expiredGeneration == generation {
                    self.expiredGeneration = nil
                }
            }
        }
        runningOperation = operation
        task.expirationHandler = { [weak self, operation] in
            Task { @MainActor [weak self] in
                guard let self, self.runGate.isCurrent(generation) else {
                    operation.cancel()
                    return
                }
                self.expiredGeneration = generation
                operation.cancel()
            }
        }
    }
}
