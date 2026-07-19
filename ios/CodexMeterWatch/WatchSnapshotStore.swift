import CodexMeterCore
import Foundation
import Observation
import WidgetKit

@MainActor
@Observable
final class WatchSnapshotStore {
    static let shared = WatchSnapshotStore()

    private static let defaultsKey = "codex-meter.watch.snapshot-v1"

    var snapshot: SharedWidgetSnapshot
    var lastUpdated: Date?

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.defaultsKey),
           let decoded = try? WatchSyncPayload.decode(data) {
            self.snapshot = decoded
            self.lastUpdated = Date()
        } else {
            self.snapshot = .signedOut
        }
    }

    func apply(_ snapshot: SharedWidgetSnapshot) {
        self.snapshot = snapshot
        self.lastUpdated = Date()
        if let data = try? WatchSyncPayload.encode(snapshot) {
            defaults.set(data, forKey: Self.defaultsKey)
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    func requestPhoneRefresh() {
        WatchSessionReceiver.shared.requestRefreshFromPhone()
    }
}
