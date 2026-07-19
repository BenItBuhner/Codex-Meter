import CodexMeterCore
import Foundation
import WatchConnectivity

/// Pushes sanitized usage snapshots to a paired Apple Watch.
@MainActor
final class PhoneWatchBridge: NSObject, WCSessionDelegate {
    static let shared = PhoneWatchBridge()

    var onRefreshRequested: (() -> Void)?
    private var lastEnvelope: WatchSnapshotEnvelope?
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
    func push(snapshot: SharedWidgetSnapshot, nextCreditExpiry: Date? = nil) {
        let envelope = WatchSnapshotEnvelope(snapshot: snapshot, nextCreditExpiry: nextCreditExpiry)
        lastEnvelope = envelope
        activateIfNeeded()
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        guard session.isPaired else { return }

        do {
            let context = [WatchSyncPayload.contextKey: try WatchSyncPayload.encode(envelope)]
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
            if activationState == .activated, let lastEnvelope {
                push(envelope: lastEnvelope)
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
        let requestedRefresh = message["action"] as? String == "refresh_snapshot"
        replyHandler(["ok": true, "refreshing": requestedRefresh])
        Task { @MainActor in
            if requestedRefresh {
                PhoneWatchBridge.shared.onRefreshRequested?()
            } else {
                PhoneWatchBridge.shared.repushLastSnapshot()
            }
        }
    }

    fileprivate func repushLastSnapshot() {
        if let lastEnvelope {
            push(envelope: lastEnvelope)
        }
    }

    private func push(envelope: WatchSnapshotEnvelope) {
        lastEnvelope = envelope
        activateIfNeeded()
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired else { return }
        do {
            let context = [WatchSyncPayload.contextKey: try WatchSyncPayload.encode(envelope)]
            try session.updateApplicationContext(context)
            if session.isComplicationEnabled { session.transferCurrentComplicationUserInfo(context) }
        } catch {}
    }
}
