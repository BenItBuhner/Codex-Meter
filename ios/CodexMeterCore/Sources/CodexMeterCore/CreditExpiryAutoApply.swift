import Foundation

/// Pure planner: an unused reset credit is used automatically a chosen lead time before it
/// expires, while the API still accepts it, instead of being lost.
public struct CreditExpiryAutoApply: Sendable, Equatable, Identifiable {
    /// Lead-time choices offered in Settings, in minutes, shortest first.
    public static let leadMinuteOptions: [Int] = [1, 15, 30, 60, 120, 360, 720, 1440]
    /// 30 minutes leaves time for a retry, and for iOS to get a refresh in.
    public static let defaultLeadMinutes = 30

    public let creditId: String
    public let expiresAt: Date
    public let triggerAt: Date

    public var id: String { token }

    public var token: String {
        Self.token(creditId: creditId, expiresAt: expiresAt)
    }

    public init(creditId: String, expiresAt: Date, leadMinutes: Int = defaultLeadMinutes) {
        self.creditId = creditId
        self.expiresAt = expiresAt
        self.triggerAt = expiresAt.addingTimeInterval(-Self.leadInterval(leadMinutes))
    }

    public static func token(creditId: String, expiresAt: Date) -> String {
        "\(creditId):\(Int(expiresAt.timeIntervalSince1970))"
    }

    /// Snaps any stored value to the nearest offered option so old or imported values stay valid.
    public static func normalizedLeadMinutes(_ minutes: Int) -> Int {
        guard minutes > 0 else { return defaultLeadMinutes }
        return leadMinuteOptions.min { abs($0 - minutes) < abs($1 - minutes) } ?? defaultLeadMinutes
    }

    public static func leadInterval(_ minutes: Int) -> TimeInterval {
        TimeInterval(normalizedLeadMinutes(minutes) * 60)
    }

    /// "1 minute", "30 minutes", "1 hour", "24 hours".
    public static func leadLabel(_ minutes: Int) -> String {
        let value = normalizedLeadMinutes(minutes)
        if value < 60 { return value == 1 ? "1 minute" : "\(value) minutes" }
        let hours = value / 60
        return hours == 1 ? "1 hour" : "\(hours) hours"
    }

    public static func plan(
        credits: [RateLimitResetCredit],
        now: Date,
        leadMinutes: Int = defaultLeadMinutes
    ) -> [CreditExpiryAutoApply] {
        credits.compactMap { credit -> CreditExpiryAutoApply? in
            guard credit.isAvailable, let expiresAt = credit.expiresAt, expiresAt > now else {
                return nil
            }
            return CreditExpiryAutoApply(
                creditId: credit.id,
                expiresAt: expiresAt,
                leadMinutes: leadMinutes
            )
        }
        .sorted {
            if $0.triggerAt != $1.triggerAt { return $0.triggerAt < $1.triggerAt }
            if $0.expiresAt != $1.expiresAt { return $0.expiresAt < $1.expiresAt }
            return $0.creditId < $1.creditId
        }
    }

    public static func due(
        credits: [RateLimitResetCredit],
        now: Date,
        leadMinutes: Int = defaultLeadMinutes
    ) -> [CreditExpiryAutoApply] {
        plan(credits: credits, now: now, leadMinutes: leadMinutes).filter {
            isDue(expiresAt: $0.expiresAt, now: now, leadMinutes: leadMinutes)
        }
    }

    public static func isDue(
        expiresAt: Date,
        now: Date,
        leadMinutes: Int = defaultLeadMinutes
    ) -> Bool {
        expiresAt > now && expiresAt.timeIntervalSince(now) <= leadInterval(leadMinutes)
    }
}

/// Which usage windows the low-usage rule watches.
public enum LowUsageAutoUseMetric: String, Codable, Sendable, CaseIterable, Identifiable {
    case both
    case weekly
    case fiveHour = "five_hour"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .both: "5-hour or weekly limit"
        case .weekly: "Weekly limit only"
        case .fiveHour: "5-hour limit only"
        }
    }

    public var shortTitle: String {
        switch self {
        case .both: "5-hour or weekly"
        case .weekly: "weekly"
        case .fiveHour: "5-hour"
        }
    }
}

/// Pure decision: use one banked reset when a window's remaining allowance drops to the chosen
/// threshold. Fires at most once per usage window, never from a stale snapshot, and never when
/// that window refills on its own within 15 minutes. Mirrors Android's ResetCreditLowUsageAutoUse.
public struct LowUsageAutoUse: Sendable, Equatable {
    public enum Outcome: String, Sendable, Equatable {
        case fire
        case wait
        case skipNoCredit
        case skipResetImminent
        case skipAlreadyUsed
    }

    public static let thresholdOptions: [Int] = [0, 1, 2, 5, 10]
    public static let defaultThreshold = 1
    public static let imminentReset: TimeInterval = 15 * 60
    public static let maxSnapshotAge: TimeInterval = 2 * 60

    public let outcome: Outcome
    /// "5-hour", "weekly", or "monthly"; empty while waiting.
    public let windowLabel: String
    public let remainingPercent: Int
    /// Identifies the usage window, so the rule fires only once per window.
    public let windowToken: String

    public var fires: Bool { outcome == .fire }

    public static func normalizedThreshold(_ value: Int) -> Int {
        thresholdOptions.contains(value) ? value : defaultThreshold
    }

    /// - Parameter availableCredits: banked credits available now; nil means unknown and the
    ///   consume call reports any shortage itself.
    public static func evaluate(
        snapshot: UsageSnapshot?,
        metric: LowUsageAutoUseMetric,
        threshold: Int,
        availableCredits: Int?,
        lastFiredToken: String?,
        now: Date
    ) -> LowUsageAutoUse {
        guard let snapshot,
              now.timeIntervalSince(snapshot.fetchedAt) <= maxSnapshotAge else {
            return waiting
        }
        let limit = normalizedThreshold(threshold)
        var candidates: [(UsageWindow, String)] = []
        if metric != .weekly, let window = current(snapshot.fiveHour, snapshot, now) {
            candidates.append((window, "5-hour"))
        }
        if metric != .fiveHour, let window = current(snapshot.longWindow, snapshot, now) {
            candidates.append((window, snapshot.longWindowIsMonthly ? "monthly" : "weekly"))
        }
        guard let (window, label) = candidates
            .filter({ $0.0.remainingPercent <= limit })
            .min(by: { $0.0.remainingPercent < $1.0.remainingPercent }) else {
            return waiting
        }
        let resetAt = window.effectiveResetDate(relativeTo: snapshot.fetchedAt)
        let token = "\(label):\(Int((resetAt?.timeIntervalSince1970 ?? 0) * 1000))"
        if sameWindow(lastFiredToken, label: label, resetAt: resetAt, window: window) {
            return LowUsageAutoUse(outcome: .skipAlreadyUsed, windowLabel: label,
                                   remainingPercent: window.remainingPercent, windowToken: token)
        }
        if availableCredits == 0 {
            return LowUsageAutoUse(outcome: .skipNoCredit, windowLabel: label,
                                   remainingPercent: window.remainingPercent, windowToken: token)
        }
        if let resetAt, resetAt > now, resetAt.timeIntervalSince(now) <= imminentReset {
            return LowUsageAutoUse(outcome: .skipResetImminent, windowLabel: label,
                                   remainingPercent: window.remainingPercent, windowToken: token)
        }
        return LowUsageAutoUse(outcome: .fire, windowLabel: label,
                               remainingPercent: window.remainingPercent, windowToken: token)
    }

    private static let waiting = LowUsageAutoUse(
        outcome: .wait, windowLabel: "", remainingPercent: -1, windowToken: ""
    )

    private static func current(
        _ window: UsageWindow?,
        _ snapshot: UsageSnapshot,
        _ now: Date
    ) -> UsageWindow? {
        guard let window else { return nil }
        if let reset = window.effectiveResetDate(relativeTo: snapshot.fetchedAt), reset <= now {
            return nil
        }
        return window
    }

    private static func sameWindow(
        _ lastToken: String?,
        label: String,
        resetAt: Date?,
        window: UsageWindow
    ) -> Bool {
        guard let lastToken, let split = lastToken.lastIndex(of: ":") else { return false }
        guard String(lastToken[..<split]) == label,
              let millis = Int64(lastToken[lastToken.index(after: split)...]) else {
            return false
        }
        guard let resetAt, millis > 0 else { return resetAt == nil && millis == 0 }
        return UsageWindow.sameResetWindow(
            leftReset: Date(timeIntervalSince1970: TimeInterval(millis) / 1000),
            leftWindowSeconds: window.windowSeconds,
            rightReset: resetAt,
            rightWindowSeconds: window.windowSeconds
        )
    }
}
