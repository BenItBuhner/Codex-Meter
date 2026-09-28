import Foundation

/// The Codex window a threshold trigger watches.
public enum ScheduledResetWindow: String, Codable, Sendable, CaseIterable, Equatable {
    case fiveHour
    case weekly

    /// Shared with Android: "5-hour" / "weekly" inside the schedule copy.
    public var label: String {
        switch self {
        case .fiveHour: "5-hour"
        case .weekly: "weekly"
        }
    }

    public func window(in usage: UsageSnapshot) -> UsageWindow? {
        switch self {
        case .fiveHour: usage.fiveHour
        case .weekly: usage.weekly
        }
    }
}

/// What arms a scheduled reset. One schedule exists at a time and fires at most once.
public enum ScheduledResetTrigger: Codable, Sendable, Equatable {
    /// Fires once the window's remaining allowance is at or below `remainingPercent`.
    case threshold(window: ScheduledResetWindow, remainingPercent: Int)
    /// Fires at `fireAt`; with `onlyIfRemainingAtMost` set, only when the lowest remaining
    /// allowance across the 5-hour and weekly windows is at or below that percentage.
    case dateTime(fireAt: Date, onlyIfRemainingAtMost: Int?)

    public var isDateTime: Bool {
        if case .dateTime = self { return true }
        return false
    }

    public var fireAt: Date? {
        if case let .dateTime(fireAt, _) = self { return fireAt }
        return nil
    }
}

/// Why a due schedule kept its credit instead of spending it.
public enum ScheduledResetSkipReason: Codable, Sendable, Equatable {
    case noCreditAvailable
    case naturalResetImminent(window: ScheduledResetWindow, resetAt: Date)
    case conditionNotMet(remainingPercent: Int?, requiredAtMost: Int)
    /// OpenAI answered the consume request without applying a reset (nothing to reset,
    /// already redeemed, ...). The message is the user-facing consume result.
    case declined(message: String)
}

public enum ScheduledResetDecision: Sendable, Equatable {
    case wait
    case fire
    case skip(ScheduledResetSkipReason)
}

public struct ScheduledReset: Codable, Sendable, Equatable, Identifiable {
    public let id: UUID
    public let trigger: ScheduledResetTrigger
    public let createdAt: Date
    public var lastCheckedAt: Date?
    public var failureCount: Int

    public init(
        id: UUID = UUID(),
        trigger: ScheduledResetTrigger,
        createdAt: Date,
        lastCheckedAt: Date? = nil,
        failureCount: Int = 0
    ) {
        self.id = id
        self.trigger = trigger
        self.createdAt = createdAt
        self.lastCheckedAt = lastCheckedAt
        self.failureCount = max(0, failureCount)
    }
}

/// The terminal (or, for failures, provisional) result of a schedule.
public struct ScheduledResetOutcome: Codable, Sendable, Equatable {
    public enum Kind: Codable, Sendable, Equatable {
        case fired(creditsRemaining: Int, windowsReset: Int)
        case skipped(ScheduledResetSkipReason)
        /// The reset request itself failed. The schedule stays armed and retries.
        case failed(message: String)
    }

    public let trigger: ScheduledResetTrigger
    public let kind: Kind
    /// Remaining allowance of the watched window when a threshold trigger fired.
    public let observedRemainingPercent: Int?
    public let at: Date

    public init(
        trigger: ScheduledResetTrigger,
        kind: Kind,
        observedRemainingPercent: Int? = nil,
        at: Date
    ) {
        self.trigger = trigger
        self.kind = kind
        self.observedRemainingPercent = observedRemainingPercent
        self.at = at
    }

    public var isFailure: Bool {
        if case .failed = kind { return true }
        return false
    }
}

/// Pure trigger evaluation shared (as fixture cases) with the Android build.
public enum ScheduledResetPolicy {
    /// A watched window that resets on its own this soon makes a credit pointless.
    public static let imminentResetLeeway: TimeInterval = 15 * 60
    public static let thresholdPresets = [0, 5, 10]
    public static let thresholdRange = 0 ... 99
    /// A snapshot older than this is re-fetched before a credit is spent on it.
    public static let maximumSnapshotAge: TimeInterval = 60

    public static func evaluate(
        _ trigger: ScheduledResetTrigger,
        usage: UsageSnapshot,
        availableCredits: Int,
        now: Date,
        imminentResetLeeway: TimeInterval = imminentResetLeeway
    ) -> ScheduledResetDecision {
        switch trigger {
        case let .threshold(windowKind, remainingPercent):
            guard let window = windowKind.window(in: usage) else {
                return .wait
            }
            guard window.remainingPercent <= clamp(remainingPercent) else {
                return .wait
            }
            return guards(
                targets: [(windowKind, window)],
                usage: usage,
                availableCredits: availableCredits,
                now: now,
                leeway: imminentResetLeeway
            )
        case let .dateTime(fireAt, onlyIfRemainingAtMost):
            guard now >= fireAt else {
                return .wait
            }
            let present: [(ScheduledResetWindow, UsageWindow)] = ScheduledResetWindow.allCases
                .compactMap { kind in kind.window(in: usage).map { (kind, $0) } }
            if let required = onlyIfRemainingAtMost {
                let lowest = present.map(\.1.remainingPercent).min()
                guard let lowest, lowest <= clamp(required) else {
                    return .skip(.conditionNotMet(remainingPercent: lowest, requiredAtMost: clamp(required)))
                }
            }
            let used = present.filter { $0.1.usedPercent > 0 }
            return guards(
                targets: used.isEmpty ? present : used,
                usage: usage,
                availableCredits: availableCredits,
                now: now,
                leeway: imminentResetLeeway
            )
        }
    }

    /// Whether every available credit expires before a date/time trigger could fire.
    /// Credits without an expiry are assumed to outlive the schedule.
    public static func creditsExpireBefore(fireAt: Date, credits: ResetCreditsSnapshot) -> Bool {
        let available = credits.credits.filter(\.isAvailable)
        guard !available.isEmpty else { return false }
        return available.allSatisfy { credit in
            guard let expiresAt = credit.expiresAt else { return false }
            return expiresAt <= fireAt
        }
    }

    /// The earliest moment a date/time schedule is worth waking the app for.
    public static func earliestCheck(for trigger: ScheduledResetTrigger, now: Date) -> Date? {
        guard case let .dateTime(fireAt, _) = trigger else { return nil }
        return max(fireAt, now)
    }

    /// Tightens the automatic refresh cadence while an armed schedule is close to firing.
    public static func refreshMinutesCap(
        for trigger: ScheduledResetTrigger,
        usage: UsageSnapshot?,
        now: Date
    ) -> Int? {
        switch trigger {
        case let .threshold(windowKind, remainingPercent):
            guard let usage, let window = windowKind.window(in: usage) else { return nil }
            let gap = window.remainingPercent - clamp(remainingPercent)
            if gap <= 10 { return 5 }
            if gap <= 25 { return 10 }
            return nil
        case let .dateTime(fireAt, _):
            let until = fireAt.timeIntervalSince(now)
            if until <= 15 * 60 { return 5 }
            if until <= 60 * 60 { return 10 }
            return nil
        }
    }

    public static func requiresFreshSnapshot(
        fetchedAt: Date,
        now: Date,
        maximumAge: TimeInterval = maximumSnapshotAge
    ) -> Bool {
        now.timeIntervalSince(fetchedAt) > maximumAge
    }

    /// Default pick for the date/time trigger: one hour out, on a whole minute.
    public static func defaultFireDate(now: Date) -> Date {
        let seconds = (now.timeIntervalSince1970 + 60 * 60).rounded(.down)
        return Date(timeIntervalSince1970: seconds - seconds.truncatingRemainder(dividingBy: 60))
    }

    public static func clamp(_ percent: Int) -> Int {
        min(thresholdRange.upperBound, max(thresholdRange.lowerBound, percent))
    }

    private static func guards(
        targets: [(ScheduledResetWindow, UsageWindow)],
        usage: UsageSnapshot,
        availableCredits: Int,
        now: Date,
        leeway: TimeInterval
    ) -> ScheduledResetDecision {
        guard availableCredits > 0 else {
            return .skip(.noCreditAvailable)
        }
        let resets = targets.compactMap { kind, window -> (ScheduledResetWindow, Date)? in
            window.effectiveResetDate(relativeTo: usage.fetchedAt).map { (kind, $0) }
        }
        // Every target window must be about to reset on its own for the credit to be
        // wasted; one target with a long way to go still makes the reset worthwhile.
        if !targets.isEmpty,
           resets.count == targets.count,
           let latest = resets.max(by: { $0.1 < $1.1 }),
           latest.1.timeIntervalSince(now) <= leeway {
            return .skip(.naturalResetImminent(window: latest.0, resetAt: latest.1))
        }
        return .fire
    }
}

/// Copy shared with the Android build. Keep wording identical when changing either side.
public enum ScheduledResetCopy {
    public static let featureName = "Scheduled reset"

    /// "when 5-hour reaches 5%" or "today at 6:30 PM".
    public static func triggerSummary(
        _ trigger: ScheduledResetTrigger,
        now: Date,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        switch trigger {
        case let .threshold(window, remainingPercent):
            return "when \(window.label) reaches \(ScheduledResetPolicy.clamp(remainingPercent))%"
        case let .dateTime(fireAt, _):
            return UsageFormat.absolute(fireAt, relativeTo: now, calendar: calendar, locale: locale)
        }
    }

    /// "Scheduled · when 5-hour reaches 5%"
    public static func armedLine(
        _ trigger: ScheduledResetTrigger,
        now: Date,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        "Scheduled · \(triggerSummary(trigger, now: now, calendar: calendar, locale: locale))"
    }

    /// "Codex Meter will use 1 reset credit when your 5-hour limit reaches 5% remaining."
    public static func confirmation(
        _ trigger: ScheduledResetTrigger,
        now: Date,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        switch trigger {
        case let .threshold(window, remainingPercent):
            return "Codex Meter will use 1 reset credit when your \(window.label) limit reaches \(ScheduledResetPolicy.clamp(remainingPercent))% remaining."
        case let .dateTime(fireAt, onlyIfRemainingAtMost):
            let when = UsageFormat.absolute(fireAt, relativeTo: now, calendar: calendar, locale: locale)
            if let onlyIfRemainingAtMost {
                return "Codex Meter will use 1 reset credit \(when) if remaining is at or below \(ScheduledResetPolicy.clamp(onlyIfRemainingAtMost))%."
            }
            return "Codex Meter will use 1 reset credit \(when)."
        }
    }

    public static func outcomeTitle(_ outcome: ScheduledResetOutcome) -> String {
        switch outcome.kind {
        case .fired: "Scheduled reset used 1 credit"
        case .skipped: "Scheduled reset skipped"
        case .failed: "Scheduled reset failed"
        }
    }

    /// One sentence for banners and notification bodies.
    public static func outcomeDetail(
        _ outcome: ScheduledResetOutcome,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        switch outcome.kind {
        case let .fired(creditsRemaining, _):
            let cause: String
            switch outcome.trigger {
            case let .threshold(window, _):
                if let observed = outcome.observedRemainingPercent {
                    cause = "Your \(window.label) limit reached \(observed)% remaining."
                } else {
                    cause = "Your \(window.label) limit reached the threshold."
                }
            case let .dateTime(fireAt, _):
                cause = "It ran as scheduled (\(UsageFormat.absolute(fireAt, relativeTo: outcome.at, calendar: calendar, locale: locale)))."
            }
            return "\(cause) \(creditsRemaining) reset \(creditsRemaining == 1 ? "credit" : "credits") left."
        case let .skipped(reason):
            switch reason {
            case .noCreditAvailable:
                return "No reset credit was available, so nothing was spent."
            case let .naturalResetImminent(window, resetAt):
                return "Your \(window.label) limit resets on its own \(UsageFormat.relative(until: resetAt, from: outcome.at)), so the credit was kept."
            case let .conditionNotMet(remainingPercent, requiredAtMost):
                if let remainingPercent {
                    return "Remaining usage was \(remainingPercent)%, above the \(requiredAtMost)% condition, so the credit was kept."
                }
                return "Remaining usage could not be checked against the \(requiredAtMost)% condition, so the credit was kept."
            case let .declined(message):
                return message
            }
        case let .failed(message):
            return "\(message) The schedule stays armed and retries on the next refresh."
        }
    }

    /// Banner text: title and detail in one line.
    public static func outcomeMessage(
        _ outcome: ScheduledResetOutcome,
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        "\(outcomeTitle(outcome)) — \(outcomeDetail(outcome, calendar: calendar, locale: locale))"
    }
}
