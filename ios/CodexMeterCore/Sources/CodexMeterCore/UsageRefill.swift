import Foundation

/// The rules behind the always-on "usage is back" alert, the same as Android's `UsageRefill`: a
/// limit that is used up (0% left) is remembered with the time it resets, and once it resets (the
/// reset time passes, or a refresh shows some left again) it is announced once.
public enum UsageRefill {
    /// What is remembered for one limit.
    public enum Armed: Codable, Sendable, Equatable {
        /// Waiting for this reset time.
        case at(Date)
        /// Used up, and the provider did not say when it resets.
        case unknownReset
    }

    /// How far a reset time may drift from the remembered one and still be the same reset.
    public static let tolerance: TimeInterval = 60

    public enum Decision: Sendable, Equatable {
        /// Nothing changes.
        case none
        /// Remember this (a new used-up limit, or its reset time moved).
        case arm(Armed)
        /// The used-up limit has some left again: announce it (with the percent left) and forget it.
        case back(left: Int)
    }

    public static func usedUp(_ window: UsageWindow?) -> Bool {
        guard let window else { return false }
        return window.windowSeconds > 0 && window.remainingPercent <= 0
    }

    /// What a fresh reading of one limit means for the alert.
    public static func decide(armed: Armed?, window: UsageWindow?, observedAt: Date, now: Date) -> Decision {
        guard let window, window.windowSeconds > 0 else { return .none }
        if usedUp(window) {
            let target: Armed
            if let resetAt = window.effectiveResetDate(relativeTo: observedAt) {
                // A reading older than its own reset: the next refresh says what is true.
                guard resetAt > now else { return .none }
                target = .at(resetAt)
            } else {
                target = .unknownReset
            }
            if target == armed { return .none }
            if case .at(let new) = target, case .at(let old)? = armed, abs(new.timeIntervalSince(old)) < tolerance {
                return .none
            }
            return .arm(target)
        }
        return armed == nil ? .none : .back(left: window.remainingPercent)
    }

    /// "Claude Code usage is back".
    public static func title(_ provider: String) -> String {
        "\(provider) usage is back"
    }

    /// The notification text. `left` is the percent left when a refresh saw it, or nil when the
    /// reset time passed before a refresh came back.
    public static func text(_ provider: String, label: String, left: Int?) -> String {
        let limit = "Your " + (label.isEmpty ? "usage" : label.lowercased()) + " limit"
        if let left { return limit + " refilled to \(left)% left. You can use \(provider) again." }
        return limit + " has reset. You can use \(provider) again."
    }
}
