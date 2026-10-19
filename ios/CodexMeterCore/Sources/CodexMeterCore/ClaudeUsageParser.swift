import Foundation

/// Maps Claude Code's `GET /api/oauth/usage` response onto the shared `UsageSnapshot` so every
/// dashboard card works unchanged.
///
/// Observed response shape (fields may be null or missing per plan):
///
/// ```json
/// {
///   "five_hour":        {"utilization": 6.0,  "resets_at": "2026-10-19T18:59:59.9+00:00"},
///   "seven_day":        {"utilization": 35.0, "resets_at": "2026-10-23T03:00:00+00:00"},
///   "seven_day_opus":   {"utilization": 0.0,  "resets_at": null},
///   "seven_day_sonnet": {"utilization": 12.0, "resets_at": "..."},
///   "extra_usage": {"is_enabled": true, "monthly_limit": 5000, "used_credits": 120,
///                   "utilization": 2.4}
/// }
/// ```
///
/// `utilization` is percent used (0–100), matching the "% used" Claude Code prints.
public enum ClaudeUsageParser {
    public static let fiveHourSeconds: Int64 = 5 * 60 * 60
    public static let sevenDaySeconds: Int64 = 7 * 24 * 60 * 60
    public static let monthSeconds: Int64 = 30 * 24 * 60 * 60
    public static let limitIDPrefix = "claude_"

    public static func parse(
        _ string: String,
        planLabel: String = "",
        fetchedAt: Date = Date()
    ) throws -> UsageSnapshot {
        try parse(Data(string.utf8), planLabel: planLabel, fetchedAt: fetchedAt)
    }

    public static func parse(
        _ data: Data,
        planLabel: String = "",
        fetchedAt: Date = Date()
    ) throws -> UsageSnapshot {
        let root = try JSONSupport.object(from: data)
        let fiveHour = parseWindow(root["five_hour"], seconds: fiveHourSeconds)
        let weekly = parseWindow(root["seven_day"], seconds: sevenDaySeconds)
        var limitReached = reached(fiveHour) || reached(weekly)
        var additionalLimits: [UsageLimit] = []

        // Model- and surface-specific caps (seven_day_opus, seven_day_sonnet, ...). New keys
        // Anthropic adds later appear automatically as long as they follow the prefix.
        for key in root.keys.sorted() {
            let seconds: Int64
            let suffix: String
            if key.hasPrefix("seven_day_") {
                seconds = sevenDaySeconds
                suffix = String(key.dropFirst("seven_day_".count))
            } else if key.hasPrefix("five_hour_") {
                seconds = fiveHourSeconds
                suffix = String(key.dropFirst("five_hour_".count))
            } else {
                continue
            }
            guard let window = parseWindow(root[key], seconds: seconds) else {
                continue
            }
            if reached(window) {
                limitReached = true
            }
            additionalLimits.append(
                UsageLimit(
                    id: limitIDPrefix + key,
                    name: limitName(suffix),
                    allowed: true,
                    limitReached: reached(window),
                    primary: window,
                    secondary: nil
                )
            )
        }

        if let extraUsage = extraUsage(root["extra_usage"]) {
            additionalLimits.append(extraUsage)
        }

        return UsageSnapshot(
            planType: planLabel,
            allowed: !limitReached,
            limitReached: limitReached,
            fiveHour: fiveHour,
            weekly: weekly,
            monthly: nil,
            resetCreditsAvailable: nil,
            additionalLimits: additionalLimits,
            usageCredits: nil,
            fetchedAt: fetchedAt
        )
    }

    /// One `{utilization, resets_at}` object, or `nil` when Claude reports no data.
    static func parseWindow(_ value: Any?, seconds: Int64) -> UsageWindow? {
        guard let object = JSONSupport.object(value),
              let utilization = percentUsed(object["utilization"]) else {
            return nil
        }
        return UsageWindow(
            usedPercent: utilization,
            windowSeconds: seconds,
            resetAfterSeconds: 0,
            resetAt: parseResetDate(object["resets_at"])
        )
    }

    /// Paid overage ("extra usage") behaves like a monthly spending cap when it is enabled.
    static func extraUsage(_ value: Any?) -> UsageLimit? {
        guard let object = JSONSupport.object(value),
              JSONSupport.bool(object["is_enabled"], default: false),
              let utilization = percentUsed(object["utilization"]) else {
            return nil
        }
        let window = UsageWindow(
            usedPercent: utilization,
            windowSeconds: monthSeconds,
            resetAfterSeconds: 0,
            resetAt: parseResetDate(object["resets_at"])
        )
        return UsageLimit(
            id: limitIDPrefix + "extra_usage",
            name: "Extra usage",
            allowed: true,
            limitReached: window.usedPercent >= 100,
            primary: window,
            secondary: nil
        )
    }

    /// Rounds a fractional percentage half-up, matching Java's `Math.round`.
    private static func percentUsed(_ value: Any?) -> Int? {
        guard let value, !(value is NSNull),
              let used = JSONSupport.double(value),
              used.isFinite else {
            return nil
        }
        let rounded = floor(used + 0.5)
        return Int(min(1_000, max(-1_000, rounded)))
    }

    private static func reached(_ window: UsageWindow?) -> Bool {
        guard let window else { return false }
        return window.usedPercent >= 100
    }

    /// Parses ISO-8601 timestamps such as `2026-10-19T18:59:59.943648+00:00` or
    /// `2026-10-23T03:00:00Z`. Fractional seconds are dropped (truncated), like Java's
    /// `OffsetDateTime.toEpochSecond()`. Unparseable values become `nil`.
    public static func parseResetDate(_ value: Any?) -> Date? {
        if let number = value as? NSNumber,
           CFGetTypeID(number) != CFBooleanGetTypeID() {
            let seconds = number.doubleValue
            return seconds.isFinite && seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
        }
        guard let raw = value as? String else {
            return nil
        }
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.lowercased() != "null" else {
            return nil
        }
        if let timeSeparator = text.firstIndex(where: { $0 == "T" || $0 == "t" }),
           let dot = text[timeSeparator...].firstIndex(of: ".") {
            var end = text.index(after: dot)
            while end < text.endIndex, text[end].isASCII, text[end].isNumber {
                end = text.index(after: end)
            }
            text.removeSubrange(dot..<end)
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        guard let date = formatter.date(from: text),
              date.timeIntervalSince1970 > 0 else {
            return nil
        }
        return date
    }

    static func limitName(_ suffix: String) -> String {
        switch suffix {
        case "opus": return "Opus"
        case "sonnet": return "Sonnet"
        case "haiku": return "Haiku"
        case "oauth_apps": return "Connected apps"
        default:
            let spaced = suffix
                .replacingOccurrences(of: "_", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let first = spaced.first else {
                return "Additional usage"
            }
            return first.uppercased() + String(spaced.dropFirst())
        }
    }

    /// Plan badge from `GET /api/oauth/profile`. Reads the rate-limit tier first because it is
    /// the only field that separates Max 5x from Max 20x. The profile only decorates the badge,
    /// so any schema change or malformed body yields an empty label instead of an error.
    public static func planLabel(profile data: Data) -> String {
        guard !data.isEmpty, let root = try? JSONSupport.object(from: data) else {
            return ""
        }
        let account = JSONSupport.object(root["account"])
        let organization = JSONSupport.object(root["organization"])
        let posix = Locale(identifier: "en_US_POSIX")
        let tier = JSONSupport.string(organization?["rate_limit_tier"]).lowercased(with: posix)
        let type = JSONSupport.string(organization?["organization_type"]).lowercased(with: posix)
        let hasMax = JSONSupport.bool(account?["has_claude_max"], default: false)
        let hasPro = JSONSupport.bool(account?["has_claude_pro"], default: false)

        if tier.contains("max_20x") { return "Max 20x" }
        if tier.contains("max_5x") { return "Max 5x" }
        if type.contains("enterprise") { return "Enterprise" }
        if type.contains("team") { return "Team" }
        if type.contains("max") || hasMax { return "Max" }
        if type.contains("pro") || hasPro { return "Pro" }
        if type.contains("free") { return "Free" }
        return ""
    }

    public static func planLabel(profile string: String) -> String {
        planLabel(profile: Data(string.utf8))
    }

    /// Email shown on the account row; Anthropic has used both field names.
    public static func email(profile data: Data) -> String {
        guard !data.isEmpty,
              let root = try? JSONSupport.object(from: data),
              let account = JSONSupport.object(root["account"]) else {
            return ""
        }
        let email = JSONSupport.string(account["email"])
        return email.isEmpty ? JSONSupport.string(account["email_address"]) : email
    }

    public static func email(profile string: String) -> String {
        email(profile: Data(string.utf8))
    }
}
