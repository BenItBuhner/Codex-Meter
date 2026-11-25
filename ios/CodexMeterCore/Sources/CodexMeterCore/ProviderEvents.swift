import Foundation

/// Recent activity on a provider's limits, worked out by comparing one refresh with the one before:
/// a limit that refilled before its scheduled reset, newly saved resets or reset credits, and a
/// saved reset that was used (in this app, in Claude Code or on claude.ai). A refill that a used
/// reset explains is put down to that reset, not called a surprise. The same rules as Android's
/// `ProviderEvents`; the app keeps the recent events for the Priority tab's Recent activity and
/// turns each one into a notification.
public enum ProviderEvents {
    public static let surpriseRefillKind = "surprise_refill"
    public static let newResetsKind = "new_resets"
    public static let resetUsedKind = "reset_used"
    /// How long after a saved reset is used a refill is still put down to it.
    public static let useExplains: TimeInterval = 6 * 3_600
    /// How long an event stays on the Priority tab.
    public static let keep: TimeInterval = 24 * 3_600
    public static let maxKept = 12
    /// A monthly limit this close to its reset is "renewing soon" on the Priority tab.
    public static let renewalSoon: TimeInterval = 3 * 86_400
    /// Saved resets this close to expiring are listed on the Priority tab.
    public static let expirySoon: TimeInterval = 3 * 86_400

    public struct Event: Codable, Sendable, Equatable, Identifiable {
        public let kind: String
        /// The provider's raw value.
        public let provider: String
        /// The short form for a list row: "Weekly refilled early", "3 Fable resets added".
        public let line: String
        public let title: String
        public let detail: String
        public let at: Date
        /// The limits it is about ("Weekly", "Fable only"), for a surprise refill; else empty.
        public let labels: [String]

        public init(kind: String, provider: String, line: String, title: String, detail: String, at: Date,
                    labels: [String] = []) {
            self.kind = kind
            self.provider = provider
            self.line = line
            self.title = title
            self.detail = detail
            self.at = at
            self.labels = labels
        }

        /// Names this event among the kept ones, so the person can dismiss it.
        public var key: String {
            "\(kind)|\(provider)|\(Int64((at.timeIntervalSince1970 * 1_000).rounded()))|\(line)"
        }

        public var id: String { key }
    }

    /// One saved reset that was used: what it was called ("Fable reset"), the one model it covers
    /// ("Fable", or empty for every model), and which of the all-models limits it refills.
    public struct Use: Codable, Sendable, Equatable {
        public let title: String
        public let scope: String
        public let fiveHour: Bool
        public let weekly: Bool
        public let clearsLabel: String
        public let at: Date

        public init(title: String, scope: String, fiveHour: Bool, weekly: Bool, clearsLabel: String, at: Date) {
            self.title = title
            self.scope = scope.trimmingCharacters(in: .whitespacesAndNewlines)
            self.fiveHour = fiveHour
            self.weekly = weekly
            self.clearsLabel = clearsLabel
            self.at = at
        }

        public static func of(_ grant: ClaudeSavedResets.Grant, at: Date) -> Use {
            Use(title: grant.title, scope: grant.scope, fiveHour: grant.clearsFiveHour, weekly: grant.clearsWeekly,
                clearsLabel: grant.clearsLabel, at: at)
        }

        /// Whether this reset refills the limit a refill row names: "5-hour" (or the provider's
        /// own short cadence), "Weekly", "Monthly", or a model limit such as "Fable only". A reset
        /// for one model covers only the limit named for it; an all-models weekly or full reset
        /// covers the weekly limits, the model ones included.
        public func covers(_ label: String, fiveHourLabel: Bool) -> Bool {
            guard !label.isEmpty else { return false }
            if !scope.isEmpty { return label.lowercased().contains(scope.lowercased()) }
            return fiveHourLabel ? fiveHour : weekly
        }

        func at(_ date: Date) -> Use {
            Use(title: title, scope: scope, fiveHour: fiveHour, weekly: weekly, clearsLabel: clearsLabel, at: date)
        }
    }

    /// A window's cadence name, the same as Android's: "Weekly", "Monthly", "5-hour", "Daily",
    /// "3-day", or "Usage" when the length says nothing.
    public static func cadenceLabel(_ window: UsageWindow) -> String {
        let seconds = window.windowSeconds
        if (432_000 ... 777_600).contains(seconds) { return "Weekly" }
        if (2_419_200 ... 2_678_400).contains(seconds) { return "Monthly" }
        if (10_800 ... 28_800).contains(seconds) {
            return "\(max(1, Int((Double(seconds) / 3_600).rounded())))-hour"
        }
        if seconds == 86_400 { return "Daily" }
        if seconds > 0, seconds.isMultiple(of: 86_400) { return "\(seconds / 86_400)-day" }
        if seconds > 0, seconds.isMultiple(of: 3_600) { return "\(seconds / 3_600)-hour" }
        return "Usage"
    }

    /// The short limit's label for a provider: Codex always says "5-hour"; the others name their
    /// own cadence ("Daily", "Session"...).
    public static func shortLabel(_ provider: UsageProvider, _ snapshot: UsageSnapshot?) -> String {
        guard provider != .codex, let five = snapshot?.fiveHour else { return "5-hour" }
        return cadenceLabel(five)
    }

    /// The limits that went back to nothing used well before the reset time the previous refresh
    /// gave: "5-hour", "Weekly", "Monthly" and any extra limit by its name ("Fable only"). A limit
    /// with no known reset time is left out, because its ordinary reset would look the same.
    public static func refilledEarly(previous: UsageSnapshot?, current: UsageSnapshot?, shortLabel: String) -> [String] {
        guard let previous, let current else { return [] }
        var out: [String] = []
        let observed = current.fetchedAt
        if refilledEarly(previous, previous.fiveHour, current.fiveHour, observed) {
            out.append(shortLabel.isEmpty ? "5-hour" : shortLabel)
        }
        if refilledEarly(previous, previous.weekly, current.weekly, observed) { out.append("Weekly") }
        if refilledEarly(previous, previous.monthly, current.monthly, observed) { out.append("Monthly") }
        for now in current.additionalLimits where !now.id.isEmpty && now.primary != nil {
            for before in previous.additionalLimits where before.id == now.id && before.primary != nil
                && refilledEarly(previous, before.primary, now.primary, observed) {
                out.append(now.displayName)
            }
        }
        return out
    }

    private static func refilledEarly(_ previous: UsageSnapshot, _ before: UsageWindow?, _ after: UsageWindow?,
                                      _ observedAt: Date) -> Bool {
        guard let before, let after, before.usedPercent > 0, after.usedPercent == 0,
              let due = before.effectiveResetDate(relativeTo: previous.fetchedAt) else { return false }
        return RefillGuard.wellBeforeReset(due, observedAt: observedAt)
    }

    static func isFiveHourLabel(_ labels: [String], _ index: Int, shortLabel: String) -> Bool {
        let five = shortLabel.isEmpty ? "5-hour" : shortLabel
        return index == 0 && labels.first == five
    }

    /// Splits `labels` (from `refilledEarly`) into the ones a saved reset used in the last
    /// `useExplains` explains (returned) and the rest, plus the first reset that explains each.
    public static func explainedByUses(
        _ labels: [String], shortLabel: String, uses: [Use], now: Date
    ) -> (explained: [String], rest: [String], by: [Use]) {
        var explained: [String] = []
        var rest: [String] = []
        var by: [Use] = []
        for (index, label) in labels.enumerated() {
            let five = isFiveHourLabel(labels, index, shortLabel: shortLabel)
            let use = uses.first { use in
                use.at <= now.addingTimeInterval(60) && now.timeIntervalSince(use.at) <= useExplains
                    && use.covers(label, fiveHourLabel: five)
            }
            if let use {
                explained.append(label)
                if !by.contains(use) { by.append(use) }
            } else {
                rest.append(label)
            }
        }
        return (explained, rest, by)
    }

    /// Saved resets used since the last list: a grant that holds fewer resets than before, or one
    /// that is gone before it expired while the person can still be offered resets. A grant that
    /// expired, or a list that came back without the program, says nothing.
    public static func usedSavedResets(previous: ClaudeSavedResets?, current: ClaudeSavedResets?, now: Date) -> [Use] {
        guard let previous, let current else { return [] }
        var out: [Use] = []
        for old in previous.grants where old.hasResets(at: now) {
            let fewer: Int
            if let still = current.grants.last(where: { $0.id == old.id }) {
                fewer = old.resetsLeft - still.resetsLeft
            } else {
                let notExpired = old.endsAt.map { $0 > now.addingTimeInterval(60) } ?? true
                fewer = current.eligible && notExpired ? old.resetsLeft : 0
            }
            if fewer > 0 {
                for _ in 0..<fewer { out.append(Use.of(old, at: now)) }
            }
        }
        return out
    }

    /// "Fable reset used" for the Priority tab. `refilled` are the limits seen refilling after it,
    /// or empty when none were seen yet. Nil when nothing was used.
    public static func resetUsed(_ provider: UsageProvider, uses: [Use], refilled: [String], at: Date) -> Event? {
        guard let first = uses.first else { return nil }
        var order: [String] = []
        var counts: [String: Int] = [:]
        for use in uses {
            if counts[use.title] == nil { order.append(use.title) }
            counts[use.title, default: 0] += 1
        }
        let what = uses.count == 1 ? first.title : countsLabel(order.map { ($0, counts[$0] ?? 0) })
        let line = what + " used" + (refilled.isEmpty ? "" : " · " + join(refilled) + " refilled")
        let detail = uses.count == 1 && !first.clearsLabel.isEmpty
            ? "It refills your " + first.clearsLabel + (first.scope.isEmpty ? "." : " only.")
            : ""
        return Event(kind: resetUsedKind, provider: provider.rawValue, line: line,
                     title: "\(provider.displayName): \(what) used", detail: detail, at: at, labels: refilled)
    }

    /// Nil when nothing refilled early.
    public static func surpriseRefill(_ provider: UsageProvider, labels: [String], at: Date) -> Event? {
        guard !labels.isEmpty else { return nil }
        let detail = labels.count == 1
            ? "Your \(limitName(labels[0])) limit jumped back to 100% before its scheduled reset."
            : "Your \(join(labels.map(limitName))) limits jumped back to 100% before their scheduled resets."
        return Event(kind: surpriseRefillKind, provider: provider.rawValue, line: join(labels) + " refilled early",
                     title: "Surprise \(provider.displayName) refill", detail: detail, at: at, labels: labels)
    }

    /// "Fable only" stays as it is; "Weekly" reads "weekly".
    static func limitName(_ label: String) -> String {
        ["Weekly", "Monthly", "Daily", "Session"].contains(label) ? label.lowercased() : label
    }

    /// Claude Code resets saved since the last refresh, by kind, in the order first seen: a grant
    /// that is new, or holds more resets than before, counts.
    public static func addedSavedResets(previous: ClaudeSavedResets?, current: ClaudeSavedResets?,
                                        now: Date) -> [(title: String, count: Int)] {
        guard let current else { return [] }
        var order: [String] = []
        var added: [String: Int] = [:]
        for grant in current.grants where grant.hasResets(at: now) {
            let before = previous?.grants.last(where: { $0.id == grant.id })?.resetsLeft ?? 0
            let more = grant.resetsLeft - before
            guard more > 0 else { continue }
            if added[grant.title] == nil { order.append(grant.title) }
            added[grant.title, default: 0] += more
        }
        return order.map { ($0, added[$0] ?? 0) }
    }

    /// "3 Fable resets added" for Claude Code. `expiryText` is when the soonest of them expires,
    /// already formatted, or empty. Nil when nothing was added.
    public static func newSavedResets(_ provider: UsageProvider, added: [(title: String, count: Int)],
                                      availableNow: Int, expiryText: String, at: Date) -> Event? {
        let count = added.reduce(0) { $0 + max(0, $1.count) }
        guard count > 0 else { return nil }
        let what = countsLabel(added)
        let detail = "You now have \(availableNow) saved reset\(availableNow == 1 ? "" : "s")."
            + (expiryText.isEmpty ? "" : (count == 1 ? " It expires " : " The soonest expires ") + expiryText + ".")
        return Event(kind: newResetsKind, provider: provider.rawValue, line: what + " added",
                     title: "\(provider.displayName): \(what) added", detail: detail, at: at)
    }

    /// A provider whose reset credits are only a count (Codex). Nil when the count did not grow.
    public static func newResetCredits(_ provider: UsageProvider, previous: Int, current: Int, at: Date) -> Event? {
        guard previous >= 0, current > previous else { return nil }
        let added = current - previous
        let what = "\(added) reset credit\(added == 1 ? "" : "s")"
        return Event(kind: newResetsKind, provider: provider.rawValue, line: what + " added",
                     title: "\(provider.displayName): \(what) added", detail: "You now have \(current).", at: at)
    }

    /// `events` plus `event`, newest first, without the ones too old to show.
    public static func remember(_ events: [Event], _ event: Event?, now: Date) -> [Event] {
        recent((event.map { [$0] } ?? []) + events, now: now)
    }

    /// `events` without the one `key` names.
    public static func without(_ events: [Event], key: String) -> [Event] {
        events.filter { $0.key != key }
    }

    /// After a saved reset turns out to have been used, the provider's recent surprise refills it
    /// explains are folded into it: explained limits leave the surprise (which goes when none are
    /// left) and are listed on the returned use event.
    public static func explainEarlierRefills(_ events: [Event], useEvent: Event?, uses: [Use], now: Date) -> [Event] {
        guard let useEvent else { return events }
        var refilled = useEvent.labels
        var out: [Event] = []
        for event in events {
            let candidate = event.kind == surpriseRefillKind && event.provider == useEvent.provider
                && !event.labels.isEmpty && now.timeIntervalSince(event.at) <= useExplains
                && event.at <= now.addingTimeInterval(60)
            guard candidate else {
                out.append(event)
                continue
            }
            // Earlier refills: the reset was used before the refresh that found them, so any time
            // within the window counts.
            let split = explainedByUses(event.labels, shortLabel: "", uses: uses.map { $0.at(event.at) }, now: event.at)
            if split.explained.isEmpty {
                out.append(event)
                continue
            }
            for label in split.explained where !refilled.contains(label) { refilled.append(label) }
            if let provider = UsageProvider(rawValue: event.provider),
               let left = surpriseRefill(provider, labels: split.rest, at: event.at) {
                out.append(left)
            }
        }
        let merged = UsageProvider(rawValue: useEvent.provider)
            .flatMap { resetUsed($0, uses: uses, refilled: refilled, at: useEvent.at) }
        return [merged ?? useEvent] + out
    }

    /// Adds refilled limits to the newest "reset used" event of the provider, or starts one.
    public static func attachRefills(_ events: [Event], provider: UsageProvider, by: [Use], refilled: [String],
                                     at: Date) -> [Event] {
        var out: [Event] = []
        var done = false
        for event in recent(events, now: at) {
            if !done, event.kind == resetUsedKind, event.provider == provider.rawValue {
                var labels = event.labels
                for label in refilled where !labels.contains(label) { labels.append(label) }
                out.append(resetUsed(provider, uses: by, refilled: labels, at: event.at) ?? event)
                done = true
            } else {
                out.append(event)
            }
        }
        if !done, let event = resetUsed(provider, uses: by, refilled: refilled, at: at) {
            out.insert(event, at: 0)
        }
        return recent(out, now: at)
    }

    /// The events from the last `keep`, newest first, at most `maxKept`.
    public static func recent(_ events: [Event], now: Date) -> [Event] {
        let kept = events.filter { $0.at <= now && now.timeIntervalSince($0.at) < keep }
            .sorted { $0.at > $1.at }
        return Array(kept.prefix(maxKept))
    }

    /// How long ago, for the Priority tab: "Just now", "12m ago", "3h ago".
    public static func ago(_ at: Date, now: Date) -> String {
        let minutes = Int(max(0, now.timeIntervalSince(at)) / 60)
        if minutes < 2 { return "Just now" }
        if minutes < 60 { return "\(minutes)m ago" }
        return "\(minutes / 60)h ago"
    }

    /// True when a monthly limit resets within `renewalSoon`.
    public static func renewsSoon(_ monthly: UsageWindow?, observedAt: Date, now: Date) -> Bool {
        guard let monthly, monthly.windowSeconds > 0,
              let resetAt = monthly.effectiveResetDate(relativeTo: observedAt) else { return false }
        return resetAt > now && resetAt.timeIntervalSince(now) <= renewalSoon
    }

    /// "2d 4h", "5h 12m", "9m".
    public static func duration(_ interval: TimeInterval) -> String {
        let minutes = max(1, Int64(interval / 60))
        let days = minutes / 1_440
        if days > 0 { return "\(days)d \((minutes / 60) % 24)h" }
        if minutes >= 60 { return "\(minutes / 60)h \(minutes % 60)m" }
        return "\(minutes)m"
    }

    /// "3 Fable resets", or "2 Fable resets and 1 Full reset".
    public static func countsLabel(_ counts: [(title: String, count: Int)]) -> String {
        join(counts.filter { $0.count > 0 }.map { "\($0.count) \($0.title)\($0.count == 1 ? "" : "s")" })
    }

    /// "A", "A and B", "A, B and C".
    static func join(_ parts: [String]) -> String {
        guard let last = parts.last else { return "" }
        return parts.count == 1 ? last : parts.dropLast().joined(separator: ", ") + " and " + last
    }
}
