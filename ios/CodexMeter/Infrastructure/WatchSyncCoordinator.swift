import CodexMeterCore
import Foundation
import WatchConnectivity

/// Sends every provider tab to the Apple Watch app and answers its refresh requests.
///
/// The payload is a sanitized summary built in CodexMeterCore (`WatchSyncPayload`): provider
/// ids, names, plan badges, usage windows and the last error. Tokens and account emails never
/// leave the iPhone. It goes out as the WatchConnectivity application context, so the watch
/// gets the latest copy whenever it next connects, even if it was out of range.
@MainActor
final class WatchSyncCoordinator: NSObject {
    static let shared = WatchSyncCoordinator()

    private var pending: Data?
    private var lastSent: Data?
    private var refreshHandler: (@MainActor () async -> Void)?
    private var isStarted = false

    /// Activates the session once. `refresh` runs when the watch asks for fresh usage.
    func start(refresh: @escaping @MainActor () async -> Void) {
        refreshHandler = refresh
        guard !isStarted, WCSession.isSupported() else { return }
        isStarted = true
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func publish(providers: [WatchProviderSummary], selectedID: String) {
        let payload = WatchSyncPayload(providers: providers, phoneSelectedID: selectedID,
                                       sentAt: Date())
        guard let data = try? payload.encoded() else { return }
        pending = data
        sendPending()
    }

    private func sendPending() {
        guard WCSession.isSupported(), let pending else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired,
              session.isWatchAppInstalled, pending != lastSent else { return }
        do {
            try session.updateApplicationContext([WatchSyncPayload.contextKey: pending])
            lastSent = pending
            DiagnosticLog.info("watch", "context_sent", details: ["bytes": "\(pending.count)"])
        } catch {
            DiagnosticLog.error("watch", "context_failed", error: error)
        }
    }

    private func handleRefreshRequest() async {
        DiagnosticLog.info("watch", "refresh_requested")
        await refreshHandler?()
    }
}

extension WatchSyncCoordinator: WCSessionDelegate {
    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: (any Error)?
    ) {
        Task { @MainActor in self.sendPending() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        // A different watch was paired; start a session for it.
        session.activate()
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in
            self.lastSent = nil
            self.sendPending()
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any],
        replyHandler: @escaping ([String: Any]) -> Void
    ) {
        let wantsRefresh = message[WatchSyncPayload.refreshMessageKey] as? Bool == true
        replyHandler(["accepted": wantsRefresh])
        guard wantsRefresh else { return }
        Task { @MainActor in await self.handleRefreshRequest() }
    }
}
