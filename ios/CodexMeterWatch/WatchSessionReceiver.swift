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
        WatchSnapshotStore.shared.setPhoneReachable(session.isReachable)
        guard session.activationState == .activated, session.isReachable else { return }
        session.sendMessage(["action": "refresh_snapshot"], replyHandler: { _ in }, errorHandler: { _ in })
    }

    private func ingest(data: Data) {
        guard let envelope = try? WatchSyncPayload.decodeEnvelope(data) else {
            return
        }
        WatchSnapshotStore.shared.apply(envelope)
    }

    // MARK: - WCSessionDelegate

    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        guard activationState == .activated else { return }
        let data = session.receivedApplicationContext[WatchSyncPayload.contextKey] as? Data
        let reachable = session.isReachable
        Task { @MainActor in
            WatchSnapshotStore.shared.setPhoneReachable(reachable)
            if let data { ingest(data: data) }
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in
            WatchSnapshotStore.shared.setPhoneReachable(reachable)
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveApplicationContext applicationContext: [String: Any]
    ) {
        let data = applicationContext[WatchSyncPayload.contextKey] as? Data
        Task { @MainActor in
            if let data { ingest(data: data) }
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveUserInfo userInfo: [String: Any] = [:]
    ) {
        let data = userInfo[WatchSyncPayload.contextKey] as? Data
        Task { @MainActor in
            if let data { ingest(data: data) }
        }
    }
}
