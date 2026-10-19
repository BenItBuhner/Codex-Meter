import Foundation

/// Maps Claude Code's `GET /api/oauth/usage` response onto the shared `UsageSnapshot` so every
/// dashboard card works unchanged.
///
/// The response carries a server-built `limits` list, the meters Claude Code itself renders.
/// Usually that is just the session (5-hour) and weekly meters; some plans add a model-scoped
/// weekly allocation such as Fable. Cards follow that list, so new meters appear without an app
/// update:
///
/// ```json
/// "limits": [
///   {"kind": "session", "group": "session", "percent": 41, "resets_at": "...", "scope": null},
///   {"kind": "weekly_all", "group": "weekly", "percent": 20, "resets_at": "...", "scope": null},
///   {"kind": "weekly_scoped", "group": "weekly", "percent": 38, "resets_at": "...",
///    "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}}
/// ]
/// ```
///
/// Older responses only have `five_hour` / `seven_day` objects of the form
/// `{"utilization": 6.0, "resets_at": "..."}`; those are the fallback. The legacy per-model keys
/// (`seven_day_opus`, `seven_day_sonnet`) and internal codename keys are ignored because Claude
/// Code no longer shows them. Percentages are percent used (0–100).
public enum ClaudeUsageParser {
    public static let fiveHourSeconds: Int64 = 5 * 60 * 60
    public static let sevenDaySeconds: Int64 = 7 * 24 * 60 * 60
    public static let monthSeconds: Int64 = 30 * 24 * 60 * 60
    public static let limitIDPrefix = "claude_"
    /// Older top-level spellings of a Fable-only weekly allocation.
    static let legacyFableKeys = ["seven_day_fable", "fable_seven_day", "fable_weekly"]

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
        var fiveHour: UsageWindow?
        var weekly: UsageWindow?
        var additionalLimits: [UsageLimit] = []
        var seen = Set<String>()

        for item in JSONSupport.array(root["limits"]) ?? [] {
            guard let row = JSONSupport.object(item) else { continue }
            let kind = JSONSupport.string(row["kind"]).trimmingCharacters(in: .whitespaces).lowercased()
            let group = JSONSupport.string(row["group"]).trimmingCharacters(in: .whitespaces).lowercased()
            let scopeObject = JSONSupport.object(row["scope"])
            // An "All models" scope repeats the weekly meter; Claude Code skips it too.
            if isAllModels(scopeObject) { continue }
            let scope = scopeName(scopeObject)
            guard let window = meter(row, seconds: windowSeconds(kind: kind, group: group)) else {
                continue
            }
            if scope.isEmpty, kind == "session", fiveHour == nil {
                fiveHour = window
            } else if scope.isEmpty, kind == "weekly_all", weekly == nil {
                weekly = window
            } else {
                let suffix = scope.isEmpty ? kind
                    : group.isEmpty ? "scoped_" + slug(scope) : group + "_scoped_" + slug(scope)
                let id = limitIDPrefix + suffix
                guard seen.insert(id).inserted else { continue }
                additionalLimits.append(
                    UsageLimit(
                        id: id,
                        name: scope.isEmpty ? humanize(kind) : scope + " only",
                        allowed: true,
                        limitReached: reached(window),
                        primary: window,
                        secondary: nil
                    )
                )
            }
        }

        if fiveHour == nil {
            fiveHour = parseWindow(root["five_hour"], seconds: fiveHourSeconds)
        }
        if weekly == nil {
            weekly = parseWindow(root["seven_day"], seconds: sevenDaySeconds)
        }
        let fableID = limitIDPrefix + "weekly_scoped_fable"
        if !seen.contains(fableID) {
            for key in legacyFableKeys {
                if let window = parseWindow(root[key], seconds: sevenDaySeconds) {
                    additionalLimits.append(
                        UsageLimit(
                            id: fableID,
                            name: "Fable only",
                            allowed: true,
                            limitReached: reached(window),
                            primary: window,
                            secondary: nil
                        )
                    )
                    break
                }
            }
        }

        let limitReached = reached(fiveHour) || reached(weekly)
            || additionalLimits.contains { $0.limitReached }

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

    /// One `limits[]` row; rows without a usable percentage are skipped.
    static func meter(_ row: JSONSupport.Object, seconds: Int64) -> UsageWindow? {
        guard let percent = percentUsed(row["percent"]) else { return nil }
        return UsageWindow(
            usedPercent: percent,
            windowSeconds: seconds,
            resetAfterSeconds: 0,
            resetAt: parseResetDate(row["resets_at"])
        )
    }

    static func windowSeconds(kind: String, group: String) -> Int64 {
        if group == "session" || kind.hasPrefix("session") { return fiveHourSeconds }
        if group.hasPrefix("month") || kind.hasPrefix("month") { return monthSeconds }
        return sevenDaySeconds
    }

    private static func isAllModels(_ scope: JSONSupport.Object?) -> Bool {
        guard let scope else { return false }
        return ["model", "surface"].contains { key in
            let object = JSONSupport.object(scope[key])
            let name = object.map { JSONSupport.string($0["display_name"]) }
                ?? JSONSupport.string(scope[key])
            return name.trimmingCharacters(in: .whitespaces).lowercased() == "all models"
        }
    }

    /// Model or surface name a meter is scoped to, or "" for account-wide meters.
    static func scopeName(_ scope: JSONSupport.Object?) -> String {
        guard let scope else { return "" }
        for key in ["model", "surface"] {
            var name = ""
            if let object = JSONSupport.object(scope[key]) {
                name = JSONSupport.string(object["display_name"])
                if name.trimmingCharacters(in: .whitespaces).isEmpty {
                    name = JSONSupport.string(object["id"])
                }
            } else {
                name = JSONSupport.string(scope[key])
            }
            name = name.trimmingCharacters(in: .whitespaces)
            if !name.isEmpty, name != "null" {
                return name
            }
        }
        return ""
    }

    static func slug(_ value: String) -> String {
        let lowered = value.lowercased()
        var result = ""
        var lastWasSeparator = true
        for scalar in lowered.unicodeScalars {
            if scalar.isASCII, CharacterSet.alphanumerics.contains(scalar) {
                result.unicodeScalars.append(scalar)
                lastWasSeparator = false
            } else if !lastWasSeparator {
                result.append("_")
                lastWasSeparator = true
            }
        }
        while result.hasSuffix("_") { result.removeLast() }
        return result
    }

    /// "weekly_all" -> "Weekly all"; used only for unscoped meters Claude adds later.
    static func humanize(_ kind: String) -> String {
        let spaced = kind.replacingOccurrences(of: "_", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = spaced.first else { return "Additional usage" }
        return first.uppercased() + String(spaced.dropFirst())
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
