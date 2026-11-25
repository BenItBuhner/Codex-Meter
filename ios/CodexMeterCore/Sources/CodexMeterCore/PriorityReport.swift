import Foundation

/// One line of the Priority tab.
public struct PriorityItem: Sendable, Equatable, Identifiable {
    public let provider: UsageProvider
    public let title: String
    public let detail: String
    /// Left in the window, 0 to 100, for a meter bar; `nil` for rows without one.
    public let remainingPercent: Int?
    /// When the row's event happens (reset, run-out, expiry, listing), for ordering.
    public let at: Date?
    /// A small line above the title: "Official incident", "Performance warning".
    public let eyebrow: String?
    /// Set on rows the person can dismiss (Recent activity, New models): "event:<key>" or
    /// "model:<id>". Nothing else can be dismissed.
    public let dismissKey: String?
    /// Where the row leads instead of the provider's tab (an official status page).
    public let link: URL?

    public init(provider: UsageProvider, title: String, detail: String, remainingPercent: Int? = nil, at: Date? = nil,
                eyebrow: String? = nil, dismissKey: String? = nil, link: URL? = nil) {
        self.provider = provider
        self.title = title
        self.detail = detail
        self.remainingPercent = remainingPercent
        self.at = at
        self.eyebrow = eyebrow
        self.dismissKey = dismissKey
        self.link = link
    }

    public var id: String { dismissKey ?? "\(provider.rawValue)|\(title)" }

    public var isDismissible: Bool { dismissKey != nil }
}

/// One provider's facts for the Priority tab.
public struct PriorityInput: Sendable {
    public let provider: UsageProvider
    public let usage: UsageSnapshot?
    /// Codex reset credits, or Claude Code's saved resets in the same shape.
    public let credits: ResetCreditsSnapshot?
    /// Claude Code's saved resets, for their names and whether one can be used now.
    public let savedResets: ClaudeSavedResets?
    /// The official status feed's latest reading, when the provider has one.
    public let status: ProviderStatus?

    public init(provider: UsageProvider, usage: UsageSnapshot?, credits: ResetCreditsSnapshot? = nil,
                savedResets: ClaudeSavedResets? = nil, status: ProviderStatus? = nil) {
        self.provider = provider
        self.usage = usage
        self.credits = credits
        self.savedResets = savedResets
        self.status = status
    }
}

/// Priority's sections, in their default order. Edit mode switches and reorders them; the raw
/// values are stored (and match Android's keys), so keep them when the labels change.
public enum PrioritySectionKey: String, CaseIterable, Sendable, Identifiable {
    case alerts
    case news
    case watch
    case usedUp = "used_up"
    case ready
    case comingUp = "coming_up"
    case models

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .alerts: "Outages and slowdowns"
        case .news: "Recent activity"
        case .watch: "Limits to watch"
        case .usedUp: "Used up"
        case .ready: "Saved resets you can use"
        case .comingUp: "Coming up"
        case .models: "New models"
        }
    }

    /// The order Edit mode saved, then any section it did not know about yet in its default place.
    public static func order(saved: [String]) -> [PrioritySectionKey] {
        var order: [PrioritySectionKey] = []
        for raw in saved {
            if let key = PrioritySectionKey(rawValue: raw), !order.contains(key) { order.append(key) }
        }
        for (index, key) in allCases.enumerated() where !order.contains(key) {
            order.insert(key, at: min(index, order.count))
        }
        return order
    }
}

/// One shown section of the Priority tab.
public struct PrioritySection: Sendable, Equatable, Identifiable {
    public let key: PrioritySectionKey
    public let items: [PriorityItem]

    public var id: String { key.rawValue }
    public var title: String { key.label }
    /// Dismiss all is offered only when a list has more than one row to dismiss.
    public var offersDismissAll: Bool { items.filter(\.isDismissible).count > 1 }
}

/// The Priority tab, ported from Android's `PriorityPage` (and drawn like its Liquid Glass page):
/// only what needs you, across every signed-in service, each section shown only while it has
/// something. **Outages and slowdowns** (official incidents, then slowdowns), **Recent activity**
/// (the last day's surprise refills and resets added or used; dismissible), **Limits to watch**
/// (running out before they reset, with some left), **Used up** (soonest reset first), **Saved
/// resets you can use** (a provider with a used-up limit and resets it can spend), **Coming up**
/// (a monthly limit renewing or resets expiring within three days) and **New models**
/// (dismissible).
public struct PriorityReport: Sendable, Equatable {
    public static let comingUpWindow: TimeInterval = 3 * 86_400
    public static let signInLine = "Sign in to a provider to see the limits to watch here."
    public static let allClearLine = "Nothing needs attention right now."
    public static let allFilteredLine = "Everything here is hidden. Tap Edit to show sections again."

    /// The sections with something in them, in the person's order, without the hidden ones.
    public let sections: [PrioritySection]
    /// How many rows each section has now, hidden or not, for Edit mode.
    public let sizes: [PrioritySectionKey: Int]
    /// True when a hidden section has something that would show now.
    public let filtered: Bool
    /// False before any provider is signed in.
    public let signedInAnywhere: Bool

    public var isEmpty: Bool { sections.isEmpty }

    /// The start of a Recent activity row's `dismissKey`; the rest is the event's key.
    public static let eventDismissPrefix = "event:"

    /// The line for an empty page: sign in, all clear, or all filtered out.
    public var emptyLine: String {
        if !signedInAnywhere { return Self.signInLine }
        return filtered ? Self.allFilteredLine : Self.allClearLine
    }

    /// The same report without rows whose `dismissKey` is in `keys`, for dismissals that are not
    /// stored (demo mode). A section left empty goes away.
    public func without(_ keys: Set<String>) -> PriorityReport {
        var sizes = sizes
        let kept = sections.compactMap { section -> PrioritySection? in
            let items = section.items.filter { !keys.contains($0.dismissKey ?? "") }
            sizes[section.key] = items.count
            return items.isEmpty ? nil : PrioritySection(key: section.key, items: items)
        }
        return PriorityReport(sections: kept, sizes: sizes, filtered: filtered, signedInAnywhere: signedInAnywhere)
    }

    public func items(_ key: PrioritySectionKey) -> [PriorityItem] {
        sections.first { $0.key == key }?.items ?? []
    }

    public var watch: [PriorityItem] { items(.watch) }
    public var usedUp: [PriorityItem] { items(.usedUp) }
    public var comingUp: [PriorityItem] { items(.comingUp) }
    public var newModels: [PriorityItem] { items(.models) }

    /// Rows that need attention now: outages and slowdowns, limits to watch and used-up limits.
    public var attentionCount: Int {
        items(.alerts).count + watch.count + usedUp.count
    }

    public static func build(
        _ providers: [PriorityInput],
        events: [ProviderEvents.Event] = [],
        slowdowns: [PriorityItem] = [],
        newModels: [PriorityItem] = [],
        order: [PrioritySectionKey] = PrioritySectionKey.allCases,
        hidden: Set<PrioritySectionKey> = [],
        now: Date = Date(),
        sensitivity: UsagePaceSensitivity = .balanced,
        calendar: Calendar = .current,
        locale: Locale = .current,
        uses24HourClock: Bool = false
    ) -> PriorityReport {
        var lists: [PrioritySectionKey: [PriorityItem]] = [:]
        let names = Set(providers.map(\.provider))

        // Outages first, most severe first, then slowdowns.
        var downtime: [(Int, PriorityItem)] = []
        var performance: [(Int, PriorityItem)] = []
        for input in providers {
            guard let status = input.status, status.shouldShowCard else { continue }
            let degraded = status.state == .degraded
            let item = PriorityItem(provider: input.provider, title: status.headline(input.provider.displayName),
                                    detail: status.detail, eyebrow: degraded ? "Performance warning" : "Official incident",
                                    link: status.link)
            if degraded { performance.append((status.state.severity, item)) } else { downtime.append((status.state.severity, item)) }
        }
        let alertRows = stableByDescending(downtime) + stableByDescending(performance)
            + slowdowns.map { PriorityItem(provider: $0.provider, title: $0.title, detail: $0.detail,
                                           eyebrow: "Performance warning") }
        lists[.alerts] = alertRows

        lists[.news] = ProviderEvents.recent(events, now: now).compactMap { event in
            guard let provider = UsageProvider(rawValue: event.provider), names.contains(provider), !event.line.isEmpty
            else { return nil }
            return PriorityItem(provider: provider, title: event.line,
                                detail: "\(provider.displayName) · \(ProviderEvents.ago(event.at, now: now))",
                                at: event.at, dismissKey: PriorityReport.eventDismissPrefix + event.key)
        }

        var watch: [PriorityItem] = []
        var usedUp: [PriorityItem] = []
        var soon: [PriorityItem] = []
        var ready: [PriorityItem] = []
        var usedUpProviders: Set<UsageProvider> = []
        var renewing: Set<UsageProvider> = []
        for input in providers {
            let provider = input.provider
            guard let usage = input.usage else { continue }
            for (label, window) in limits(provider, usage) {
                guard window.windowSeconds > 0 else { continue }
                let resetAt = window.effectiveResetDate(relativeTo: usage.fetchedAt)
                if window.showsResetCountdown, let resetAt, resetAt <= now { continue } // stale: it has reset since
                let left = max(0, min(100, window.remainingPercent))
                let title = "\(provider.displayName) · \(label)"
                let pace = UsagePace.assess(window: window, observedAt: usage.fetchedAt, now: now, sensitivity: sensitivity)
                let fast = pace.isAvailable && pace.isAccelerated
                if left > 0, fast {
                    var detail = "\(left)% left · at this pace, runs out in \(duration(pace.estimatedRemaining))"
                    if let resetAt { detail += " · " + resetClock(resetAt, now: now, calendar: calendar, locale: locale,
                                                                 uses24HourClock: uses24HourClock).lowercased() }
                    watch.append(PriorityItem(provider: provider, title: title, detail: detail, remainingPercent: left,
                                              at: pace.estimatedExhaustionAt))
                } else if left <= 0 {
                    usedUpProviders.insert(provider)
                    let detail = resetAt.map { resetClock($0, now: now, calendar: calendar, locale: locale,
                                                          uses24HourClock: uses24HourClock) } ?? "Reset time unknown"
                    usedUp.append(PriorityItem(provider: provider, title: title, detail: detail, remainingPercent: 0, at: resetAt))
                }
                let monthly = label == "Monthly" || ProviderEvents.cadenceLabel(window) == "Monthly"
                if monthly, left > 0, !renewing.contains(provider),
                   ProviderEvents.renewsSoon(window, observedAt: usage.fetchedAt, now: now), let renews = resetAt {
                    renewing.insert(provider)
                    soon.append(PriorityItem(provider: provider, title: "\(provider.displayName) · Monthly limit",
                                             detail: "Renews in \(ProviderEvents.duration(renews.timeIntervalSince(now))) · \(left)% left",
                                             remainingPercent: left, at: renews))
                }
            }
        }
        for input in providers {
            let provider = input.provider
            let available = input.credits?.availableCount ?? 0
            guard available > 0, let credits = input.credits else { continue }
            let saved = provider == .claudeCode ? input.savedResets : nil
            let what = saved.map { $0.inventoryLabel(at: now) }.flatMap { $0.isEmpty ? nil : $0 }
                ?? "\(available) reset credit\(available == 1 ? "" : "s")"
            let title = "\(provider.displayName) · \(what)"
            if usedUpProviders.contains(provider), saved.map({ $0.usableGrant(at: now) != nil }) ?? true {
                ready.append(PriorityItem(provider: provider, title: title, detail: "Ready to use"))
            }
            let expiry = credits.credits.filter(\.isAvailable).compactMap(\.expiresAt).filter { $0 > now }.min()
            if let expiry, expiry.timeIntervalSince(now) <= ProviderEvents.expirySoon {
                soon.append(PriorityItem(provider: provider, title: title,
                                         detail: (available == 1 ? "Expires in " : "Next expires in ")
                                             + ProviderEvents.duration(expiry.timeIntervalSince(now)),
                                         at: expiry))
            }
        }
        let distant = Date.distantFuture
        lists[.watch] = watch.enumerated().sorted { a, b in
            (a.element.remainingPercent ?? 100, a.offset) < (b.element.remainingPercent ?? 100, b.offset)
        }.map(\.element)
        lists[.usedUp] = usedUp.enumerated().sorted { a, b in
            ((a.element.at ?? distant), a.offset) < ((b.element.at ?? distant), b.offset)
        }.map(\.element)
        lists[.ready] = ready
        lists[.comingUp] = soon.enumerated().sorted { a, b in
            ((a.element.at ?? distant), a.offset) < ((b.element.at ?? distant), b.offset)
        }.map(\.element)
        lists[.models] = newModels

        var sections: [PrioritySection] = []
        var sizes: [PrioritySectionKey: Int] = [:]
        var filtered = false
        for key in PrioritySectionKey.order(saved: order.map(\.rawValue)) {
            let items = lists[key] ?? []
            sizes[key] = items.count
            if items.isEmpty { continue }
            if hidden.contains(key) {
                filtered = true
                continue
            }
            sections.append(PrioritySection(key: key, items: items))
        }
        return PriorityReport(sections: sections, sizes: sizes, filtered: filtered, signedInAnywhere: !providers.isEmpty)
    }

    private static func stableByDescending(_ rows: [(Int, PriorityItem)]) -> [PriorityItem] {
        rows.enumerated().sorted { a, b in
            a.element.0 != b.element.0 ? a.element.0 > b.element.0 : a.offset < b.offset
        }.map(\.element.1)
    }

    /// A provider's 5-hour (or own short cadence), weekly and monthly limits, as Priority names them.
    static func limits(_ provider: UsageProvider, _ usage: UsageSnapshot) -> [(String, UsageWindow)] {
        var result: [(String, UsageWindow)] = []
        if let five = usage.fiveHour { result.append((ProviderEvents.shortLabel(provider, usage), five)) }
        if let weekly = usage.weekly { result.append(("Weekly", weekly)) }
        if let monthly = usage.monthly { result.append(("Monthly", monthly)) }
        return result
    }

    /// "Resets 4:14 PM", "Resets tomorrow 9:00 AM", "Resets Fri 9:00 AM" or "Resets Nov 3", the same
    /// as Android's Priority and Now Bar.
    public static func resetClock(_ date: Date, now: Date, calendar: Calendar = .current, locale: Locale = .current,
                                  uses24HourClock: Bool = false) -> String {
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: now),
                                           to: calendar.startOfDay(for: date)).day ?? 0
        let clock = uses24HourClock ? "HH:mm" : "h:mm a"
        let pattern: String
        if days <= 0 {
            pattern = clock
        } else if days == 1 {
            pattern = "'tomorrow' " + clock
        } else if days < 7 {
            pattern = "EEE " + clock
        } else {
            pattern = "MMM d"
        }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = locale
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = pattern
        return "Resets " + formatter.string(from: date)
    }

    static func windows(_ usage: UsageSnapshot) -> [(String, UsageWindow)] {
        var result: [(String, UsageWindow)] = []
        if let five = usage.fiveHour { result.append(("5-hour", five)) }
        if let weekly = usage.weekly { result.append(("weekly", weekly)) }
        if let monthly = usage.monthly { result.append(("monthly", monthly)) }
        return result
    }

    /// "2h 10m", "3d 4h", "45m".
    static func duration(_ interval: TimeInterval) -> String {
        let minutes = Int64(max(0, interval) / 60)
        let days = minutes / 1_440
        let hours = (minutes % 1_440) / 60
        if days > 0 { return "\(days)d \(hours)h" }
        if hours > 0 { return "\(hours)h \(minutes % 60)m" }
        return "\(max(1, minutes))m"
    }
}
