import Foundation

/// Turns each added provider's usage response into a `UsageSnapshot`. A line-for-line port of
/// Android's `ProviderUsageParsers`, so both apps read the same windows from the same body.
/// None of these routes is documented publicly; the shapes follow the providers' own CLIs and
/// editors and open-source trackers, so every reader is defensive: a missing field leaves a
/// window out rather than failing the whole refresh.
///
/// Windows land in the snapshot by length: up to a day is the short window (`fiveHour`,
/// labelled on the dashboard by its real length), about a week is `weekly`, about a month is
/// `monthly`, and anything else becomes an additional limit.
public enum ProviderUsageParsers {
    public static let hour: Int64 = 3_600
    public static let day: Int64 = 86_400
    public static let week: Int64 = 604_800
    public static let month: Int64 = 2_592_000

    typealias Object = ProviderJSONObject

    // MARK: Shared building blocks

    /// Collects windows while a response is read, then builds the snapshot.
    final class Builder {
        let now: Date
        var plan = ""
        var shortWindow: UsageWindow?
        var weekly: UsageWindow?
        var monthly: UsageWindow?
        var extras: [UsageLimit] = []
        var credits: UsageCredits?

        init(now: Date) {
            self.now = now
        }

        /// Places a window by its length; the first window of each length wins.
        func add(_ name: String, _ window: UsageWindow?) {
            guard let window else { return }
            let seconds = window.windowSeconds
            if seconds > 0, seconds <= ProviderUsageParsers.day + 60, shortWindow == nil {
                shortWindow = window
            } else if seconds >= 6 * ProviderUsageParsers.day, seconds <= 8 * ProviderUsageParsers.day, weekly == nil {
                weekly = window
            } else if seconds >= 27 * ProviderUsageParsers.day, seconds <= 32 * ProviderUsageParsers.day, monthly == nil {
                monthly = window
            } else {
                extra(name, window)
            }
        }

        func extra(_ name: String, _ window: UsageWindow?) {
            guard let window else { return }
            let id = ProviderUsageParsers.slug(name)
            guard !extras.contains(where: { $0.id == id }) else { return }
            extras.append(UsageLimit(
                id: id,
                name: name,
                allowed: window.usedPercent < 100,
                limitReached: window.usedPercent >= 100,
                primary: window,
                secondary: nil
            ))
        }

        var isEmpty: Bool {
            shortWindow == nil && weekly == nil && monthly == nil && extras.isEmpty
                && credits?.shouldDisplay != true
        }

        func build() -> UsageSnapshot {
            let reached = [shortWindow, weekly, monthly].contains { ($0?.usedPercent ?? 0) >= 100 }
            return UsageSnapshot(
                planType: plan,
                allowed: !reached,
                limitReached: reached,
                fiveHour: shortWindow,
                weekly: weekly,
                monthly: monthly,
                additionalLimits: extras,
                usageCredits: credits,
                fetchedAt: now
            )
        }
    }

    /// A window from percent used (0–100), its length, and when it resets (epoch seconds).
    static func makeWindow(_ usedPercent: Double, _ windowSeconds: Int64, _ resetAt: Int64, _ now: Date) -> UsageWindow? {
        guard !usedPercent.isNaN else { return nil }
        let nowSeconds = Int64(now.timeIntervalSince1970)
        let after = resetAt > nowSeconds ? resetAt - nowSeconds : 0
        return UsageWindow(
            usedPercent: Int(max(0, min(100, usedPercent)).rounded()),
            windowSeconds: windowSeconds,
            resetAfterSeconds: after,
            resetAt: resetAt > 0 ? Date(timeIntervalSince1970: TimeInterval(resetAt)) : nil
        )
    }

    /// Percent used from a limit and either how much is used or how much remains.
    static func percentUsed(_ limit: Double, _ used: Double, _ remaining: Double) -> Double {
        if limit.isNaN || limit <= 0 { return .nan }
        if !used.isNaN { return used * 100 / limit }
        if !remaining.isNaN { return (limit - remaining) * 100 / limit }
        return .nan
    }

    /// Epoch seconds from a timestamp in any shape these APIs use: seconds, milliseconds,
    /// microseconds, ISO-8601 instants or offsets, or a bare date. Returns 0 when unreadable.
    public static func epochSeconds(_ value: ProviderJSON?) -> Int64 {
        switch value {
        case let .number(number, _)?:
            return epochSeconds(number)
        case let .string(text)?:
            return epochSeconds(text)
        default:
            return 0
        }
    }

    static func epochSeconds(_ number: Double) -> Int64 {
        guard number.isFinite, number > 0 else { return 0 }
        if number > 1e14 { return Int64(number / 1_000_000) }
        if number > 1e11 { return Int64(number / 1_000) }
        return Int64(number)
    }

    public static func epochSeconds(_ raw: String) -> Int64 {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || text == "null" { return 0 }
        if text.range(of: #"^-?\d+(\.\d+)?$"#, options: .regularExpression) != nil {
            return epochSeconds(Double(text) ?? 0)
        }
        // Drop fractional seconds of any length; ISO8601DateFormatter is picky about them.
        let trimmed = text.replacingOccurrences(of: #"(T\d{2}:\d{2}:\d{2})\.\d+"#, with: "$1", options: .regularExpression)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: trimmed) {
            return Int64(date.timeIntervalSince1970)
        }
        let datePart = String(text.prefix(10))
        let day = ISO8601DateFormatter()
        day.formatOptions = [.withFullDate]
        day.timeZone = TimeZone(identifier: "UTC")
        if let date = day.date(from: datePart) {
            return Int64(date.timeIntervalSince1970)
        }
        return 0
    }

    static func number(_ object: Object?, _ keys: String...) -> Double {
        number(object, keys)
    }

    static func number(_ object: Object?, _ keys: [String]) -> Double {
        guard let object else { return .nan }
        for key in keys {
            switch object[key] {
            case let .number(value, _)?:
                return value
            case let .string(value)?:
                if let parsed = Double(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
                    return parsed
                }
            case let .object(inner)?:
                // Grok wraps amounts as {"val": "123"}.
                let value = number(inner, "val", "value", "amount")
                if !value.isNaN { return value }
            default:
                break
            }
        }
        return .nan
    }

    static func first(_ object: Object?, _ keys: String...) -> ProviderJSON? {
        first(object, keys)
    }

    static func first(_ object: Object?, _ keys: [String]) -> ProviderJSON? {
        guard let object else { return nil }
        for key in keys {
            guard let value = object[key], !value.isNull else { continue }
            if case let .string(text) = value, text.isEmpty { continue }
            return value
        }
        return nil
    }

    static func string(_ object: Object?, _ keys: String...) -> String {
        first(object, keys)?.text.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    static func object(_ body: String?) -> Object {
        ProviderJSON.object(body)
    }

    /// The first nested object along `path`, or nil.
    static func path(_ root: Object?, _ path: String...) -> Object? {
        var current = root
        for key in path {
            current = current?.object(key)
        }
        return current
    }

    static func money(_ amount: Double) -> String {
        guard !amount.isNaN else { return "" }
        return "$" + String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), amount)
    }

    static func balance(_ dollars: Double) -> UsageCredits? {
        guard !dollars.isNaN else { return nil }
        return UsageCredits(hasCredits: true, unlimited: false, balance: money(dollars))
    }

    static func wholeCount(_ value: Double) -> String {
        String(Int64(value.rounded(.towardZero)))
    }

    static func titleCase(_ raw: String?) -> String {
        guard let raw else { return "" }
        let cleaned = raw.replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .trimmingCharacters(in: .whitespaces)
        return cleaned.split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" })
            .map { word in word.prefix(1).uppercased() + word.dropFirst().lowercased() }
            .joined(separator: " ")
    }

    static func slug(_ name: String) -> String {
        let slug = name.lowercased()
            .replacingOccurrences(of: "[^a-z0-9]+", with: "_", options: .regularExpression)
            .replacingOccurrences(of: "^_+|_+$", with: "", options: .regularExpression)
        return slug.isEmpty ? "limit" : slug
    }

    /// Length of a window from a start and end in any timestamp shape, or `fallback`.
    static func span(_ start: ProviderJSON?, _ end: ProviderJSON?, _ fallback: Int64) -> Int64 {
        let from = epochSeconds(start)
        let to = epochSeconds(end)
        return from > 0 && to > from ? to - from : fallback
    }

    static func ratioPercent(_ ratio: Double) -> Double {
        ratio <= 1 ? ratio * 100 : ratio
    }

    // MARK: GitHub Copilot

    /// `api.github.com/copilot_internal/user`. Premium requests are the monthly window.
    public static func copilot(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = copilotPlan(string(root, "copilot_plan", "access_type_sku"))
        let reset = epochSeconds(first(root, "quota_reset_date_utc", "quota_reset_date", "limited_user_reset_date"))
        if let quotas = root.object("quota_snapshots") {
            for key in ["premium_interactions", "chat", "completions"] {
                guard let window = copilotQuota(quotas.object(key), reset, now) else { continue }
                if key == "premium_interactions", out.monthly == nil {
                    out.monthly = window
                } else {
                    out.extra(copilotName(key), window)
                }
            }
            for key in quotas.keys where !["premium_interactions", "chat", "completions"].contains(key) {
                out.extra(copilotName(key), copilotQuota(quotas.object(key), reset, now))
            }
        }
        // Free accounts report counts instead of snapshots.
        if let monthly = root.object("monthly_quotas"), let left = root.object("limited_user_quotas") {
            for key in ["chat", "completions"] {
                guard let window = makeWindow(
                    percentUsed(number(monthly, key), .nan, number(left, key)), Self.month, reset, now
                ) else { continue }
                if key == "chat", out.monthly == nil {
                    out.monthly = window
                } else {
                    out.extra(copilotName(key), window)
                }
            }
        }
        return out.build()
    }

    private static func copilotQuota(_ quota: Object?, _ reset: Int64, _ now: Date) -> UsageWindow? {
        guard let quota, !quota.bool("unlimited", default: false) else { return nil }
        let entitlement = number(quota, "entitlement")
        let remaining = number(quota, "remaining")
        // Placeholder rows for features the plan doesn't include.
        if entitlement == 0, remaining == 0 { return nil }
        let percentRemaining = number(quota, "percent_remaining")
        let used = !percentRemaining.isNaN ? 100 - percentRemaining
            : percentUsed(entitlement, .nan, remaining)
        return makeWindow(used, Self.month, reset, now)
    }

    private static func copilotName(_ key: String) -> String {
        switch key {
        case "premium_interactions": "Premium requests"
        case "chat": "Chat"
        case "completions": "Code completions"
        default: titleCase(key)
        }
    }

    public static func copilotPlan(_ sku: String?) -> String {
        let value = (sku ?? "").lowercased()
        if value.isEmpty { return "" }
        if value.contains("pro_plus") || value.contains("pro+") || value.contains("individual_pro") {
            return "Pro+"
        }
        if value.contains("free") { return "Free" }
        if value.contains("individual") || value == "pro" { return "Pro" }
        if value.contains("business") { return "Business" }
        if value.contains("enterprise") { return "Enterprise" }
        return titleCase(value)
    }

    // MARK: Kimi Code

    /// `api.kimi.com/coding/v1/usages`: 5-hour and weekly limits, as the Kimi CLI shows.
    public static func kimi(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = kimiPlan(string(path(root, "user", "membership"), "level"))
        if let limits = root.array("limits") {
            for (index, element) in limits.enumerated() {
                guard let entry = element.objectValue else { continue }
                let windowInfo = entry.object("window")
                let detail = entry.object("detail") ?? entry
                let seconds = kimiWindowSeconds(windowInfo)
                let used = percentUsed(number(detail, "limit"), number(detail, "used"), number(detail, "remaining"))
                let window = makeWindow(used, seconds, epochSeconds(first(detail, "resetTime", "reset_time", "resetAt")), now)
                out.add(seconds > 0 ? durationName(seconds) + " limit" : "Limit \(index + 1)", window)
            }
        }
        if let usages = root.object("usages") {
            kimiRatio(out, usages.object("limit_5h"), 5 * Self.hour)
            kimiRatio(out, usages.object("limit_7d"), Self.week)
            kimiRatio(out, usages.object("limit_month_total"), Self.month)
        }
        // The top-level "usage" block is the weekly quota the Kimi CLI's /usage shows first.
        if let usage = root.object("usage"), out.weekly == nil {
            let used = percentUsed(number(usage, "limit"), number(usage, "used"), number(usage, "remaining"))
            out.weekly = makeWindow(used, Self.week, epochSeconds(first(usage, "resetTime", "reset_time", "resetAt")), now)
        }
        return out.build()
    }

    private static func kimiRatio(_ out: Builder, _ entry: Object?, _ seconds: Int64) {
        guard let entry else { return }
        let ratio = number(entry, "used_ratio", "usedRatio")
        guard !ratio.isNaN else { return }
        out.add(durationName(seconds) + " limit", makeWindow(
            ratioPercent(ratio), seconds, epochSeconds(first(entry, "reset_time", "resetTime")), out.now
        ))
    }

    static func kimiWindowSeconds(_ window: Object?) -> Int64 {
        guard let window else { return 0 }
        let duration = number(window, "duration")
        guard !duration.isNaN, duration > 0 else { return 0 }
        let unit = string(window, "timeUnit", "time_unit", "unit").uppercased()
        let multiplier: Int64
        if unit.contains("MINUTE") {
            multiplier = 60
        } else if unit.contains("HOUR") {
            multiplier = Self.hour
        } else if unit.contains("DAY") {
            multiplier = Self.day
        } else if unit.contains("WEEK") {
            multiplier = Self.week
        } else if unit.contains("MONTH") {
            multiplier = Self.month
        } else {
            multiplier = 1
        }
        return Int64(duration) * multiplier
    }

    public static func kimiPlan(_ level: String?) -> String {
        switch (level ?? "").uppercased() {
        case "LEVEL_FREE": return "Adagio"
        case "LEVEL_TRIAL": return "Andante"
        case "LEVEL_BASIC": return "Moderato"
        case "LEVEL_INTERMEDIATE": return "Allegretto"
        case "LEVEL_ADVANCED": return "Allegro"
        case "": return ""
        default:
            return titleCase((level ?? "").replacingOccurrences(of: "(?i)^level_", with: "", options: .regularExpression))
        }
    }

    // MARK: Factory (Droid)

    /// `api.factory.ai/api/billing/limits`: standard and core token limits.
    public static func factory(_ body: String, plan: String?, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = plan ?? ""
        if let limits = root.object("limits") {
            let standard = limits.object("standard")
            factoryWindow(out, standard, "fiveHour", 5 * Self.hour, "", now)
            factoryWindow(out, standard, "weekly", Self.week, "", now)
            factoryWindow(out, standard, "monthly", Self.month, "", now)
            let core = limits.object("core")
            factoryWindow(out, core, "fiveHour", 5 * Self.hour, "Core ", now)
            factoryWindow(out, core, "weekly", Self.week, "Core ", now)
            factoryWindow(out, core, "monthly", Self.month, "Core ", now)
        }
        let cents = number(root, "extraUsageBalanceCents")
        if !cents.isNaN, cents > 0 { out.credits = balance(cents / 100) }
        if out.isEmpty { factorySubscription(out, root, now) }
        return out.build()
    }

    private static func factoryWindow(_ out: Builder, _ group: Object?, _ key: String, _ seconds: Int64, _ prefix: String, _ now: Date) {
        guard let entry = group?.object(key) else { return }
        var used = number(entry, "usedPercent", "used_percent")
        if used.isNaN {
            let ratio = number(entry, "usedRatio", "used_ratio")
            if !ratio.isNaN { used = ratioPercent(ratio) }
        }
        let nowSeconds = Int64(now.timeIntervalSince1970)
        var reset = epochSeconds(first(entry, "windowEnd", "window_end", "resetAt"))
        if reset <= 0 {
            let remaining = number(entry, "secondsRemaining")
            if !remaining.isNaN { reset = nowSeconds + Int64(remaining) }
        }
        // A window that already ended has reset; Factory keeps the old percent until next use.
        if reset > 0, reset <= nowSeconds { used = 0 }
        let window = makeWindow(used, seconds, reset, now)
        if prefix.isEmpty {
            out.add(durationName(seconds) + " limit", window)
        } else {
            out.extra(prefix + durationName(seconds).lowercased() + " limit", window)
        }
    }

    /// Older `app.factory.ai/api/organization/subscription/usage` token allowance.
    private static func factorySubscription(_ out: Builder, _ root: Object, _ now: Date) {
        guard let usage = root.object("usage") else { return }
        let seconds = span(usage["startDate"], usage["endDate"], Self.month)
        let reset = epochSeconds(usage["endDate"])
        for key in ["standard", "premium"] {
            guard let entry = usage.object(key) else { continue }
            let ratio = number(entry, "usedRatio")
            let percent = !ratio.isNaN ? ratioPercent(ratio)
                : percentUsed(number(entry, "totalAllowance"), number(entry, "userTokens", "orgTotalTokensUsed"), .nan)
            let window = makeWindow(percent, seconds, reset, now)
            if key == "standard", out.monthly == nil, seconds >= 27 * Self.day {
                out.monthly = window
            } else {
                out.extra(titleCase(key) + " tokens", window)
            }
        }
    }

    /// Plan name from `app.factory.ai/api/app/auth/me`.
    public static func factoryPlan(_ body: String) -> String {
        let root = object(body)
        let subscription = path(root, "organization", "subscription")
        var name = string(path(subscription, "orbSubscription", "plan"), "name")
        if name.isEmpty { name = string(subscription, "factoryTier", "tier") }
        if name.isEmpty { name = string(root.object("organization"), "factoryTier") }
        return titleCase(name.replacingOccurrences(of: #"(?i)^factory\s*"#, with: "", options: .regularExpression))
    }

    public static func factoryEmail(_ body: String) -> String {
        let root = object(body)
        var email = string(root.object("userProfile"), "email")
        if email.isEmpty { email = string(root.object("user"), "email") }
        return email.contains("@") ? email : ""
    }

    // MARK: Grok (xAI)

    /// `cli-chat-proxy.grok.com/v1/billing?format=credits`: credits for the billing period.
    public static func grok(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let config = root.object("config") ?? root
        let out = Builder(now: now)
        out.plan = grokPlan(string(config, "subscriptionTier"))
        if out.plan.isEmpty { out.plan = grokPlan(string(root, "subscriptionTier")) }
        let period = config.object("currentPeriod")
        let start = period != nil ? first(period, "start", "startTime") : first(config, "billingPeriodStart")
        let end = period != nil ? first(period, "end", "endTime") : first(config, "billingPeriodEnd")
        let seconds = span(start, end, Self.month)
        let reset = epochSeconds(end)
        var percent = number(config, "creditUsagePercent", "usagePercent")
        if !percent.isNaN, percent <= 1, percent > 0, !config.has("creditUsagePercent") {
            percent *= 100
        }
        out.add("Credits", makeWindow(percent, seconds, reset, now))
        if let products = config.array("productUsage") {
            for (index, element) in products.enumerated() {
                guard let product = element.objectValue else { continue }
                let name = titleCase(string(product, "product", "name"))
                out.extra(name.isEmpty ? "Product \(index + 1)" : name,
                          makeWindow(number(product, "usagePercent", "creditUsagePercent"), seconds, reset, now))
            }
        }
        let prepaid = number(config, "prepaidBalance")
        if !prepaid.isNaN, prepaid > 0 { out.credits = balance(prepaid / 100) }
        return out.build()
    }

    static func grokPlan(_ tier: String?) -> String {
        let value = (tier ?? "").uppercased()
        if value.isEmpty { return "" }
        if value.contains("HEAVY") { return "SuperGrok Heavy" }
        // Old SuperGrok before The X Subscription. Check before a bare SUPER (X Super).
        if value.contains("SUPER_GROK") || value.contains("SUPERGROK") { return "SuperGrok" }
        if value.contains("ULTRA") { return "Ultra" }
        if value.contains("SUPER") { return "Super" }
        if value.contains("PREMIUM_PLUS") || value.contains("PREMIUMPLUS") { return "Premium+" }
        if value.contains("PREMIUM") { return "Premium" }
        if value.contains("PLUS") { return "Plus" }
        if value.contains("FREE") { return "Free" }
        return titleCase(value.replacingOccurrences(of: "^SUBSCRIPTION_TIER_", with: "", options: .regularExpression))
    }

    // MARK: Antigravity (Google)

    /// `v1internal:retrieveUserQuotaSummary`: buckets per model family. Gemini is the main pair
    /// of windows; Claude and other third-party models become an additional limit.
    public static func antigravity(_ body: String, plan: String?, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = plan ?? ""
        var buckets: [Object] = []
        collectBuckets(.object(root), into: &buckets)
        var thirdShort: UsageWindow?
        var thirdWeekly: UsageWindow?
        for bucket in buckets {
            let id = string(bucket, "bucketId", "id", "name").lowercased()
            var fraction = number(bucket, "remainingFraction", "remaining_fraction")
            if fraction.isNaN, bucket.has("resetTime") || bucket.has("reset_time") {
                // proto3 omits zero: a bucket with a reset but no fraction is used up.
                fraction = 0
            }
            guard !fraction.isNaN else { continue }
            let weekly = id.contains("week") || id.contains("7d")
            let seconds = weekly ? Self.week : 5 * Self.hour
            let window = makeWindow((1 - fraction) * 100, seconds, epochSeconds(first(bucket, "resetTime", "reset_time")), now)
            if id.hasPrefix("gemini"), !id.contains("argon") {
                if weekly, out.weekly == nil {
                    out.weekly = window
                } else if !weekly, out.shortWindow == nil {
                    out.shortWindow = window
                }
            } else if id.hasPrefix("3p") || id.contains("third") {
                if weekly { thirdWeekly = window } else { thirdShort = window }
            } else {
                let label = string(bucket, "displayName", "label")
                out.extra(label.isEmpty ? titleCase(id) : label, window)
            }
        }
        if let primary = thirdShort ?? thirdWeekly {
            let secondary = thirdShort != nil ? thirdWeekly : nil
            let reached = primary.usedPercent >= 100 || (secondary?.usedPercent ?? 0) >= 100
            out.extras.insert(UsageLimit(
                id: "third_party",
                name: "Claude and other models",
                allowed: !reached,
                limitReached: reached,
                primary: primary,
                secondary: secondary
            ), at: 0)
        }
        return out.build()
    }

    private static func collectBuckets(_ node: ProviderJSON, into: inout [Object]) {
        switch node {
        case let .object(object):
            if object.has("bucketId") || object.has("remainingFraction") {
                into.append(object)
                return
            }
            for key in object.keys {
                if let child = object[key] { collectBuckets(child, into: &into) }
            }
        case let .array(values):
            for value in values { collectBuckets(value, into: &into) }
        default:
            break
        }
    }

    /// Plan from `v1internal:loadCodeAssist`.
    public static func antigravityPlan(_ body: String) -> String {
        let root = object(body)
        let paid = root.object("paidTier")
        let id = string(paid, "id").lowercased()
        if id.contains("ultra-lite") || id.contains("ultra_lite") { return "Ultra Lite" }
        if id.contains("ultra") { return "Ultra" }
        if id.contains("pro") { return "Pro" }
        var name = string(paid, "name")
        if name.isEmpty { name = string(root.object("currentTier"), "name") }
        return name.replacingOccurrences(of: #"(?i)^google\s+(ai\s+)?"#, with: "", options: .regularExpression)
    }

    // MARK: Devin and Windsurf

    /// Codeium `GetUserStatus`: daily and weekly quota for Devin and Windsurf plans.
    public static func codeium(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let status = root.object("userStatus") ?? root
        let plan = status.object("planStatus") ?? status
        let out = Builder(now: now)
        out.plan = string(plan.object("planInfo"), "planName")
        if out.plan.isEmpty { out.plan = string(status, "teamsTier", "planName") }
        let hideDaily = plan.object("planInfo")?.bool("hideDailyQuota", default: false) ?? false
        if !hideDaily {
            out.add("Daily quota", codeiumWindow(plan, "dailyQuotaRemainingPercent", "dailyQuotaResetAtUnix", Self.day, now))
        }
        out.weekly = codeiumWindow(plan, "weeklyQuotaRemainingPercent", "weeklyQuotaResetAtUnix", Self.week, now)
        let micros = number(plan, "overageBalanceMicros")
        if !micros.isNaN, micros > 0 { out.credits = balance(micros / 1_000_000) }
        // Older plans meter prompt credits per month instead.
        if out.weekly == nil, out.extras.isEmpty {
            let available = number(plan, "availablePromptCredits")
            let used = number(plan, "usedPromptCredits")
            if !available.isNaN, available > 0 {
                out.monthly = makeWindow(percentUsed(available, used, .nan), Self.month, epochSeconds(first(plan, "planEnd")), now)
            }
        }
        return out.build()
    }

    private static func codeiumWindow(_ plan: Object, _ percentKey: String, _ resetKey: String, _ seconds: Int64, _ now: Date) -> UsageWindow? {
        var remaining = number(plan, percentKey)
        let reset = epochSeconds(plan[resetKey])
        // proto3 omits zero values: a reset time with no percent means nothing is left.
        if remaining.isNaN, reset > 0 { remaining = 0 }
        guard !remaining.isNaN else { return nil }
        return makeWindow(100 - remaining, seconds, reset, now)
    }

    // MARK: Augment

    /// `<tenant>/get-billing-summary`: credits included per billing cycle.
    public static func augment(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = string(root, "plan_name", "planName")
        let included = number(root, "amount_included_per_cycle", "credits_included")
        let remaining = number(root, "amount_remaining", "credits_remaining")
        let reset = epochSeconds(first(root, "billing_cycle_end_date_iso", "billing_cycle_end_date", "cycle_end"))
        let window = makeWindow(percentUsed(included, .nan, remaining), Self.month, reset, now)
        out.monthly = window
        if window == nil, !remaining.isNaN {
            let unit = string(root, "usage_unit")
            out.credits = UsageCredits(
                hasCredits: true,
                unlimited: false,
                balance: wholeCount(remaining) + (unit.isEmpty ? " credits" : " " + unit.lowercased())
            )
        }
        return out.build()
    }

    // MARK: Cline

    /// `api.cline.bot/api/v1/users/me/plan/usage-limits`.
    public static func cline(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let data = root.object("data") ?? root
        let out = Builder(now: now)
        out.plan = titleCase(string(data, "planName", "plan"))
        if let limits = data.array("limits") {
            for element in limits {
                guard let entry = element.objectValue else { continue }
                let type = string(entry, "type", "window").lowercased()
                let seconds: Int64 = type.contains("five") || type.contains("5h") ? 5 * Self.hour
                    : type.contains("week") ? Self.week
                    : type.contains("month") ? Self.month
                    : type.contains("day") ? Self.day : 0
                out.add(seconds > 0 ? durationName(seconds) + " limit" : titleCase(type),
                        makeWindow(number(entry, "percentUsed", "usedPercent"), seconds,
                               epochSeconds(first(entry, "resetsAt", "resetAt")), now))
            }
        }
        return out.build()
    }

    // MARK: OpenCode Go

    static let percentKeys = ["usagePercent", "usedPercent", "percentUsed", "percent", "usage_percent",
                              "used_percent", "percent_used", "utilization"]
    static let resetInKeys = ["resetInSec", "resetInSeconds", "reset_in_sec", "reset_in_seconds",
                              "resetsInSeconds", "secondsUntilReset", "secondsRemaining"]
    static let resetAtKeys = ["resetAt", "resetsAt", "reset_at", "resets_at", "nextReset", "next_reset",
                              "renewAt", "renew_at", "resetTime", "reset_time", "windowEnd", "endTime",
                              "end_time", "nextResetTime"]

    /// `opencode.ai/zen/go/v1/usage`: rolling, weekly and monthly percentages.
    public static func opencodeGo(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let usage = root.object("usage") ?? root
        let out = Builder(now: now)
        out.plan = titleCase(string(root, "plan", "tier"))
        let windows = [["rolling", "5h", "fiveHour"], ["weekly", "week", "7d"], ["monthly", "month", "30d"]]
        let lengths: [Int64] = [5 * Self.hour, Self.week, Self.month]
        for (index, keys) in windows.enumerated() {
            guard let entry = keys.lazy.compactMap({ usage.object($0) }).first else { continue }
            var percent = number(entry, percentKeys)
            if !percent.isNaN, percent <= 1, entry.has("utilization") { percent *= 100 }
            let reset = resetFrom(entry, now)
            out.add(durationName(lengths[index]) + " limit", makeWindow(percent, lengths[index], reset, now))
        }
        return out.build()
    }

    static func resetFrom(_ entry: Object, _ now: Date) -> Int64 {
        let at = epochSeconds(first(entry, resetAtKeys))
        if at > 0 { return at }
        let within = number(entry, resetInKeys)
        return within.isNaN || within <= 0 ? 0 : Int64(now.timeIntervalSince1970) + Int64(within)
    }

    // MARK: MiniMax

    /// `api.minimax.io/v1/token_plan/remains`: prompts left in the current interval for each
    /// coding-plan model. MiniMax names the remaining count `usage_count`.
    public static func minimax(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        if let base = root.object("base_resp"), base.int("status_code", default: 0) != 0 {
            return out.build()
        }
        out.plan = string(root, "plan_name", "current_plan")
        guard let models = root.array("model_remains") else { return out.build() }
        for (index, element) in models.enumerated() {
            guard let model = element.objectValue else { continue }
            let name = string(model, "model_name")
            let percentLeft = number(model, "current_interval_remaining_percent")
            let used = !percentLeft.isNaN ? 100 - percentLeft
                : percentUsed(number(model, "current_interval_total_count"), .nan, number(model, "current_interval_usage_count"))
            let seconds = span(model["start_time"], model["end_time"], 5 * Self.hour)
            var reset = epochSeconds(model["end_time"])
            if reset <= 0 {
                let remainsMillis = number(model, "remains_time")
                if !remainsMillis.isNaN {
                    reset = Int64((now.timeIntervalSince1970 * 1_000 + remainsMillis) / 1_000)
                }
            }
            let interval = makeWindow(used, seconds, reset, now)
            let weekly = makeWindow(
                percentUsed(number(model, "current_weekly_total_count"), .nan, number(model, "current_weekly_usage_count")),
                Self.week, epochSeconds(model["weekly_end_time"]), now
            )
            if index == 0 {
                out.add("Prompts", interval)
                if let weekly { out.weekly = weekly }
            } else {
                out.extra(name.isEmpty ? "Model \(index + 1)" : name, interval)
            }
        }
        return out.build()
    }

    // MARK: Z.ai

    /// `api.z.ai/api/monitor/usage/quota/limit`: token windows plus the web-tool quota.
    public static func zai(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let data = root.object("data") ?? root
        let out = Builder(now: now)
        out.plan = titleCase(string(data, "planName", "plan", "level"))
        guard let limits = data.array("limits") else { return out.build() }
        for element in limits {
            guard let entry = element.objectValue else { continue }
            let type = string(entry, "type").uppercased()
            let seconds = zaiWindowSeconds(unit: entry.int("unit", default: 0), number: entry.int("number", default: 0))
            var percent = number(entry, "percentage")
            if percent.isNaN {
                percent = percentUsed(number(entry, "usage", "total"), number(entry, "currentValue"), number(entry, "remaining"))
            }
            let window = makeWindow(percent, seconds, epochSeconds(entry["nextResetTime"]), now)
            if type.contains("TIME") {
                out.extra("Web search and reader", window)
            } else {
                out.add(seconds > 0 ? durationName(seconds) + " limit" : titleCase(type), window)
            }
        }
        return out.build()
    }

    /// Z.ai's unit codes: 1 day, 3 hour, 5 minute, 6 week.
    static func zaiWindowSeconds(unit: Int64, number: Int64) -> Int64 {
        let base: Int64
        switch unit {
        case 1: base = Self.day
        case 3: base = Self.hour
        case 5: base = 60
        case 6: base = Self.week
        default: return 0
        }
        return base * max(1, number)
    }

    // MARK: Kiro

    /// CodeWhisperer `GetUsageLimits`: monthly agentic requests or credits.
    public static func kiro(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = string(root.object("subscriptionInfo"), "subscriptionTitle", "type")
            .replacingOccurrences(of: #"(?i)^kiro\s+"#, with: "", options: .regularExpression)
        let list = root.array("usageBreakdownList") ?? root.array("limits")
        let fallbackReset = epochSeconds(first(root, "nextDateReset", "resetDate"))
        for (index, element) in (list ?? []).enumerated() {
            guard let entry = element.objectValue else { continue }
            let limit = number(entry, "usageLimitWithPrecision", "usageLimit", "limit")
            let used = number(entry, "currentUsageWithPrecision", "currentUsage", "used")
            let reset = epochSeconds(first(entry, "nextDateReset", "resetDate"))
            let window = makeWindow(percentUsed(limit, used, .nan), Self.month, reset > 0 ? reset : fallbackReset, now)
            let name = titleCase(string(entry, "displayName", "resourceType"))
            if out.monthly == nil {
                out.monthly = window
            } else {
                out.extra(name.isEmpty ? "Limit \(index + 1)" : name, window)
            }
            if let trial = entry.object("freeTrialInfo") {
                out.extra("Free trial", makeWindow(
                    percentUsed(number(trial, "usageLimitWithPrecision", "usageLimit"),
                                number(trial, "currentUsageWithPrecision", "currentUsage"), .nan),
                    Self.month, epochSeconds(trial["freeTrialExpiry"]), now
                ))
            }
        }
        return out.build()
    }

    // MARK: OpenRouter

    /// `/api/v1/key` (spend limit) plus `/api/v1/credits` (balance).
    public static func openrouter(keyBody: String, creditsBody: String, now: Date) -> UsageSnapshot {
        let key = object(keyBody)
        let data = key.object("data") ?? key
        let out = Builder(now: now)
        out.plan = data.bool("is_free_tier", default: false) ? "Free" : ""
        let limit = number(data, "limit")
        if !limit.isNaN, limit > 0 {
            let period = string(data, "limit_reset").lowercased()
            let seconds: Int64 = period.contains("day") ? Self.day
                : period.contains("week") ? Self.week
                : period.contains("month") ? Self.month : 0
            let used = seconds == Self.day ? number(data, "usage_daily", "usage")
                : seconds == Self.week ? number(data, "usage_weekly", "usage")
                : seconds == Self.month ? number(data, "usage_monthly", "usage")
                : number(data, "usage")
            let remaining = number(data, "limit_remaining")
            out.add("Key limit", makeWindow(percentUsed(limit, used, remaining), seconds, nextPeriodStart(seconds, now), now))
        }
        if let credits = object(creditsBody).object("data") {
            let total = number(credits, "total_credits")
            let used = number(credits, "total_usage")
            if !total.isNaN {
                out.credits = balance(total - (used.isNaN ? 0 : used))
            }
        }
        return out.build()
    }

    /// OpenRouter limits reset at midnight UTC (daily), Monday (weekly) or the 1st (monthly).
    static func nextPeriodStart(_ seconds: Int64, _ now: Date) -> Int64 {
        guard seconds > 0 else { return 0 }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let today = calendar.startOfDay(for: now)
        let next: Date?
        if seconds == Self.day {
            next = calendar.date(byAdding: .day, value: 1, to: today)
        } else if seconds == Self.week {
            // ISO weekday: Monday 1 … Sunday 7; Calendar's weekday is Sunday 1 … Saturday 7.
            let weekday = calendar.component(.weekday, from: today)
            let iso = weekday == 1 ? 7 : weekday - 1
            next = calendar.date(byAdding: .day, value: 8 - iso, to: today)
        } else {
            let components = calendar.dateComponents([.year, .month], from: today)
            next = calendar.date(from: components).flatMap { calendar.date(byAdding: .month, value: 1, to: $0) }
        }
        return next.map { Int64($0.timeIntervalSince1970) } ?? 0
    }

    // MARK: Amp

    nonisolated(unsafe) private static let ampFree = try! NSRegularExpression(
        pattern: #"\$\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*/\s*\$\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*remaining"#,
        options: [.caseInsensitive]
    )
    nonisolated(unsafe) private static let ampCredits = try! NSRegularExpression(
        pattern: #"\$\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:in\s+)?(?:individual\s+)?credits"#,
        options: [.caseInsensitive]
    )

    /// `ampcode.com/api/internal?userDisplayBalanceInfo`: Amp returns the sentence the CLI
    /// prints, e.g. "Amp Free: $7.12/$10 remaining (replenishes +$0.42/hour) · $5.00 credits".
    /// The free tier refills continuously, so it is shown as a daily window.
    public static func amp(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        var text = root.object("result").map { string($0, "displayText", "text") } ?? ""
        if text.isEmpty { text = string(root, "displayText", "result") }
        if text.isEmpty { text = body }
        let out = Builder(now: now)
        let range = NSRange(text.startIndex..., in: text)
        if let match = ampFree.firstMatch(in: text, range: range) {
            let remaining = parseAmount(text, match.range(at: 1))
            let total = parseAmount(text, match.range(at: 2))
            out.plan = "Free"
            out.add("Free usage", makeWindow(percentUsed(total, .nan, remaining), Self.day, 0, now))
        }
        if let match = ampCredits.firstMatch(in: text, range: range) {
            out.credits = balance(parseAmount(text, match.range(at: 1)))
        }
        return out.build()
    }

    private static func parseAmount(_ text: String, _ range: NSRange) -> Double {
        guard let swiftRange = Range(range, in: text) else { return .nan }
        return Double(text[swiftRange].replacingOccurrences(of: ",", with: "")) ?? .nan
    }

    // MARK: Balance-only providers

    /// Kilo `/api/profile/balance`: a dollar balance.
    public static func kilo(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let data = root.object("data") ?? root
        let out = Builder(now: now)
        var dollars = number(data, "balance", "balanceUsd", "credits")
        if dollars.isNaN {
            let micros = number(data, "balance_mUsd", "microdollars")
            if !micros.isNaN { dollars = micros / 1_000_000 }
        }
        if !dollars.isNaN { out.credits = balance(dollars) }
        return out.build()
    }

    /// Codebuff `/api/v1/usage`: credits used against the cycle quota.
    public static func codebuff(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = titleCase(string(root, "subscriptionTier", "plan", "tier"))
        let quota = number(root, "quota", "limit")
        let used = number(root, "usage", "used")
        let reset = epochSeconds(first(root, "next_quota_reset", "nextQuotaReset", "resetAt"))
        out.monthly = makeWindow(percentUsed(quota, used, .nan), Self.month, reset, now)
        let remaining = number(root, "remainingBalance", "balance")
        if !remaining.isNaN, out.monthly == nil {
            out.credits = UsageCredits(hasCredits: true, unlimited: false, balance: wholeCount(remaining) + " credits")
        }
        return out.build()
    }

    // MARK: Key-only heuristics

    /// Synthetic, Chutes, v0 and Warp don't publish one stable shape, so this walks the
    /// response for anything that looks like a quota: an object with a limit and a used or
    /// remaining count, or a percent, plus a reset time. Window length comes from its name
    /// ("5h", "weekly", "daily", "monthly") or, failing that, from `defaultSeconds`.
    public static func keyQuota(_ body: String, defaultSeconds: Int64, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = titleCase(string(root, "plan", "planName", "tier", "subscription"))
        walkQuotas(.object(root), name: "", defaultSeconds: defaultSeconds, out: out, depth: 0)
        return out.build()
    }

    private static func walkQuotas(_ node: ProviderJSON, name: String, defaultSeconds: Int64, out: Builder, depth: Int) {
        guard depth <= 6 else { return }
        switch node {
        case let .array(values):
            for value in values {
                let itemName = value.objectValue.map { string($0, "name", "type", "window", "period", "label", "id") } ?? ""
                walkQuotas(value, name: itemName.isEmpty ? name : itemName, defaultSeconds: defaultSeconds, out: out, depth: depth + 1)
            }
        case let .object(object):
            var percent = number(object, percentKeys)
            if !percent.isNaN, percent <= 1, object.has("utilization") { percent *= 100 }
            if percent.isNaN {
                percent = percentUsed(
                    number(object, "limit", "max", "total", "quota", "allowed", "requestLimit"),
                    number(object, "used", "usage", "count", "consumed", "requestsUsedSinceLastRefresh", "requestsUsed"),
                    number(object, "remaining", "left", "available")
                )
            }
            if !percent.isNaN {
                var seconds = secondsFromName(name)
                if seconds <= 0 { seconds = secondsFromObject(object) }
                if seconds <= 0 { seconds = defaultSeconds }
                let label = name.isEmpty ? durationName(seconds) + " limit" : titleCase(name)
                out.add(label, makeWindow(percent, seconds, resetFrom(object, out.now), out.now))
                return
            }
            for key in object.keys {
                if let child = object[key] {
                    walkQuotas(child, name: key, defaultSeconds: defaultSeconds, out: out, depth: depth + 1)
                }
            }
        default:
            break
        }
    }

    static func secondsFromName(_ name: String?) -> Int64 {
        let value = (name ?? "").lowercased()
        if value.isEmpty { return 0 }
        if value.contains("five") || value.contains("5h") || value.contains("5_hour") || value.contains("rolling") {
            return 5 * Self.hour
        }
        if value.contains("hour") { return Self.hour }
        if value.contains("week") || value.contains("7d") { return Self.week }
        if value.contains("day") || value.contains("daily") || value.contains("24h") { return Self.day }
        if value.contains("month") || value.contains("30d") { return Self.month }
        return 0
    }

    private static func secondsFromObject(_ object: Object) -> Int64 {
        let seconds = number(object, "windowSeconds", "window_seconds", "periodSeconds", "durationSeconds")
        if !seconds.isNaN, seconds > 0 { return Int64(seconds) }
        return secondsFromName(string(object, "window", "period", "interval", "refreshDuration"))
    }

    /// "5-hour", "Weekly", "Daily", "Monthly" or "N-day" for a window length.
    public static func durationName(_ seconds: Int64) -> String {
        if seconds <= 0 { return "Usage" }
        if seconds == Self.week { return "Weekly" }
        if seconds >= 27 * Self.day, seconds <= 32 * Self.day { return "Monthly" }
        if seconds == Self.day { return "Daily" }
        if seconds % Self.day == 0 { return "\(seconds / Self.day)-day" }
        if seconds % Self.hour == 0 { return "\(seconds / Self.hour)-hour" }
        return "\(max(1, seconds / 60))-minute"
    }
}
