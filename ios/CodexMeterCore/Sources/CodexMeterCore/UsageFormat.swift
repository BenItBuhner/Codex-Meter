import Foundation

public enum UsageDisplayMode: String, Codable, Sendable, Equatable, CaseIterable {
    case used
    case remaining
}

public enum UsageResetDisplayMode: String, Codable, Sendable, Equatable, CaseIterable {
    case hidden
    case absolute
    case relative
    case both
}

public enum UsageFormat {
    public static func planLabel(_ planType: String?) -> String {
        guard let planType else {
            return ""
        }
        let normalized = planType
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")

        switch normalized {
        case "free": return "Free"
        case "go": return "Go"
        case "plus": return "Plus"
        case "prolite", "pro5x": return "Pro 5x"
        case "pro", "pro20x": return "Pro 20x"
        default: return ""
        }
    }

    public static func percent(
        _ window: UsageWindow?,
        display: UsageDisplayMode = .remaining,
        compact: Bool = false,
        showsPercentSymbol: Bool = true
    ) -> String {
        guard let window else {
            return compact ? "—" : "Unavailable"
        }
        let value = display == .used ? window.usedPercent : window.remainingPercent
        let number = showsPercentSymbol ? "\(value)%" : "\(value)"
        guard !compact else {
            return number
        }
        return "\(number) \(display == .used ? "used" : "left")"
    }

    public static func reset(
        _ window: UsageWindow?,
        display: UsageResetDisplayMode,
        fetchedAt: Date,
        now: Date = Date(),
        calendar: Calendar = .current,
        locale: Locale = .current,
        uses24HourClock: Bool = false
    ) -> String {
        guard let window, display != .hidden else {
            return ""
        }
        guard let resetAt = window.effectiveResetDate(relativeTo: fetchedAt) else {
            return "Reset time unavailable"
        }

        let absolute = absolute(
            resetAt,
            relativeTo: now,
            calendar: calendar,
            locale: locale,
            uses24HourClock: uses24HourClock
        )
        let relative = relative(until: resetAt, from: now)
        switch display {
        case .hidden:
            return ""
        case .relative:
            return "Resets \(relative)"
        case .both:
            return "Resets \(absolute) (\(relative))"
        case .absolute:
            return "Resets \(absolute)"
        }
    }

    public static func absolute(
        _ date: Date,
        relativeTo now: Date,
        calendar: Calendar = .current,
        locale: Locale = .current,
        uses24HourClock: Bool = false
    ) -> String {
        let prefix: String
        if calendar.isDate(date, inSameDayAs: now) {
            prefix = "today at"
        } else if let tomorrow = calendar.date(byAdding: .day, value: 1, to: now),
                  calendar.isDate(date, inSameDayAs: tomorrow) {
            prefix = "tomorrow at"
        } else {
            let dayFormatter = DateFormatter()
            dayFormatter.calendar = calendar
            dayFormatter.locale = locale
            dayFormatter.dateFormat = "EEE, MMM d"
            prefix = "\(dayFormatter.string(from: date)) at"
        }

        let timeFormatter = DateFormatter()
        timeFormatter.calendar = calendar
        timeFormatter.locale = locale
        timeFormatter.dateFormat = uses24HourClock ? "HH:mm" : "h:mm a"
        return "\(prefix) \(timeFormatter.string(from: date))"
    }

    public static func relative(until date: Date, from now: Date = Date()) -> String {
        let totalMinutes = Int64(max(0, date.timeIntervalSince(now)) / 60)
        let days = totalMinutes / 1_440
        let hours = (totalMinutes % 1_440) / 60
        let minutes = totalMinutes % 60
        if days > 0 {
            return "in \(days)d \(hours)h"
        }
        if hours > 0 {
            return "in \(hours)h \(minutes)m"
        }
        return totalMinutes > 0 ? "in \(totalMinutes)m" : "now"
    }

    public static func updated(fetchedAt: Date?, now: Date = Date()) -> String {
        guard let fetchedAt else {
            return "Not updated yet"
        }
        let minutes = Int64(max(0, now.timeIntervalSince(fetchedAt)) / 60)
        if minutes < 1 {
            return "Updated just now"
        }
        if minutes < 60 {
            return "Updated \(minutes)m ago"
        }
        let hours = minutes / 60
        return hours < 24 ? "Updated \(hours)h ago" : "Updated \(hours / 24)d ago"
    }
}
