import Foundation

/// Maps Cursor's `DashboardService/GetCurrentPeriodUsage` response onto the shared
/// `UsageSnapshot`. Mirrors Android's `CursorUsageParser`.
///
/// Observed response shape (Connect JSON, so zero-valued fields are omitted and 64-bit numbers
/// may arrive as strings; money is in cents, percents are already 0–100):
///
/// ```json
/// {
///   "billingCycleStart": "1791676800000", "billingCycleEnd": "1794355200000",
///   "planUsage": {"totalSpend": 500, "includedSpend": 400, "limit": 2000,
///                 "totalPercentUsed": 25, "autoPercentUsed": 10, "apiPercentUsed": 15},
///   "spendLimitUsage": {"individualLimit": 1000, "individualUsed": 300,
///                       "pooledLimit": "20000", "pooledUsed": 5000, "limitType": "team"},
///   "enabled": true
/// }
/// ```
///
/// Cursor sells two kinds of plan, and both answer with this shape:
///
/// - Current plans have one included pool for Cursor models that refreshes weekly. It becomes
///   the weekly window, and spend past it is "Additional usage".
/// - Legacy plans (Pro, Pro+, Ultra, Teams Standard) keep a monthly cycle with two pools,
///   "Cursor models" and "Other models". The included total becomes the monthly window, each
///   pool a named limit, and spend past it is "On-demand".
///
/// The cycle length tells them apart: Cursor reports the weekly refresh as the cycle.
public enum CursorUsageParser {
    public static let defaultCycleSeconds: Int64 = 30 * 24 * 60 * 60
    public static let limitIDPrefix = "cursor_"

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
        let start = epochMillis(root["billingCycleStart"])
        let end = epochMillis(root["billingCycleEnd"])
        let cycleSeconds = start > 0 && end > start ? (end - start) / 1_000 : defaultCycleSeconds
        let resetAt = end > 0 ? Date(timeIntervalSince1970: TimeInterval(end / 1_000)) : nil

        let weekly = isWeeklyCycle(cycleSeconds)
        var plan: UsageWindow?
        var limits: [UsageLimit] = []
        if let planUsage = JSONSupport.object(root["planUsage"]) {
            var percent = number(planUsage["totalPercentUsed"])
            if percent == nil, let limit = number(planUsage["limit"]), limit > 0 {
                let used = number(planUsage["includedSpend"]) ?? number(planUsage["totalSpend"]) ?? 0
                percent = used / limit * 100
            }
            if let percent {
                plan = UsageWindow(
                    usedPercent: roundPercent(percent),
                    windowSeconds: cycleSeconds,
                    resetAt: resetAt
                )
            }
            // Only legacy monthly plans split usage into two pools. Connect JSON drops zero
            // values, so when one pool is reported the other is shown at 0%.
            let pools = [
                ("autoPercentUsed", "auto", "Cursor models"),
                ("apiPercentUsed", "api", "Other models")
            ]
            let hasPools = !weekly && pools.contains { number(planUsage[$0.0]) != nil }
            for (key, id, name) in pools where hasPools {
                let value = number(planUsage[key]) ?? 0
                let window = UsageWindow(
                    usedPercent: roundPercent(value),
                    windowSeconds: cycleSeconds,
                    resetAt: resetAt
                )
                limits.append(UsageLimit(
                    id: limitIDPrefix + id,
                    name: name,
                    limitReached: window.usedPercent >= 100,
                    primary: window,
                    secondary: nil
                ))
            }
        }

        let onDemand = onDemand(
            JSONSupport.object(root["spendLimitUsage"]),
            cycleSeconds: cycleSeconds,
            resetAt: resetAt,
            weekly: weekly
        )
        if let onDemand { limits.append(onDemand) }

        let planSpent = (plan?.usedPercent ?? 0) >= 100
        let limitReached = planSpent && (onDemand?.limitReached ?? true)
        return UsageSnapshot(
            planType: planLabel,
            allowed: !limitReached,
            limitReached: limitReached,
            fiveHour: nil,
            weekly: weekly ? plan : nil,
            monthly: weekly ? nil : plan,
            additionalLimits: limits,
            fetchedAt: fetchedAt
        )
    }

    /// A cycle of up to ten days is the weekly refresh of a current plan.
    public static func isWeeklyCycle(_ cycleSeconds: Int64) -> Bool {
        cycleSeconds > 0 && cycleSeconds <= 10 * 24 * 60 * 60
    }

    /// Paid usage past the included plan: "Additional usage" on current plans, "On-demand" on
    /// legacy ones. A personal cap wins over a team pool; no cap means unlimited or disabled,
    /// which has nothing to meter.
    static func onDemand(
        _ spend: [String: Any]?,
        cycleSeconds: Int64,
        resetAt: Date?,
        weekly: Bool = false
    ) -> UsageLimit? {
        guard let spend else { return nil }
        var name = weekly ? "Additional usage" : "On-demand"
        var limit = number(spend["individualLimit"]) ?? 0
        var used = number(spend["individualUsed"]) ?? 0
        if limit <= 0 {
            name = weekly ? "Team additional usage" : "Team on-demand"
            limit = number(spend["pooledLimit"]) ?? 0
            used = number(spend["pooledUsed"]) ?? 0
        }
        guard limit > 0 else { return nil }
        let window = UsageWindow(
            usedPercent: roundPercent(used / limit * 100),
            windowSeconds: cycleSeconds,
            resetAt: resetAt
        )
        return UsageLimit(
            id: limitIDPrefix + "on_demand",
            name: "\(name) (\(dollars(used)) of \(dollars(limit)))",
            limitReached: used >= limit,
            primary: window,
            secondary: nil
        )
    }

    /// Accepts epoch millis as a number or numeric string, or an ISO-8601 timestamp.
    static func epochMillis(_ value: Any?) -> Int64 {
        if let number = number(value) {
            return number > 0 ? Int64(number) : 0
        }
        guard let date = ClaudeUsageParser.parseResetDate(value) else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1_000)
    }

    /// A number that Connect JSON may encode as a string; `nil` when missing or unreadable.
    static func number(_ value: Any?) -> Double? {
        guard let value, !(value is NSNull),
              let parsed = JSONSupport.double(value), parsed.isFinite else {
            return nil
        }
        return parsed
    }

    /// Cents to a short dollar label, e.g. 1250 -> "$12.50", 2000 -> "$20".
    public static func dollars(_ cents: Double) -> String {
        let value = cents / 100
        if abs(value - value.rounded()) < 0.005 {
            return String(format: "$%.0f", locale: Locale(identifier: "en_US_POSIX"), value)
        }
        return String(format: "$%.2f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    /// Any usage shows at least 1% so a light month never reads as untouched.
    private static func roundPercent(_ percent: Double) -> Int {
        guard percent > 0 else { return 0 }
        return max(1, Int(min(1_000, floor(percent + 0.5))))
    }

    /// Plan badge from `auth/full_stripe_profile`.
    public static func planLabel(profile data: Data) -> String {
        guard !data.isEmpty, let root = try? JSONSupport.object(from: data) else { return "" }
        var type = JSONSupport.string(root["membershipType"])
        if type.isEmpty { type = JSONSupport.string(root["membership_type"]) }
        if type.isEmpty { type = JSONSupport.string(root["individualMembershipType"]) }
        switch type.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "": return ""
        case "free", "hobby": return "Hobby"
        case "free_trial": return "Pro trial"
        case "pro": return "Pro"
        case "pro_plus": return "Pro+"
        case "plus": return "Plus"
        case "premium": return "Premium"
        case "super": return "Super"
        case "ultra": return "Ultra"
        case "team", "business": return "Teams"
        case "enterprise": return "Enterprise"
        default:
            let spaced = type.replacingOccurrences(of: "_", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return spaced.prefix(1).uppercased() + spaced.dropFirst()
        }
    }

    public static func planLabel(profile string: String) -> String {
        planLabel(profile: Data(string.utf8))
    }

    /// Email on the account row when the profile includes one.
    public static func email(profile data: Data) -> String {
        guard !data.isEmpty, let root = try? JSONSupport.object(from: data) else { return "" }
        let email = JSONSupport.string(root["email"])
        return email.contains("@") ? email : ""
    }
}
