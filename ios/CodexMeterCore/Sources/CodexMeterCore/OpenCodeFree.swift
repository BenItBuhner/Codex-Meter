import Foundation

/// OpenCode's free tier: what Codex Meter can know about it without an account.
///
/// OpenCode's gateway counts free-model requests per network (IP address) per UTC day and
/// refuses with `FreeUsageLimitError` once a network reaches its daily number, with a
/// `retry-after` of the seconds left until 00:00 UTC. It has no route that reports how many
/// requests are used or left, and it only serves free models to OpenCode itself, so the daily
/// reset is the one thing that can be shown honestly. Port of Android's `OpenCodeFree`; the
/// desktop apps use the same words.
public enum OpenCodeFree {
    static let day: TimeInterval = 86_400

    /// Dashboard card title.
    public static let cardTitle = "Daily free limit"

    /// What the card says under the reset time.
    public static let explanation = "OpenCode counts free-model requests from your network each day and "
        + "resets them at midnight UTC. It doesn't share how many are left."

    /// Sign-in screen copy: there is nothing to sign in to.
    public static let howItWorks = "No sign-in needed. OpenCode's free models have a daily limit for "
        + "each network, and Codex Meter shows when it resets. OpenCode doesn't share how much of it "
        + "is used, so there's no percentage."

    /// Sign-in screen footer: nothing is stored or sent.
    public static let privacy = "Codex Meter works out the reset time on this device. Nothing is sent "
        + "to OpenCode or anyone else."

    /// The next 00:00 UTC strictly after `now`.
    public static func nextReset(after now: Date) -> Date {
        let seconds = now.timeIntervalSince1970
        return Date(timeIntervalSince1970: ((seconds / day).rounded(.down) + 1) * day)
    }

    /// The snapshot the client saves: no meters, only the plan and when it was read.
    public static func snapshot(at now: Date) -> UsageSnapshot {
        UsageSnapshot(planType: "Free", allowed: true, limitReached: false, fiveHour: nil, weekly: nil,
                      fetchedAt: now)
    }
}
