import CodexMeterCore
import Foundation
import WatchConnectivity

/// Receives sanitized snapshots from the iPhone app.
@MainActor
final class WatchSessionReceiver: NSObject, WCSessionDelegate {
    static let shared = WatchSessionReceiver()

    private var didActivate = false

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        if session.delegate == nil {
            session.delegate = self
        }
        if session.activationState != .activated && !didActivate {
            didActivate = true
            session.activate()
        }
    }

    func requestRefreshFromPhone() {
        activate()
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return }
        session.sendMessage(["action": "refresh_snapshot"], replyHandler: { _ in }, errorHandler: { _ in })
    }

    private func ingest(context: [String: Any]) {
        guard let snapshot = try? WatchSyncPayload.snapshot(fromApplicationContext: context) else {
            return
        }
        WatchSnapshotStore.shared.apply(snapshot)
    }

    // MARK: - WCSessionDelegate

    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        guard activationState == .activated else { return }
        let context = session.receivedApplicationContext
        Task { @MainActor in
            if !context.isEmpty {
                ingest(context: context)
            }
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveApplicationContext applicationContext: [String: Any]
    ) {
        Task { @MainActor in
            ingest(context: applicationContext)
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveUserInfo userInfo: [String: Any] = [:]
    ) {
        Task { @MainActor in
            ingest(context: userInfo)
        }
    }
}
