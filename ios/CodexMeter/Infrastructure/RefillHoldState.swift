import CodexMeterCore
import Foundation

/// The refill guard (`RefillGuard`) for one provider tab: a reading that shows a limit refilled
/// well before its reset is held, the last real numbers stay on screen, and the provider is asked
/// again after `RefillGuard.confirmDelay`. Codex keeps its own copy in `AppModel`; this one is
/// shared by Claude Code, Cursor and the added providers, like Android's `RefillHold`.
@MainActor
final class RefillHoldState {
    private(set) var heldSince: Date?
    /// After a reset is spent, its refill is expected and shows at once.
    private var skipUntil: Date?
    private var recheck: Task<Void, Never>?

    /// True when `current` should not be shown yet. `again` runs once after the confirm delay.
    func shouldHold(previous: UsageSnapshot?, current: UsageSnapshot, now: Date = Date(),
                    again: @escaping @MainActor () async -> Void) -> Bool {
        if let skipUntil, skipUntil > now {
            heldSince = nil
            return false
        }
        let verdict = RefillGuard.check(previous: previous, current: current, heldSince: heldSince, now: now)
        heldSince = verdict.heldSince
        guard verdict.hold else { return false }
        DiagnosticLog.info("refresh", "refill_held")
        if recheck == nil {
            recheck = Task { [weak self] in
                try? await Task.sleep(for: .seconds(RefillGuard.confirmDelay))
                guard let self, !Task.isCancelled else { return }
                self.recheck = nil
                guard self.heldSince != nil else { return }
                await again()
            }
        }
        return true
    }

    /// The person just spent a reset: the refill that follows is theirs.
    func skipForUserReset(now: Date = Date()) {
        skipUntil = now.addingTimeInterval(ProviderActivityStore.userResetQuiet)
        heldSince = nil
    }

    /// Forgets the hold, for sign-out or demo.
    func reset() {
        recheck?.cancel()
        recheck = nil
        heldSince = nil
        skipUntil = nil
    }
}
