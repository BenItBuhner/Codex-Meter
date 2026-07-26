import Foundation

public enum AdaptiveRefreshPolicy {
    private static let intervals = [5, 10, 15, 30, 60, 120]

    public static func chooseMinutes(
        snapshot: UsageSnapshot?,
        attentionScore: Double,
        localHour: Int,
        consecutiveFailures: Int,
        now: Date = Date()
    ) -> Int {
        let windows = [snapshot?.fiveHour, snapshot?.weekly]
            .compactMap { $0 }
            .filter {
                guard let reset = $0.effectiveResetDate(relativeTo: snapshot?.fetchedAt ?? now) else {
                    return true
                }
                return reset > now
            }
        let remaining = windows.map(\.remainingPercent).min()
        let limited = snapshot.map { !$0.allowed || $0.limitReached } ?? false

        var minutes = 30
        if let remaining {
            switch remaining {
            case ...10:
                minutes = 5
            case ...25:
                minutes = 10
            case ...50:
                minutes = 15
            case ...75:
                minutes = 30
            default:
                minutes = 60
            }
        }
        if limited {
            minutes = 5
        }

        let nextUsedReset = windows
            .filter { $0.usedPercent > 0 }
            .compactMap { $0.effectiveResetDate(relativeTo: snapshot?.fetchedAt ?? now) }
            .filter { $0 > now }
            .min()
        let untilReset = nextUsedReset?.timeIntervalSince(now) ?? .infinity
        if untilReset <= 15 * 60 {
            minutes = min(minutes, 5)
        } else if untilReset <= 60 * 60 {
            minutes = min(minutes, 10)
        }

        let attention = max(0, attentionScore.isFinite ? attentionScore : 0)
        switch attention {
        case 6...:
            minutes = min(minutes, 5)
        case 3...:
            minutes = min(minutes, 10)
        case 1.5...:
            minutes = min(minutes, 15)
        case 0.5...:
            minutes = min(minutes, 30)
        default:
            break
        }

        let urgent = limited || (remaining ?? 101) <= 25 || untilReset <= 60 * 60
        let hour = min(23, max(0, localHour))
        if !urgent, attention < 0.5, (1..<6).contains(hour) {
            minutes = max(minutes, 120)
        }

        for _ in 0..<min(3, max(0, consecutiveFailures)) {
            minutes = nextSlower(than: minutes)
        }
        return urgent ? min(minutes, 30) : min(minutes, 120)
    }

    private static func nextSlower(than minutes: Int) -> Int {
        intervals.first(where: { $0 > minutes }) ?? intervals[intervals.count - 1]
    }
}
