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
/// that window refills on its own within the wait the user chose for it (15 minutes unless
/// changed; the 5-hour and weekly limits each have their own). Mirrors Android's
/// ResetCreditLowUsageAutoUse.
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
    /// The default wait: a window that refills on its own this soon is not worth a reset.
    public static let defaultWaitMinutes = 15
    /// Waits offered for the 5-hour limit, in minutes. 0 never waits.
    public static let fiveHourWaitOptions: [Int] = [0, 5, 15, 30, 60, 120, 180, 240]
    /// Waits offered for the weekly (or monthly) limit, in minutes, up to 3 days.
    public static let weeklyWaitOptions: [Int] = [0, 15, 30, 60, 180, 360, 720, 1440, 2880, 4320]
    public static let maxSnapshotAge: TimeInterval = 2 * 60

    public let outcome: Outcome
    /// "5-hour", "weekly", or "monthly"; empty while waiting.
    public let windowLabel: String
    public let remainingPercent: Int
    /// Identifies the usage window, so the rule fires only once per window.
    public let windowToken: String
    /// When the picked window refills on its own; nil while waiting or when unknown.
    public let resetAt: Date?

    public var fires: Bool { outcome == .fire }

    public init(outcome: Outcome, windowLabel: String, remainingPercent: Int, windowToken: String,
                resetAt: Date? = nil) {
        self.outcome = outcome
        self.windowLabel = windowLabel
        self.remainingPercent = remainingPercent
        self.windowToken = windowToken
        self.resetAt = resetAt
    }

    public static func normalizedThreshold(_ value: Int) -> Int {
        thresholdOptions.contains(value) ? value : defaultThreshold
    }

    /// A stored 5-hour wait, or the default when it isn't one of the offered values.
    public static func normalizedFiveHourWait(_ minutes: Int) -> Int {
        fiveHourWaitOptions.contains(minutes) ? minutes : defaultWaitMinutes
    }

    /// A stored weekly wait, or the default when it isn't one of the offered values.
    public static func normalizedWeeklyWait(_ minutes: Int) -> Int {
        weeklyWaitOptions.contains(minutes) ? minutes : defaultWaitMinutes
    }

    /// "Don't wait", "5 minutes", "1 hour", "12 hours", "1 day", "3 days".
    public static func waitLabel(_ minutes: Int) -> String {
        if minutes <= 0 { return "Don't wait" }
        if minutes < 60 { return minutes == 1 ? "1 minute" : "\(minutes) minutes" }
        if minutes < 1440 || minutes % 1440 != 0 {
            let hours = minutes / 60
            return hours == 1 ? "1 hour" : "\(hours) hours"
        }
        let days = minutes / 1440
        return days == 1 ? "1 day" : "\(days) days"
    }

    /// True when `resetAt` is still ahead and no more than the wait away (inclusive).
    public static func refillsWithin(_ resetAt: Date?, waitMinutes: Int, now: Date) -> Bool {
        guard waitMinutes > 0, let resetAt, resetAt > now else { return false }
        return resetAt.timeIntervalSince(now) <= TimeInterval(waitMinutes * 60)
    }

    /// - Parameter availableCredits: banked credits available now; nil means unknown and the
    ///   consume call reports any shortage itself.
    public static func evaluate(
        snapshot: UsageSnapshot?,
        metric: LowUsageAutoUseMetric,
        threshold: Int,
        availableCredits: Int?,
        lastFiredToken: String?,
        fiveHourWaitMinutes: Int = defaultWaitMinutes,
        weeklyWaitMinutes: Int = defaultWaitMinutes,
        now: Date
    ) -> LowUsageAutoUse {
        guard let snapshot,
              now.timeIntervalSince(snapshot.fetchedAt) <= maxSnapshotAge else {
            return waiting
        }
        let limit = normalizedThreshold(threshold)
        var candidates: [(window: UsageWindow, label: String, fiveHour: Bool)] = []
        if metric != .weekly, let window = current(snapshot.fiveHour, snapshot, now) {
            candidates.append((window, "5-hour", true))
        }
        if metric != .fiveHour, let window = current(snapshot.longWindow, snapshot, now) {
            candidates.append((window, snapshot.longWindowIsMonthly ? "monthly" : "weekly", false))
        }
        guard let pick = candidates
            .filter({ $0.window.remainingPercent <= limit })
            .min(by: { $0.window.remainingPercent < $1.window.remainingPercent }) else {
            return waiting
        }
        let window = pick.window
        let label = pick.label
        let resetAt = window.effectiveResetDate(relativeTo: snapshot.fetchedAt)
        let token = "\(label):\(Int((resetAt?.timeIntervalSince1970 ?? 0) * 1000))"
        func decision(_ outcome: Outcome) -> LowUsageAutoUse {
            LowUsageAutoUse(outcome: outcome, windowLabel: label, remainingPercent: window.remainingPercent,
                            windowToken: token, resetAt: resetAt)
        }
        if sameWindow(lastFiredToken, label: label, resetAt: resetAt, window: window) {
            return decision(.skipAlreadyUsed)
        }
        if availableCredits == 0 {
            return decision(.skipNoCredit)
        }
        let wait = pick.fiveHour ? normalizedFiveHourWait(fiveHourWaitMinutes)
            : normalizedWeeklyWait(weeklyWaitMinutes)
        if refillsWithin(resetAt, waitMinutes: wait, now: now) {
            return decision(.skipResetImminent)
        }
        return decision(.fire)
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

/// A running-low use held back because the limit refills on its own within the chosen wait,
/// kept so Settings can say why no reset was spent. Shown until that limit refills.
public struct RefillWait: Codable, Sendable, Equatable {
    /// "5-hour", "weekly" or "monthly".
    public let windowLabel: String
    public let resetAt: Date
    public let skippedAt: Date

    public init(windowLabel: String, resetAt: Date, skippedAt: Date) {
        self.windowLabel = windowLabel
        self.resetAt = resetAt
        self.skippedAt = skippedAt
    }

    public func isActive(at now: Date) -> Bool { resetAt > now && !windowLabel.isEmpty }

    /// "Your weekly limit refills on its own tomorrow at 9:00 AM, so no reset was used."
    public func summary(formatWhen: (Date) -> String) -> String {
        "Your \(windowLabel) limit refills on its own \(formatWhen(resetAt)), so no reset was used."
    }

    /// The wait to remember after a decision: a new hold, cleared after a use, else unchanged
    /// while its limit hasn't refilled.
    public static func after(_ current: RefillWait?, decision: LowUsageAutoUse, now: Date) -> RefillWait? {
        switch decision.outcome {
        case .skipResetImminent:
            if let resetAt = decision.resetAt {
                return RefillWait(windowLabel: decision.windowLabel, resetAt: resetAt, skippedAt: now)
            }
        case .fire:
            return nil
        default:
            break
        }
        return current?.isActive(at: now) == true ? current : nil
    }

    /// The same for a Claude Code saved-reset decision.
    public static func after(_ current: RefillWait?, claude decision: ClaudeResetAutoUse, now: Date) -> RefillWait? {
        switch decision.outcome {
        case .skipRefillsSoon:
            if let resetAt = decision.resetAt {
                return RefillWait(windowLabel: decision.windowLabel, resetAt: resetAt, skippedAt: now)
            }
        case .fireLowUsage:
            return nil
        default:
            break
        }
        return current?.isActive(at: now) == true ? current : nil
    }
}

/// Where each provider's held-back running-low use is kept on this device (never exported).
public enum RefillWaitStore {
    public static let codexKey = "codexMeter.autoUse.refillWait"
    public static let claudeKey = "codex-meter.claude-reset-refill-wait-v1"

    public static func load(_ key: String, defaults: UserDefaults = .standard) -> RefillWait? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(RefillWait.self, from: data)
    }

    /// Saves the wait after a decision (nil removes it); writes only when it changed.
    public static func save(_ wait: RefillWait?, _ key: String, defaults: UserDefaults = .standard) {
        guard wait != load(key, defaults: defaults) else { return }
        if let wait, let data = try? JSONEncoder().encode(wait) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    /// The wait to show now: one whose limit hasn't refilled yet.
    public static func active(_ key: String, now: Date = Date(), defaults: UserDefaults = .standard) -> RefillWait? {
        guard let wait = load(key, defaults: defaults), wait.isActive(at: now) else { return nil }
        return wait
    }
}
