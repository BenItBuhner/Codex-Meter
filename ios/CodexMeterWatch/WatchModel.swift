import CodexMeterCore
import Foundation
import Observation
import WatchConnectivity
import WidgetKit

/// The watch's copy of the iPhone's provider list, kept in sync over WatchConnectivity.
///
/// The iPhone stays the only place that signs in and talks to providers; the watch receives
/// a sanitized summary (no tokens or emails) as the application context, saves it in the
/// shared App Group so complications can read it, and asks the iPhone to refresh on demand.
@MainActor
@Observable
final class WatchModel: NSObject {
    private(set) var payload: WatchSyncPayload?
    private(set) var followedID: String?
    private(set) var isRefreshing = false
    private(set) var isPhoneReachable = false
    private(set) var refreshError: String?

    /// `-watch-demo` fills the watch with sample providers (screenshots, App Review, trying
    /// the app without an iPhone). `-watch-provider <id>` opens on that provider and
    /// `-watch-complications` opens the complication gallery.
    let isDemo: Bool
    let startsInGallery: Bool
    private let launchProvider: String?
    @ObservationIgnored private let store = WatchPayloadStore()
    @ObservationIgnored private var hasStarted = false

    override init() {
        let arguments = ProcessInfo.processInfo.arguments
        isDemo = arguments.contains("-watch-demo")
        startsInGallery = arguments.contains("-watch-complications")
        if let index = arguments.firstIndex(of: "-watch-provider"), index + 1 < arguments.count {
            launchProvider = arguments[index + 1]
        } else {
            launchProvider = nil
        }
        super.init()
    }

    var followed: WatchProviderSummary? {
        payload?.resolve(watchChoice: followedID)
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        if isDemo {
            store.save(.demo())
        }
        if let launchProvider {
            store.watchChoice = launchProvider
        }
        payload = store.load()
        followedID = store.watchChoice
        guard !isDemo, WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    /// Makes `id` the provider this watch shows and its complications follow.
    func follow(_ id: String) {
        guard id != followed?.id else { return }
        store.watchChoice = id
        followedID = id
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Asks the iPhone to refresh every provider; the new data arrives as a context update.
    func requestRefresh() {
        guard !isDemo else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else {
            refreshError = "Open Codex Meter on your iPhone to refresh."
            return
        }
        refreshError = nil
        isRefreshing = true
        session.sendMessage([WatchSyncPayload.refreshMessageKey: true], replyHandler: { _ in
            Task { @MainActor in self.isRefreshing = false }
        }, errorHandler: { error in
            let message = error.localizedDescription
            Task { @MainActor in
                self.isRefreshing = false
                self.refreshError = message
            }
        })
    }

    fileprivate func apply(_ data: Data?) {
        guard let data, let incoming = try? WatchSyncPayload.decode(data) else { return }
        if store.save(incoming) {
            payload = incoming
            WidgetCenter.shared.reloadAllTimelines()
        }
        isRefreshing = false
    }

    fileprivate func updateReachability(_ reachable: Bool) {
        isPhoneReachable = reachable
        if reachable { refreshError = nil }
    }
}

extension WatchModel: WCSessionDelegate {
    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: (any Error)?
    ) {
        let data = session.receivedApplicationContext[WatchSyncPayload.contextKey] as? Data
        let reachable = session.isReachable
        Task { @MainActor in
            self.apply(data)
            self.updateReachability(reachable)
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveApplicationContext applicationContext: [String: Any]
    ) {
        let data = applicationContext[WatchSyncPayload.contextKey] as? Data
        Task { @MainActor in self.apply(data) }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in self.updateReachability(reachable) }
    }
}
