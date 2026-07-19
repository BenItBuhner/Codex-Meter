import CodexMeterCore
import Foundation
import WatchConnectivity

/// Pushes sanitized usage snapshots to a paired Apple Watch.
@MainActor
final class PhoneWatchBridge: NSObject, WCSessionDelegate {
    static let shared = PhoneWatchBridge()

    private var lastSnapshot: SharedWidgetSnapshot?
    private var didActivate = false

    func activateIfNeeded() {
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

    /// Best-effort push. Safe to call after every widget snapshot write.
    func push(snapshot: SharedWidgetSnapshot) {
        lastSnapshot = snapshot
        activateIfNeeded()
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        guard session.isPaired else { return }

        do {
            let context = try WatchSyncPayload.applicationContext(for: snapshot)
            try session.updateApplicationContext(context)
            if session.isComplicationEnabled {
                session.transferCurrentComplicationUserInfo(context)
            }
        } catch {
            // Watch is optional; never fail phone refresh paths.
        }
    }

    // MARK: - WCSessionDelegate

    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        Task { @MainActor in
            if activationState == .activated, let lastSnapshot {
                push(snapshot: lastSnapshot)
            }
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any],
        replyHandler: @escaping ([String: Any]) -> Void
    ) {
        // Acknowledge on the WCSession queue, then re-push last snapshot on main.
        replyHandler(["ok": true])
        Task { @MainActor in
            PhoneWatchBridge.shared.repushLastSnapshot()
        }
    }

    fileprivate func repushLastSnapshot() {
        if let lastSnapshot {
            push(snapshot: lastSnapshot)
        }
    }
}
