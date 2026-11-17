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

    public init(provider: UsageProvider, title: String, detail: String, remainingPercent: Int? = nil, at: Date? = nil) {
        self.provider = provider
        self.title = title
        self.detail = detail
        self.remainingPercent = remainingPercent
        self.at = at
    }

    public var id: String { "\(provider.rawValue)|\(title)" }
}

/// One provider's facts for the Priority tab.
public struct PriorityInput: Sendable {
    public let provider: UsageProvider
    public let usage: UsageSnapshot?
    public let credits: ResetCreditsSnapshot?

    public init(provider: UsageProvider, usage: UsageSnapshot?, credits: ResetCreditsSnapshot? = nil) {
        self.provider = provider
        self.usage = usage
        self.credits = credits
    }
}

/// The Priority tab, ported from the Android tab and the desktop `PriorityReport`: only what
/// needs you, across every signed-in service, each list shown only when it has something.
/// **Limits to watch** (running out before they reset, or nearly gone, with some left),
/// **Slowdowns** (a followed model much slower than it usually is), **Used up** (soonest reset
/// first), **Coming up** (a monthly limit renewing or a reset credit expiring within three
/// days) and **New models** from the makers the user is signed in to.
public struct PriorityReport: Sendable, Equatable {
    public static let lowPercent = 15
    public static let comingUpWindow: TimeInterval = 3 * 86_400

    public let watch: [PriorityItem]
    public let slowdowns: [PriorityItem]
    public let usedUp: [PriorityItem]
    public let comingUp: [PriorityItem]
    public let newModels: [PriorityItem]

    public var isEmpty: Bool {
        watch.isEmpty && slowdowns.isEmpty && usedUp.isEmpty && comingUp.isEmpty && newModels.isEmpty
    }

    /// Rows that need attention now: limits to watch, slowdowns and used-up limits.
    public var attentionCount: Int {
        watch.count + slowdowns.count + usedUp.count
    }

    /// One line for the top of the tab.
    public var headline: String {
        if isEmpty { return "Nothing needs you right now. Every connected limit is on track." }
        if !watch.isEmpty { return "\(watch.count) \(watch.count == 1 ? "limit needs" : "limits need") watching." }
        if !usedUp.isEmpty { return "\(usedUp.count) \(usedUp.count == 1 ? "limit is" : "limits are") used up." }
        if !slowdowns.isEmpty {
            return "\(slowdowns.count) \(slowdowns.count == 1 ? "model is" : "models are") running slower than usual."
        }
        return "Nothing urgent. Here's what's coming up."
    }

    public static func build(
        _ providers: [PriorityInput],
        slowdowns: [PriorityItem] = [],
        newModels: [PriorityItem] = [],
        now: Date = Date(),
        sensitivity: UsagePaceSensitivity = .balanced
    ) -> PriorityReport {
        var watch: [PriorityItem] = []
        var usedUp: [PriorityItem] = []
        var coming: [PriorityItem] = []
        for input in providers {
            let provider = input.provider
            if let usage = input.usage {
                for (label, window) in windows(usage) {
                    let resetAt = window.effectiveResetDate(relativeTo: usage.fetchedAt)
                    if let resetAt, resetAt <= now { continue } // stale: it has reset since
                    let title = "\(provider.displayName) \(label)"
                    if window.remainingPercent <= 0 {
                        usedUp.append(PriorityItem(
                            provider: provider, title: title,
                            detail: resetAt.map { "Resets " + UsageFormat.relative(until: $0, from: now) } ?? "Used up",
                            remainingPercent: 0, at: resetAt
                        ))
                        continue
                    }
                    let pace = UsagePace.assess(window: window, observedAt: usage.fetchedAt, now: now, sensitivity: sensitivity)
                    let accelerated = pace.isAvailable && pace.isAccelerated
                    if accelerated || window.remainingPercent <= lowPercent {
                        let detail = accelerated
                            ? "\(window.remainingPercent)% left · at this pace, runs out in \(duration(pace.estimatedRemaining))"
                            : "\(window.remainingPercent)% left" + (resetAt.map { " · resets " + UsageFormat.relative(until: $0, from: now) } ?? "")
                        watch.append(PriorityItem(provider: provider, title: title, detail: detail,
                                                  remainingPercent: window.remainingPercent, at: pace.estimatedExhaustionAt))
                    } else if window == usage.monthly, let renews = resetAt, renews.timeIntervalSince(now) <= comingUpWindow {
                        coming.append(PriorityItem(
                            provider: provider, title: "\(provider.displayName) monthly renews",
                            detail: "\(window.remainingPercent)% left to use · renews " + UsageFormat.relative(until: renews, from: now),
                            remainingPercent: window.remainingPercent, at: renews
                        ))
                    }
                }
            }
            // One row per provider: how many credits expire soon, and when the first one goes.
            let expiring = (input.credits?.credits ?? []).filter(\.isAvailable).compactMap(\.expiresAt)
                .filter { $0 > now && $0.timeIntervalSince(now) <= comingUpWindow }
            if let first = expiring.min() {
                let count = expiring.count
                coming.append(PriorityItem(
                    provider: provider,
                    title: count == 1 ? "\(provider.displayName) reset credit expires"
                        : "\(count) \(provider.displayName) reset credits expire",
                    detail: (count == 1 ? "Expires " : "The first expires ") + UsageFormat.relative(until: first, from: now)
                        + ". Use \(count == 1 ? "it" : "them") before then or \(count == 1 ? "it's" : "they're") lost.",
                    at: first
                ))
            }
        }
        let distant = Date.distantFuture
        return PriorityReport(
            watch: watch.sorted { ($0.remainingPercent ?? 100) < ($1.remainingPercent ?? 100) },
            slowdowns: slowdowns,
            usedUp: usedUp.sorted { ($0.at ?? distant) < ($1.at ?? distant) },
            comingUp: coming.sorted { ($0.at ?? distant) < ($1.at ?? distant) },
            newModels: newModels
        )
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
