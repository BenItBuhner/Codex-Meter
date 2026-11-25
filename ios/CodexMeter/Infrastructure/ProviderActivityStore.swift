import CodexMeterCore
import Foundation

/// What a fresh reading means for the "usage is back" notification of one limit.
enum UsageBackAction: Equatable {
    /// Ask the system to say "usage is back" at this time, replacing any earlier request.
    case schedule(id: String, title: String, body: String, at: Date)
    /// The limit came back before its reset: drop the scheduled request and say so now.
    case postNow(id: String, title: String, body: String)
    /// The limit came back after the scheduled request already fired: drop it, say nothing more.
    case cancel(id: String)
}

/// Surprise refills, newly saved resets and used resets for every provider, not only Codex: each
/// refresh is compared with the one before (`ProviderEvents`), and what it finds is kept for a day
/// for Priority's Recent activity, until the person dismisses it. The app turns the returned
/// events into notifications. A port of Android's `ProviderAlerts`, plus the always-on
/// "usage is back" bookkeeping of its `UsageRefillAlerts`.
@MainActor
final class ProviderActivityStore {
    static let shared = ProviderActivityStore()

    private static let prefix = "codexMeter.providerActivity."
    private static let eventsKey = prefix + "events"
    private static let codexCreditsKey = prefix + "codex.creditsSeen"
    /// After the person spends a reset, a full limit is their doing, not a surprise.
    static let userResetQuiet: TimeInterval = 15 * 60
    /// The limits the "usage is back" alert follows.
    private static let windows = ["five_hour", "weekly", "monthly"]

    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: Events

    /// The last day's events, newest first.
    func recent(now: Date = Date()) -> [ProviderEvents.Event] {
        ProviderEvents.recent(loadEvents(), now: now)
    }

    private func loadEvents() -> [ProviderEvents.Event] {
        guard let data = defaults.data(forKey: Self.eventsKey) else { return [] }
        // An unreadable list is an empty one.
        return (try? decoder.decode([ProviderEvents.Event].self, from: data)) ?? []
    }

    private func saveEvents(_ events: [ProviderEvents.Event]) {
        guard let data = try? encoder.encode(events) else { return }
        defaults.set(data, forKey: Self.eventsKey)
    }

    private func remember(_ event: ProviderEvents.Event?, now: Date) {
        guard let event else { return }
        saveEvents(ProviderEvents.remember(loadEvents(), event, now: now))
        DiagnosticLog.info("notifications", "provider_event", details: ["kind": event.kind, "provider": event.provider])
    }

    /// Call with the snapshot a provider had before, just after its new one is shown. Returns a
    /// surprise refill to announce, or nil.
    @discardableResult
    func usageSaved(_ provider: UsageProvider, previous: UsageSnapshot?, current: UsageSnapshot) -> ProviderEvents.Event? {
        guard let previous else { return nil }
        if let quietUntil = date(provider, "userResetUntil"), current.fetchedAt < quietUntil { return nil }
        let short = ProviderEvents.shortLabel(provider, current)
        let labels = ProviderEvents.refilledEarly(previous: previous, current: current, shortLabel: short)
        guard !labels.isEmpty else { return nil }
        let split = ProviderEvents.explainedByUses(labels, shortLabel: short, uses: uses(provider), now: current.fetchedAt)
        if !split.explained.isEmpty {
            // A reset the person used refilled these: say so on the use, and announce nothing.
            saveEvents(ProviderEvents.attachRefills(loadEvents(), provider: provider, by: split.by,
                                                    refilled: split.explained, at: current.fetchedAt))
        }
        let event = ProviderEvents.surpriseRefill(provider, labels: split.rest, at: current.fetchedAt)
        remember(event, now: current.fetchedAt)
        return event
    }

    /// Keeps a rise in Codex's reset-credit count for Priority. Codex announces its own.
    func codexCreditCount(_ count: Int, now: Date = Date()) {
        guard count >= 0 else { return }
        if defaults.object(forKey: Self.codexCreditsKey) != nil {
            remember(ProviderEvents.newResetCredits(.codex, previous: defaults.integer(forKey: Self.codexCreditsKey),
                                                    current: count, at: now), now: now)
        }
        defaults.set(count, forKey: Self.codexCreditsKey)
    }

    /// Call when a provider's saved resets arrive. The first list ever seen is only remembered,
    /// so signing in does not announce resets the person already had. The comparison is with the
    /// last list the provider sent, so one answer without the list announces nothing when it
    /// comes back. Returns newly added resets to announce, or nil.
    @discardableResult
    func savedResetsChanging(_ provider: UsageProvider, stored: ClaudeSavedResets?, current: ClaudeSavedResets,
                             now: Date = Date()) -> ProviderEvents.Event? {
        let lastKey = key(provider, "resetsLast")
        let seenKey = key(provider, "resetsSeen")
        let previous = defaults.data(forKey: lastKey).flatMap { try? decoder.decode(ClaudeSavedResets.self, from: $0) }
            ?? stored
        let known = previous != nil || defaults.bool(forKey: seenKey)
        defaults.set(true, forKey: seenKey)
        if let data = try? encoder.encode(current) { defaults.set(data, forKey: lastKey) }
        guard known else { return nil }
        let used = ProviderEvents.usedSavedResets(previous: previous, current: current, now: now)
        if !used.isEmpty { resetsUsed(provider, used, now: now) }
        let added = ProviderEvents.addedSavedResets(previous: previous, current: current, now: now)
        guard !added.isEmpty else { return nil }
        let expiry = current.nextExpiry(at: now).map { UsageFormat.absolute($0, relativeTo: now) } ?? ""
        let event = ProviderEvents.newSavedResets(provider, added: added, availableNow: current.availableCount(at: now),
                                                  expiryText: expiry, at: now)
        remember(event, now: now)
        return event
    }

    /// Saved resets were used: remember them for a few hours, list the use on Priority, and fold
    /// in any surprise refill they already explain.
    private func resetsUsed(_ provider: UsageProvider, _ used: [ProviderEvents.Use], now: Date) {
        let kept = (used + uses(provider)).filter { now.timeIntervalSince($0.at) <= ProviderEvents.useExplains }
        if let data = try? encoder.encode(kept) { defaults.set(data, forKey: key(provider, "uses")) }
        let event = ProviderEvents.resetUsed(provider, uses: used, refilled: [], at: now)
        saveEvents(ProviderEvents.recent(ProviderEvents.explainEarlierRefills(loadEvents(), useEvent: event,
                                                                            uses: used, now: now), now: now))
        DiagnosticLog.info("notifications", "provider_reset_used", details: ["provider": provider.rawValue,
                                                                             "count": String(used.count)])
    }

    /// Saved resets used in the last few hours.
    func uses(_ provider: UsageProvider) -> [ProviderEvents.Use] {
        defaults.data(forKey: key(provider, "uses")).flatMap { try? decoder.decode([ProviderEvents.Use].self, from: $0) } ?? []
    }

    /// The person just spent a reset on this provider.
    func markUserReset(_ provider: UsageProvider, now: Date = Date()) {
        defaults.set(now.addingTimeInterval(Self.userResetQuiet).timeIntervalSince1970, forKey: key(provider, "userResetUntil"))
    }

    /// Removes events from Priority for good: the person dismissed them. Takes event keys.
    func dismiss(keys: [String]) {
        let drop = Set(keys)
        saveEvents(loadEvents().filter { !drop.contains($0.key) })
        DiagnosticLog.info("user", "priority_event_dismissed", details: ["count": String(drop.count)])
    }

    /// Forgets a provider's events, saved-reset lists and waiting alerts, for sign-out. Returns
    /// the "usage is back" requests to cancel.
    func clear(_ provider: UsageProvider) -> [String] {
        saveEvents(loadEvents().filter { $0.provider != provider.rawValue })
        for suffix in ["resetsSeen", "resetsLast", "userResetUntil", "uses"] {
            defaults.removeObject(forKey: key(provider, suffix))
        }
        if provider == .codex { defaults.removeObject(forKey: Self.codexCreditsKey) }
        var ids: [String] = []
        for index in Self.windows.indices {
            defaults.removeObject(forKey: backKey(provider, index))
            defaults.removeObject(forKey: backKey(provider, index) + ".label")
            ids.append(Self.backIdentifier(provider, index))
        }
        return ids
    }

    // MARK: Usage is back

    static func backIdentifier(_ provider: UsageProvider, _ index: Int) -> String {
        "\(NotificationDeduplication.identifierPrefix)back.\(provider.rawValue).\(windows[index])"
    }

    /// What a fresh reading means for each limit's "usage is back" alert. Always on, like
    /// Android's; it only needs notification permission.
    func usageBack(_ provider: UsageProvider, snapshot: UsageSnapshot, now: Date = Date()) -> [UsageBackAction] {
        var actions: [UsageBackAction] = []
        let limits = [snapshot.fiveHour, snapshot.weekly, snapshot.monthly]
        let labels = [ProviderEvents.shortLabel(provider, snapshot), "Weekly", "Monthly"]
        for index in Self.windows.indices {
            let storeKey = backKey(provider, index)
            let armed = defaults.data(forKey: storeKey).flatMap { try? decoder.decode(UsageRefill.Armed.self, from: $0) }
            let id = Self.backIdentifier(provider, index)
            switch UsageRefill.decide(armed: armed, window: limits[index], observedAt: snapshot.fetchedAt, now: now) {
            case .none:
                continue
            case .arm(let target):
                if let data = try? encoder.encode(target) { defaults.set(data, forKey: storeKey) }
                defaults.set(labels[index], forKey: storeKey + ".label")
                if case .at(let resetAt) = target {
                    actions.append(.schedule(id: id, title: UsageRefill.title(provider.displayName),
                                             body: UsageRefill.text(provider.displayName, label: labels[index], left: nil),
                                             at: resetAt))
                } else {
                    actions.append(.cancel(id: id))
                }
            case .back(let left):
                let label = defaults.string(forKey: storeKey + ".label") ?? labels[index]
                defaults.removeObject(forKey: storeKey)
                defaults.removeObject(forKey: storeKey + ".label")
                if case .at(let resetAt)? = armed, resetAt <= now {
                    // The scheduled request already said so.
                    actions.append(.cancel(id: id))
                } else {
                    actions.append(.postNow(id: id, title: UsageRefill.title(provider.displayName),
                                            body: UsageRefill.text(provider.displayName, label: label, left: left)))
                }
            }
        }
        return actions
    }

    // MARK: Keys

    private func key(_ provider: UsageProvider, _ suffix: String) -> String {
        Self.prefix + provider.rawValue + "." + suffix
    }

    private func backKey(_ provider: UsageProvider, _ index: Int) -> String {
        key(provider, "back." + Self.windows[index])
    }

    private func date(_ provider: UsageProvider, _ suffix: String) -> Date? {
        let value = defaults.double(forKey: key(provider, suffix))
        return value > 0 ? Date(timeIntervalSince1970: value) : nil
    }
}
