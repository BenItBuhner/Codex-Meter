import Foundation

/// Turns each added provider's usage response into a `UsageSnapshot`. A line-for-line port of
/// Android's `ProviderUsageParsers`, so both apps read the same windows from the same body.
/// Synthetic and v0 document their routes; Chutes publishes its API source; the rest follow the
/// providers' own CLIs and editors and open-source trackers (CodexBar, OpenClaw). Every reader
/// is defensive: a missing field leaves a window out rather than failing the whole refresh.
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
        // A time with no zone (Chutes' naive timestamps) is UTC with its time of day.
        if trimmed.range(of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$"#, options: .regularExpression) != nil,
           let date = formatter.date(from: trimmed + "Z") {
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

    /// A field named a ratio (`used_ratio`, `usedRatio`) is always a fraction of the limit,
    /// so 1.2 is 120% (over the limit), never 1.2%.
    static func ratioPercent(_ ratio: Double) -> Double {
        ratio * 100
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
        // Token-based billing (Copilot Business since 2026) and unlimited seats have no metered
        // window; show the plan as unlimited instead of failing the refresh.
        var unlimited = root.bool("token_based_billing", default: false)
        if let quotas = root.object("quota_snapshots"), !unlimited {
            unlimited = ["premium_interactions", "chat"].contains {
                quotas.object($0)?.bool("unlimited", default: false) == true
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
        if unlimited, out.isEmpty { out.credits = UsageCredits(hasCredits: false, unlimited: true, balance: "") }
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
        // Both fields are percentage points (CodexBar's GrokCreditsProxyFetcher), so 0.5 is 0.5%.
        var percent = number(config, "creditUsagePercent", "usagePercent")
        if percent.isNaN {
            // Pay-as-you-go accounts report spend against their on-demand cap instead.
            let cap = number(config, "onDemandCap")
            let used = number(config, "onDemandUsed")
            if !cap.isNaN, cap > 0, !used.isNaN { percent = used / cap * 100 }
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
        if value.contains("SUPER") { return "SuperGrok" }
        if value.contains("PREMIUM_PLUS") || value.contains("PREMIUMPLUS") { return "Premium+" }
        if value.contains("PREMIUM") { return "Premium" }
        if value.contains("FREE") { return "Free" }
        return titleCase(value.replacingOccurrences(of: "^SUBSCRIPTION_TIER_", with: "", options: .regularExpression))
    }

    // MARK: Antigravity (Google)

    /// Antigravity usage from any of Cloud Code's three quota replies: `retrieveUserQuotaSummary`
    /// (`groups[].buckets[]` with `remaining`), `fetchAvailableModels` (`models.<id>.quotaInfo`) or
    /// `retrieveUserQuota` (`buckets[]` per model). Gemini is the main pair of windows; Claude and
    /// GPT become one additional limit. A bucket with no fraction, or a disabled one, is unknown.
    /// Mirrors Android.
    public static func antigravity(_ body: String, plan: String?, now: Date) -> UsageSnapshot {
        var root = object(body)
        if let wrapped = root.object("response") ?? root.object("summary"), wrapped.has("groups") { root = wrapped }
        let out = Builder(now: now)
        out.plan = plan ?? ""
        var thirdShort: UsageWindow?
        var thirdWeekly: UsageWindow?
        if let groups = root.array("groups") {
            for case let .object(group) in groups {
                let groupName = string(group, "displayName", "name")
                let family = antigravityFamily(groupName)
                for case let .object(bucket) in group.array("buckets") ?? [] {
                    if bucket.bool("disabled", default: false) { continue }
                    let id = string(bucket, "bucketId", "id", "name")
                    let fraction = antigravityFraction(bucket)
                    guard !fraction.isNaN else { continue }
                    let weekly = antigravityWeekly(string(bucket, "window") + " " + id + " " + string(bucket, "displayName"))
                    let window = makeWindow((1 - fraction) * 100, weekly ? Self.week : 5 * Self.hour,
                                            epochSeconds(first(bucket, "resetTime", "reset_time")), now)
                    let bucketFamily = family.isEmpty ? antigravityFamily(id) : family
                    if bucketFamily == "gemini" {
                        if weekly, out.weekly == nil {
                            out.weekly = window
                        } else if !weekly, out.shortWindow == nil {
                            out.shortWindow = window
                        }
                    } else if bucketFamily == "third" {
                        if weekly { thirdWeekly = thirdWeekly ?? window } else { thirdShort = thirdShort ?? window }
                    } else {
                        let label = groupName.isEmpty ? titleCase(id) : groupName
                        out.extra(label + (weekly ? " weekly" : " 5-hour"), window)
                    }
                }
            }
        } else {
            // Per-model quotas: each family shows its most used model.
            var rows: [(name: String, fraction: Double, reset: ProviderJSON?)] = []
            if let models = root.object("models") {
                for id in models.keys {
                    guard let model = models.object(id), let quota = model.object("quotaInfo") else { continue }
                    rows.append((id + " " + string(model, "displayName", "label"), number(quota, "remainingFraction"),
                                 first(quota, "resetTime")))
                }
            }
            for case let .object(bucket) in root.array("buckets") ?? [] {
                rows.append((string(bucket, "modelId", "bucketId"), number(bucket, "remainingFraction"), first(bucket, "resetTime")))
            }
            var gemini: (fraction: Double, reset: ProviderJSON?)?
            var third: (fraction: Double, reset: ProviderJSON?)?
            for row in rows where !row.fraction.isNaN {
                let family = antigravityFamily(row.name)
                if family == "gemini", gemini == nil || row.fraction < gemini!.fraction {
                    gemini = (row.fraction, row.reset)
                } else if family == "third", third == nil || row.fraction < third!.fraction {
                    third = (row.fraction, row.reset)
                }
            }
            if let gemini {
                out.shortWindow = makeWindow((1 - gemini.fraction) * 100, 5 * Self.hour, epochSeconds(gemini.reset), now)
            }
            if let third {
                thirdShort = makeWindow((1 - third.fraction) * 100, 5 * Self.hour, epochSeconds(third.reset), now)
            }
        }
        if let primary = thirdShort ?? thirdWeekly {
            let secondary = thirdShort != nil ? thirdWeekly : nil
            let reached = primary.usedPercent >= 100 || (secondary?.usedPercent ?? 0) >= 100
            out.extras.insert(UsageLimit(
                id: "third_party",
                name: "Claude and GPT models",
                allowed: !reached,
                limitReached: reached,
                primary: primary,
                secondary: secondary
            ), at: 0)
        }
        return out.build()
    }

    /// "gemini", "third" (Claude, GPT and other partner models) or "" for anything else.
    private static func antigravityFamily(_ name: String) -> String {
        let value = name.lowercased()
        if value.contains("claude") || value.contains("gpt") || value.hasPrefix("3p") || value.contains("third") { return "third" }
        // A versioned family such as "Gemini 4 Argon" is a separate quota with its own name.
        return value.contains("gemini") && !value.contains("argon") ? "gemini" : ""
    }

    private static func antigravityWeekly(_ text: String) -> Bool {
        let value = text.lowercased()
        return value.contains("week") || value.contains("7d") || value.contains("7-day")
    }

    /// `remaining` is `{remainingFraction}` or a oneof `{case, value}`.
    private static func antigravityFraction(_ bucket: Object) -> Double {
        let direct = number(bucket, "remainingFraction", "remaining_fraction")
        if !direct.isNaN { return direct }
        guard let remaining = bucket.object("remaining") else { return .nan }
        let nested = number(remaining, "remainingFraction", "remaining_fraction")
        if !nested.isNaN { return nested }
        return string(remaining, "case") == "remainingFraction" ? number(remaining, "value") : .nan
    }

    /// True when the account's OAuth client can't read consumer quota (sign in again).
    public static func antigravityClientRejected(_ body: String) -> Bool {
        let root = object(body)
        if root.has("currentTier") || !antigravityProject(body).isEmpty { return false }
        return (root.array("ineligibleTiers") ?? []).contains { node in
            guard case let .object(tier) = node else { return false }
            return string(tier, "reasonCode") == "GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT"
        }
    }

    /// Google Cloud project the account's Code Assist quota is billed to, when given.
    public static func antigravityProject(_ body: String) -> String {
        var root = object(body)
        // onboardUser wraps its answer in "response".
        if !root.has("cloudaicompanionProject"), let inner = root.object("response") { root = inner }
        if let project = root.object("cloudaicompanionProject") { return string(project, "id", "projectId", "name") }
        return string(root, "cloudaicompanionProject")
    }

    /// The tier to onboard an account that has no Code Assist project yet.
    public static func antigravityOnboardTier(_ body: String) -> String {
        let root = object(body)
        var tiers: [Object] = []
        for case let .object(tier) in root.array("allowedTiers") ?? [] { tiers.append(tier) }
        if let pick = tiers.first(where: { $0.bool("isDefault", default: false) && !string($0, "id").isEmpty })
            ?? tiers.first(where: { !string($0, "id").isEmpty }) {
            return string(pick, "id")
        }
        let paid = string(root.object("paidTier"), "id")
        return paid.isEmpty ? string(root.object("currentTier"), "id") : paid
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
            // The API's percentages are 0–100 (CodexBar reads this route as percent), so 1 is 1%.
            let percent = number(entry, percentKeys)
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
        var root = object(body)
        let out = Builder(now: now)
        if let base = root.object("base_resp"), base.int("status_code", default: 0) != 0 {
            return out.build()
        }
        // The token plan API wraps its answer in "data"; the coding plan one doesn't.
        if let data = root.object("data"), !root.has("model_remains") { root = data }
        out.plan = minimaxPlan(root)
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
            let weeklyLeft = number(model, "current_weekly_remaining_percent")
            let weekly = makeWindow(
                !weeklyLeft.isNaN ? 100 - weeklyLeft
                    : percentUsed(number(model, "current_weekly_total_count"), .nan, number(model, "current_weekly_usage_count")),
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

    static func minimaxPlan(_ root: Object) -> String {
        var plan = string(root, "current_subscribe_title", "plan_name", "combo_title", "current_plan_title", "current_plan")
        if plan.isEmpty { plan = string(root.object("current_subscribe"), "current_subscribe_title") }
        if plan.isEmpty { plan = string(root.object("current_combo_card"), "title") }
        return plan
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

    /// CodeWhisperer `GetUsageLimits`: monthly credits, plus any other metered resource.
    public static func kiro(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = string(root.object("subscriptionInfo"), "subscriptionTitle", "type")
            .replacingOccurrences(of: #"(?i)^kiro\s+"#, with: "", options: .regularExpression)
        let list = root.array("usageBreakdownList") ?? root.array("limits")
        let fallbackReset = epochSeconds(first(root, "nextDateReset", "resetDate"))
        // Kiro's credits (resourceType CREDIT) are the plan's main meter; list them first.
        var entries = (list ?? []).compactMap(\.objectValue)
        if let credit = entries.firstIndex(where: { string($0, "resourceType").uppercased() == "CREDIT" }) {
            entries.insert(entries.remove(at: credit), at: 0)
        }
        for (index, entry) in entries.enumerated() {
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

    private static let amount = #"([0-9][0-9,]*(?:\.[0-9]+)?)"#
    private static let ampFree = BuiltInPattern.compile(
        pattern: #"Amp Free:\s*\$\s*"# + amount + #"\s*/\s*\$\s*"# + amount + #"\s*remaining"#,
        options: [.caseInsensitive]
    )
    private static let ampFreePercent = BuiltInPattern.compile(
        pattern: #"Amp Free:\s*"# + amount + #"\s*%\s*remaining"#, options: [.caseInsensitive]
    )
    private static let ampTier = BuiltInPattern.compile(
        pattern: #"Amp\s+(.+?)\s+(?:Tier|Subscription):\s*agent usage\s*\$\s*"# + amount
            + #"\s*of\s*\$\s*"# + amount + #"\s*remaining"#,
        options: [.caseInsensitive]
    )
    private static let ampOrb = BuiltInPattern.compile(
        pattern: #"orb usage\s*"# + amount + #"\s*h\s*of\s*"# + amount + #"\s*h"#, options: [.caseInsensitive]
    )
    private static let ampPeriod = BuiltInPattern.compile(
        pattern: #"period\s+(\d{4}-\d{2}-\d{2})\s+to\s+(\d{4}-\d{2}-\d{2})"#, options: [.caseInsensitive]
    )
    private static let ampCredits = BuiltInPattern.compile(
        pattern: #"(?:Individual credits:\s*\$\s*"# + amount + #"\s*remaining|\$\s*"# + amount
            + #"\s*(?:in\s+)?(?:individual\s+)?credits)"#,
        options: [.caseInsensitive]
    )

    /// `ampcode.com/api/internal?userDisplayBalanceInfo`: Amp returns the text the CLI's
    /// `amp usage` prints. Amp Free is a daily allowance that resets at 8 PM New York time
    /// (older replies give dollars, newer ones a percentage); paid tiers report agent dollars
    /// and orb hours for the billing period; individual credits are a dollar balance.
    public static func amp(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        var text = root.object("result").map { string($0, "displayText", "text") } ?? ""
        if text.isEmpty { text = string(root, "displayText", "result") }
        if text.isEmpty { text = body }
        let out = Builder(now: now)
        let range = NSRange(text.startIndex..., in: text)
        if let match = ampFree.firstMatch(in: text, range: range) {
            out.plan = "Free"
            out.add("Free usage", makeWindow(percentUsed(parseAmount(text, match.range(at: 2)), .nan,
                                                         parseAmount(text, match.range(at: 1))),
                                             Self.day, ampFreeReset(now), now))
        } else if let match = ampFreePercent.firstMatch(in: text, range: range) {
            out.plan = "Free"
            out.add("Free usage", makeWindow(100 - parseAmount(text, match.range(at: 1)), Self.day,
                                             ampFreeReset(now), now))
        }
        if let tier = ampTier.firstMatch(in: text, range: range) {
            out.plan = titleCase(substring(text, tier.range(at: 1)))
            var seconds = Self.month
            var reset: Int64 = 0
            if let period = ampPeriod.firstMatch(in: text, range: range) {
                let start = substring(text, period.range(at: 1))
                let end = substring(text, period.range(at: 2))
                seconds = span(.string(start), .string(end), Self.month)
                reset = epochSeconds(end)
            }
            let agent = makeWindow(percentUsed(parseAmount(text, tier.range(at: 3)), .nan,
                                               parseAmount(text, tier.range(at: 2))), seconds, reset, now)
            if seconds >= 27 * Self.day, seconds <= 32 * Self.day {
                out.monthly = agent
            } else {
                out.extra("Agent usage", agent)
            }
            if let orb = ampOrb.firstMatch(in: text, range: range) {
                out.extra("Orb hours", makeWindow(percentUsed(parseAmount(text, orb.range(at: 2)), .nan,
                                                              parseAmount(text, orb.range(at: 1))),
                                                  seconds, reset, now))
            }
        }
        if let match = ampCredits.firstMatch(in: text, range: range) {
            let group = match.range(at: 1).location != NSNotFound ? match.range(at: 1) : match.range(at: 2)
            out.credits = balance(parseAmount(text, group))
        }
        return out.build()
    }

    /// Amp Free refills at 8 PM in New York.
    static func ampFreeReset(_ now: Date) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        guard let newYork = TimeZone(identifier: "America/New_York") else { return 0 }
        calendar.timeZone = newYork
        guard var reset = calendar.date(bySettingHour: 20, minute: 0, second: 0, of: now) else { return 0 }
        if reset <= now, let next = calendar.date(byAdding: .day, value: 1, to: reset) { reset = next }
        return Int64(reset.timeIntervalSince1970)
    }

    private static func substring(_ text: String, _ range: NSRange) -> String {
        guard let swiftRange = Range(range, in: text) else { return "" }
        return String(text[swiftRange])
    }

    private static func parseAmount(_ text: String, _ range: NSRange) -> Double {
        guard let swiftRange = Range(range, in: text) else { return .nan }
        return Double(text[swiftRange].replacingOccurrences(of: ",", with: "")) ?? .nan
    }

    // MARK: Balance-only providers

    /// Kilo's tRPC batch, as the web app and CodexBar read it: `user.getCreditBlocks` gives the
    /// credit balance, `kiloPass.getState` the Kilo Pass month (base plus bonus credits).
    public static func kiloTrpc(_ body: String, now: Date) -> UsageSnapshot {
        let out = Builder(now: now)
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let entries: [ProviderJSON] = ProviderJSON.parse(trimmed)?.arrayValue ?? [.object(object(body))]
        let blocks = kiloPayload(entries.first?.objectValue)
        let pass = kiloPayload(entries.count > 1 ? entries[1].objectValue : nil)
        if let blocks {
            var micros = Double.nan
            if let list = blocks.array("creditBlocks"), !list.isEmpty {
                micros = 0
                for item in list {
                    let value = number(item.objectValue, "balance_mUsd")
                    if !value.isNaN { micros += value }
                }
            }
            if micros.isNaN { micros = number(blocks, "totalBalance_mUsd") }
            if !micros.isNaN { out.credits = balance(max(0, micros) / 1_000_000) }
        }
        if let subscription = pass?.object("subscription") {
            let tier = string(subscription, "tier")
            out.plan = switch tier {
            case "tier_19": "Starter"
            case "tier_49": "Pro"
            case "tier_199": "Expert"
            case "": "Kilo Pass"
            default: tier
            }
            let used = number(subscription, "currentPeriodUsageUsd")
            let base = number(subscription, "currentPeriodBaseCreditsUsd")
            let bonus = number(subscription, "currentPeriodBonusCreditsUsd")
            let total = base.isNaN ? Double.nan : base + (bonus.isNaN ? 0 : max(0, bonus))
            out.monthly = makeWindow(percentUsed(total, used, .nan), Self.month, epochSeconds(
                first(subscription, "nextBillingAt", "nextRenewalAt", "renewsAt", "renewAt")), now)
        }
        return out.build()
    }

    /// True when a Kilo tRPC entry is an auth error.
    public static func kiloUnauthorized(_ body: String) -> Bool {
        let text = body.lowercased()
        return text.contains("\"unauthorized\"") || text.contains("\"forbidden\"")
    }

    private static func kiloPayload(_ entry: Object?) -> Object? {
        guard let result = entry?.object("result") else { return nil }
        if let data = result.object("data") {
            if let json = data.object("json") { return json }
            return data.has("json") ? nil : data
        }
        return result.object("json")
    }

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

    /// Codebuff `/api/user/subscription`: the weekly rate limit and tier, added to the usage.
    public static func codebuffSubscription(_ usage: UsageSnapshot, _ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let subscription = root.object("subscription")
        let limit = root.object("rateLimit")
        var plan = string(subscription, "displayName")
        if plan.isEmpty { plan = string(root, "displayName") }
        if plan.isEmpty { plan = titleCase(string(subscription, "tier")) }
        let weekly = makeWindow(percentUsed(number(limit, "weeklyLimit", "limit"), number(limit, "weeklyUsed", "used"), .nan),
                                Self.week, epochSeconds(first(limit, "weeklyResetsAt")), now)
        if weekly == nil, plan.isEmpty { return usage }
        let out = Builder(now: now)
        let numeric = !plan.isEmpty && plan.allSatisfy(\.isNumber)
        out.plan = plan.isEmpty || numeric ? usage.planType : plan
        out.shortWindow = usage.fiveHour
        out.weekly = weekly ?? usage.weekly
        out.monthly = usage.monthly
        out.extras.append(contentsOf: usage.additionalLimits)
        out.credits = usage.usageCredits
        return out.build()
    }

    // MARK: Synthetic

    /// `api.synthetic.new/v2/quotas` (documented at dev.synthetic.new/docs/synthetic/quotas).
    /// Current plans meter weekly credits in `planWeeklyLimit`, whose `percentRemaining` is in
    /// percentage points and regenerates continuously, so `nextRegenAt` is when the next slice
    /// comes back. Older plans report `rollingFiveHourLimit` and `weeklyTokenLimit` as
    /// fractions, and the retired `subscription` request counter is read only when nothing
    /// else is there. The search tool has its own hourly allowance. Which unit a percentage is
    /// in follows from the object it sits in, never from its size: a fraction of 1.2 is over the
    /// limit, and 0.5 points is half a percent.
    public static func synthetic(_ body: String, now: Date) -> UsageSnapshot {
        var root = object(body)
        if root.isEmpty, case let .array(values)? = ProviderJSON.parse(body.trimmingCharacters(in: .whitespacesAndNewlines)) {
            root["quotas"] = .array(values)
        }
        let data = root.object("data")
        let source = data != nil && !root.has("planWeeklyLimit") && !root.has("rollingFiveHourLimit")
            && !root.has("weeklyTokenLimit") ? data! : root
        let out = Builder(now: now)
        out.plan = titleCase(string(source, "plan", "planName", "plan_name", "tier"))
        if out.plan.isEmpty { out.plan = titleCase(string(root, "plan", "planName", "tier")) }
        if let planWeekly = source.object("planWeeklyLimit") {
            out.weekly = makeWindow(quotaUsed(planWeekly, points: true), Self.week, quotaReset(planWeekly, now), now)
        } else {
            if let rolling = source.object("rollingFiveHourLimit") {
                out.shortWindow = makeWindow(quotaUsed(rolling, points: false), 5 * Self.hour, quotaReset(rolling, now), now)
            }
            if let weekly = source.object("weeklyTokenLimit") {
                out.weekly = makeWindow(quotaUsed(weekly, points: false), Self.week, quotaReset(weekly, now), now)
            }
        }
        if let search = path(source, "search", "hourly") {
            out.extra("Search (hourly)", makeWindow(quotaUsed(search, points: true), Self.hour, quotaReset(search, now), now))
        }
        if out.shortWindow == nil, out.weekly == nil, let subscription = source.object("subscription") {
            out.shortWindow = makeWindow(quotaUsed(subscription, points: true), 5 * Self.hour,
                                         quotaReset(subscription, now), now)
        }
        return out.build()
    }

    static let quotaLimitKeys = ["limit", "max", "total", "quota", "cap", "requestLimit", "messageLimit", "maxRequests"]
    static let quotaUsedKeys = ["used", "usage", "requests", "requestCount", "consumed", "spent", "messagesUsed"]
    static let quotaLeftKeys = ["remaining", "left", "available"]
    static let quotaLeftPercentKeys = ["percentRemaining", "remainingPercent", "remaining_percent", "percent_remaining"]
    static let quotaResetKeys = ["nextRegenAt", "next_regen_at", "renewsAt", "renews_at", "periodEnd", "period_end"]

    static func quotaReset(_ quota: Object, _ now: Date) -> Int64 {
        let at = epochSeconds(first(quota, quotaResetKeys))
        return at > 0 ? at : resetFrom(quota, now)
    }

    /// Percent used of one quota object. Percent fields win over counts; `points` says the
    /// percentages are 0–100, otherwise they are fractions of 1 (over 1 when over the limit).
    static func quotaUsed(_ quota: Object, points: Bool) -> Double {
        var used = number(quota, "percentUsed", "usedPercent", "usagePercent", "used_percent")
        let left = number(quota, quotaLeftPercentKeys)
        if used.isNaN, !left.isNaN {
            used = 100 - (points ? left : left * 100)
        } else if !used.isNaN, !points {
            used *= 100
        }
        if !used.isNaN { return used }
        var limit = number(quota, quotaLimitKeys)
        let count = number(quota, quotaUsedKeys)
        let remaining = number(quota, quotaLeftKeys)
        if limit.isNaN, !count.isNaN, !remaining.isNaN { limit = count + remaining }
        return percentUsed(limit, count, remaining)
    }

    // MARK: Chutes

    /// `api.chutes.ai/users/me/subscription_usage`, from Chutes' own API source
    /// (`api/user/router.py`): a rolling 4-hour cap and a monthly cap, both in dollars of
    /// pay-as-you-go value, or `{"subscription": false}` without a plan. Custom plans only
    /// enforce the 4-hour cap and report `monthly.uncapped`.
    public static func chutes(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        guard root.bool("subscription", default: false) else { return out.build() }
        let price = number(root, "monthly_price")
        if root.bool("custom", default: false) {
            out.plan = "Custom"
        } else if !price.isNaN, price > 0 {
            out.plan = money(price).replacingOccurrences(of: ".00", with: "") + "/month"
        }
        if let fourHour = root.object("four_hour") {
            out.shortWindow = makeWindow(percentUsed(number(fourHour, "cap"), number(fourHour, "usage"), number(fourHour, "remaining")),
                                         4 * Self.hour, epochSeconds(fourHour["reset_at"]), now)
        }
        if let monthly = root.object("monthly"), !monthly.bool("uncapped", default: false) {
            out.monthly = makeWindow(percentUsed(number(monthly, "cap"), number(monthly, "usage"), number(monthly, "remaining")),
                                     Self.month, epochSeconds(monthly["reset_at"]), now)
        }
        return out.build()
    }

    // MARK: v0

    /// The v0 Platform API (`v0.app/docs/api/v1`): `/v1/user/billing` gives the credit balance
    /// for the billing cycle (token billing) or a legacy allowance, `/v1/user/plan` names the
    /// plan, and `/v1/rate-limits` gives the request limit plus, for free accounts, the daily
    /// message limit.
    public static func v0(billing billingBody: String, plan planBody: String, rateLimits rateBody: String, now: Date) -> UsageSnapshot {
        let billing = object(billingBody)
        let plan = object(planBody)
        let rate = object(rateBody)
        let out = Builder(now: now)
        out.plan = titleCase(string(plan, "plan"))
        func cycleWindow(_ balance: Object?, _ cycle: Object?) -> UsageWindow? {
            makeWindow(percentUsed(number(balance, "total"), .nan, number(balance, "remaining")),
                       cycle.map { span($0["start"], $0["end"], Self.month) } ?? Self.month,
                       epochSeconds(cycle?["end"]), now)
        }
        var credits: UsageWindow?
        if let data = billing.object("data"), let balance = data.object("balance") {
            credits = cycleWindow(balance, data.object("billingCycle"))
            let onDemand = number(data.object("onDemand"), "balance")
            if !onDemand.isNaN, onDemand > 0 { out.credits = self.balance(onDemand) }
        } else if let data = billing.object("data") {
            credits = makeWindow(percentUsed(number(data, "limit"), .nan, number(data, "remaining")),
                                 Self.month, epochSeconds(data["reset"]), now)
        }
        if credits == nil {
            credits = cycleWindow(plan.object("balance"), plan.object("billingCycle"))
        }
        if let credits {
            if credits.windowSeconds >= 27 * Self.day, credits.windowSeconds <= 32 * Self.day {
                out.monthly = credits
            } else {
                out.extra("Credits", credits)
            }
        }
        if let daily = rate.object("dailyLimit"), !daily.bool("isWithinGracePeriod", default: false) {
            out.shortWindow = makeWindow(percentUsed(number(daily, "limit"), .nan, number(daily, "remaining")),
                                         Self.day, epochSeconds(daily["reset"]), now)
        }
        out.extra("API requests", makeWindow(percentUsed(number(rate, "limit"), .nan, number(rate, "remaining")),
                                             Self.day, epochSeconds(rate["reset"]), now))
        return out.build()
    }

    // MARK: Warp

    /// Warp's `GetRequestLimitInfo` GraphQL query: monthly credits (Warp still names them
    /// requests) and add-on credit grants for the user and their workspaces.
    public static func warp(_ body: String, now: Date) -> UsageSnapshot {
        let out = Builder(now: now)
        guard let user = path(object(body), "data", "user", "user") else { return out.build() }
        let info = user.object("requestLimitInfo")
        if let info, info.bool("isUnlimited", default: false) {
            out.plan = "Unlimited"
            out.credits = UsageCredits(hasCredits: true, unlimited: true, balance: nil)
        } else if let info {
            out.monthly = makeWindow(percentUsed(number(info, "requestLimit"), number(info, "requestsUsedSinceLastRefresh"), .nan),
                                     Self.month, epochSeconds(info["nextRefreshTime"]), now)
        }
        var grants = user.array("bonusGrants") ?? []
        for workspace in user.array("workspaces") ?? [] {
            grants += workspace.objectValue?.object("bonusGrantsInfo")?.array("grants") ?? []
        }
        var remaining = 0.0
        var anyGrant = false
        for grant in grants {
            let left = number(grant.objectValue, "requestCreditsRemaining")
            if !left.isNaN, left > 0 {
                remaining += left
                anyGrant = true
            }
        }
        if anyGrant, out.credits == nil {
            out.credits = UsageCredits(hasCredits: true, unlimited: false, balance: wholeCount(remaining) + " add-on credits")
        }
        return out.build()
    }

    /// The first GraphQL error message in a Warp reply, or "".
    public static func warpError(_ body: String) -> String {
        guard let first = object(body).array("errors")?.first?.objectValue else { return "" }
        return first.string("message")
    }

    // MARK: Devin

    /// `app.devin.ai/api/<org>/billing/quota/usage`: daily and weekly quota percentages used,
    /// their reset times, and the extra-usage balance.
    public static func devin(_ body: String, now: Date) -> UsageSnapshot {
        let root = object(body)
        let out = Builder(now: now)
        out.plan = titleCase(string(root, "plan_name", "planName", "plan", "tier"))
        if !root.bool("hide_daily_quota", default: false) {
            out.add("Daily quota", makeWindow(number(root, "daily_percentage", "dailyPercentage"), Self.day,
                                              epochSeconds(first(root, "daily_reset_at", "dailyResetAt")), now))
        }
        out.weekly = makeWindow(number(root, "weekly_percentage", "weeklyPercentage"), Self.week,
                                epochSeconds(first(root, "weekly_reset_at", "weeklyResetAt")), now)
        var overage = number(root, "overage_balance")
        if overage.isNaN {
            let cents = number(root, "overage_balance_cents")
            if !cents.isNaN { overage = cents / 100 }
        }
        if !overage.isNaN, overage > 0 { out.credits = balance(overage) }
        return out.build()
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
