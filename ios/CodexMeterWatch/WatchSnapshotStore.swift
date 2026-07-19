import CodexMeterCore
import Foundation
import Observation
import WidgetKit

@MainActor
@Observable
final class WatchSnapshotStore {
    static let shared = WatchSnapshotStore()

    var snapshot: SharedWidgetSnapshot
    var lastUpdated: Date?
    var nextCreditExpiry: Date?
    var phoneReachable = false

    private let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults(suiteName: "group.com.bukovinafilip.CodexMeter") ?? .standard) {
        self.defaults = defaults
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ui-testing-demo") {
            let now = Date()
            self.snapshot = SharedWidgetSnapshot(
                mode: .demo,
                fetchedAt: now,
                planType: "plus",
                fiveHour: UsageWindow(usedPercent: 38, windowSeconds: 18_000, resetAt: now.addingTimeInterval(7_200)),
                weekly: UsageWindow(usedPercent: 64, windowSeconds: 604_800, resetAt: now.addingTimeInterval(280_000)),
                resetCreditsAvailable: 2,
                freshness: .fresh
            )
            self.lastUpdated = now
            self.nextCreditExpiry = now.addingTimeInterval(5 * 24 * 60 * 60)
            return
        }
#endif
        if let data = defaults.data(forKey: WatchSyncPayload.watchDefaultsKey),
           let envelope = try? WatchSyncPayload.decodeEnvelope(data) {
            self.snapshot = envelope.snapshot
            self.lastUpdated = envelope.sourceTime
            self.nextCreditExpiry = envelope.nextCreditExpiry
        } else {
            self.snapshot = .signedOut
        }
    }

    func apply(_ envelope: WatchSnapshotEnvelope) {
        self.snapshot = envelope.snapshot
        self.lastUpdated = envelope.sourceTime
        self.nextCreditExpiry = envelope.nextCreditExpiry
        if let data = try? WatchSyncPayload.encode(envelope) {
            defaults.set(data, forKey: WatchSyncPayload.watchDefaultsKey)
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    func requestPhoneRefresh() {
        WatchSessionReceiver.shared.requestRefreshFromPhone()
    }

    func setPhoneReachable(_ reachable: Bool) {
        phoneReachable = reachable
    }

    func isStale(at now: Date = Date()) -> Bool {
        if snapshot.freshness != .fresh { return true }
        guard let fetchedAt = snapshot.fetchedAt else { return true }
        return now.timeIntervalSince(fetchedAt) > 2 * 60 * 60
    }
}
