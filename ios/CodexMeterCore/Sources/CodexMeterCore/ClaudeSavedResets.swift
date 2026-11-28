import Foundation

/// Claude Code's saved usage-limit resets ("cedar_ember" in the CLI), ported from Android's
/// `ClaudeSavedResets`. Claude Code reads them from `GET /api/oauth/usage?cedar_ember=1`, whose
/// body gains a `cedar_ember` block with `eligible`, `at_limit`, `grants` (each with `id`,
/// `label`, `resets_total`, `resets_left`, `ends_at`, `clears`, `paused`, `usable_now`,
/// `use_requires_limit`, `type` and `unavailable_reason`), `next_grant_id` and `cooldown_until`.
/// One is spent with `POST /api/organizations/{org}/reset_rate_limits`. A reset normally only
/// works at a usage limit; the server then answers `not_limited` and nothing is used. Anthropic
/// does not document any of this, so parsing is defensive and an absent or malformed block
/// simply means "no saved resets".
public struct ClaudeSavedResets: Codable, Sendable, Equatable {
    public static let program = "cedar_ember"
    /// The usage route with the saved-reset block, as Claude Code's `/usage` reads it.
    public static let usageURL = URL(string: "https://api.anthropic.com/api/oauth/usage?cedar_ember=1&skip_spend=1")!
    /// Anthropic only reports saved resets to a Claude Code client.
    public static let cliUserAgent = "claude-cli/2.1.316 (external, cli)"
    public static let cliApp = "cli"
    /// The scope that marks a Claude Code sign-in; without it no saved resets are reported.
    public static let sessionsScope = "user:sessions:claude_code"

    /// One saved-reset grant. A grant can hold several resets.
    public struct Grant: Codable, Sendable, Equatable {
        public let id: String
        public let label: String
        public let resetsTotal: Int
        public let resetsLeft: Int
        public let endsAt: Date?
        /// Limits this grant refills: "five_hour", "seven_day", ...
        public let clears: [String]
        public let paused: Bool
        public let usableNow: Bool
        public let useRequiresLimit: Bool
        /// "session", "weekly", "full", or empty.
        public let type: String
        public let unavailableReason: String
        /// The one model this reset covers ("Fable"), or empty when it covers every model.
        public let scope: String

        public init(id: String, label: String = "", resetsTotal: Int, resetsLeft: Int, endsAt: Date? = nil,
                    clears: [String] = [], paused: Bool = false, usableNow: Bool = false,
                    useRequiresLimit: Bool = true, type: String = "", unavailableReason: String = "", scope: String = "") {
            self.id = id
            self.label = label
            self.resetsTotal = max(0, resetsTotal)
            self.resetsLeft = max(0, resetsLeft)
            self.endsAt = endsAt
            self.clears = clears
            self.paused = paused
            self.usableNow = usableNow
            self.useRequiresLimit = useRequiresLimit
            self.type = type
            self.unavailableReason = unavailableReason
            self.scope = scope.trimmingCharacters(in: .whitespaces)
        }

        /// True for a reset that only covers one model's limit.
        public var modelOnly: Bool { !scope.isEmpty }

        public func hasResets(at now: Date) -> Bool {
            resetsLeft > 0 && !paused && (endsAt.map { $0 > now } ?? true)
        }

        public var clearsFiveHour: Bool {
            clears.contains("five_hour") || type == "session" || type == "full"
        }

        /// Whether it refills the all-models weekly limit; a model-only reset does not.
        public var clearsWeekly: Bool {
            clears.contains { $0 == "seven_day" || (!modelOnly && $0.hasPrefix("seven_day")) }
                || type == "weekly" || type == "full"
        }

        /// "5-hour limit", "weekly limit", "5-hour and weekly limits", or "Fable limit".
        public var clearsLabel: String {
            if modelOnly { return "\(scope) limit" }
            switch (clearsFiveHour, clearsWeekly) {
            case (true, true): return "5-hour and weekly limits"
            case (false, true): return "weekly limit"
            case (true, false): return "5-hour limit"
            default: return "usage limit"
            }
        }

        /// What using it does, in a sentence.
        public var refillsText: String {
            if modelOnly {
                return "Refills only your \(scope) limit. Your 5-hour and weekly limits stay as they are."
            }
            switch (clearsFiveHour, clearsWeekly) {
            case (true, true): return "Refills your 5-hour and weekly limits."
            case (false, true): return "Refills your weekly limit. Your 5-hour limit stays as it is."
            case (true, false): return "Refills your 5-hour limit. Your weekly limit stays as it is."
            default: return "Refills a usage limit you've hit."
            }
        }

        /// Why it can't be used right now, in words, or empty when the server says it can.
        public var heldText: String {
            if usableNow { return "" }
            switch unavailableReason {
            case "blocked_seven_day": return "Your weekly limit is blocking you, and this reset doesn't clear it"
            case "blocked_five_hour": return "Your 5-hour limit is blocking you, and this reset doesn't clear it"
            case "blocked_opus", "blocked_sonnet", "blocked_other": return "A model limit this reset doesn't clear is blocking you"
            case "cooldown": return "Available again after a short cooldown"
            default: return modelOnly ? "Works once you hit your \(scope) limit" : "Works once you hit a usage limit"
            }
        }

        /// The dashboard's name for it, matching claude.ai.
        public var title: String {
            if modelOnly { return "\(scope) reset" }
            switch type {
            case "full": return "Full reset"
            case "weekly": return "Weekly reset"
            case "session": return "Session reset"
            default: return label.isEmpty ? "Limit reset" : label
            }
        }

        func afterUse() -> Grant {
            Grant(id: id, label: label, resetsTotal: resetsTotal, resetsLeft: resetsLeft - 1, endsAt: endsAt,
                  clears: clears, paused: paused, usableNow: false, useRequiresLimit: useRequiresLimit,
                  type: type, unavailableReason: unavailableReason, scope: scope)
        }
    }

    public let eligible: Bool
    public let ineligibleReason: String
    public let atLimit: Bool
    public let grants: [Grant]
    public let nextGrantID: String
    public let cooldownUntil: Date?
    public let fetchedAt: Date

    public init(eligible: Bool, ineligibleReason: String = "", atLimit: Bool = false, grants: [Grant],
                nextGrantID: String = "", cooldownUntil: Date? = nil, fetchedAt: Date) {
        self.eligible = eligible
        self.ineligibleReason = ineligibleReason
        self.atLimit = atLimit
        self.grants = grants
        self.nextGrantID = nextGrantID
        self.cooldownUntil = cooldownUntil
        self.fetchedAt = fetchedAt
    }

    // MARK: Parsing

    private static let genericNames: Set<String> = [
        "", "limit", "limits", "full", "session", "weekly", "usage", "rate limit", "saved", "free", "bonus",
        "all", "all models", "oauth apps", "overage", "five hour", "seven day",
    ]

    /// Reads the `cedar_ember` block out of a usage response body; `nil` when it is missing or
    /// unreadable.
    public static func fromUsageBody(_ data: Data, fetchedAt: Date) -> ClaudeSavedResets? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return fromBlock(root["cedar_ember"] as? [String: Any], fetchedAt: fetchedAt)
    }

    public static func fromBlock(_ block: [String: Any]?, fetchedAt: Date) -> ClaudeSavedResets? {
        guard let block, block["eligible"] != nil else { return nil }
        var grants: [Grant] = []
        for case let row as [String: Any] in (block["grants"] as? [Any]) ?? [] {
            let id = string(row["id"])
            guard isGrantID(id), row["resets_left"] != nil else { continue }
            let clears = strings(row["clears"])
            let type = string(row["type"])
            let label = string(row["label"])
            grants.append(Grant(
                id: id, label: label,
                resetsTotal: int(row["resets_total"]), resetsLeft: int(row["resets_left"]),
                endsAt: ClaudeUsageParser.parseResetDate(row["ends_at"]), clears: clears,
                paused: bool(row["paused"], false), usableNow: bool(row["usable_now"], false),
                useRequiresLimit: bool(row["use_requires_limit"], true), type: type,
                unavailableReason: string(row["unavailable_reason"]),
                scope: scopeOf(row, type: type, label: label, clears: clears)
            ))
        }
        let next = string(block["next_grant_id"])
        return ClaudeSavedResets(
            eligible: bool(block["eligible"], false),
            ineligibleReason: string(block["ineligible_reason"]),
            atLimit: bool(block["at_limit"], false),
            grants: grants,
            nextGrantID: grants.contains { $0.id == next } ? next : "",
            cooldownUntil: ClaudeUsageParser.parseResetDate(block["cooldown_until"]),
            fetchedAt: fetchedAt
        )
    }

    /// The model a grant row is limited to ("Fable"), or empty when it covers every model.
    static func scopeOf(_ row: [String: Any], type: String, label: String, clears: [String]) -> String {
        var name = modelName(row["scope"])
        if name.isEmpty { name = modelName(row["model"]) }
        if name.isEmpty { name = named(string(row["model_display_name"])) }
        if name.isEmpty { name = scopeFromClears(clears) }
        if name.isEmpty, !type.isEmpty, !["session", "weekly", "full"].contains(type) {
            name = named(type
                .replacingOccurrences(of: "^(weekly_scoped_|seven_day_|model_|scoped_)", with: "", options: .regularExpression)
                .replacingOccurrences(of: "(_weekly|_seven_day|_reset|_only)$", with: "", options: .regularExpression))
        }
        if name.isEmpty {
            let trimmed = label.trimmingCharacters(in: .whitespaces)
            if let range = trimmed.range(of: #"^([\p{L}][\p{L}\p{N}.-]{1,23})(?:[ -]only)? reset$"#,
                                         options: [.regularExpression, .caseInsensitive]),
               range == trimmed.startIndex..<trimmed.endIndex {
                var head = trimmed.replacingOccurrences(of: #"(?i)(?:[ -]only)? reset$"#, with: "", options: .regularExpression)
                head = head.trimmingCharacters(in: .whitespaces)
                name = named(head)
            }
        }
        return name
    }

    private static func modelName(_ value: Any?) -> String {
        if let text = value as? String { return named(text) }
        guard let object = value as? [String: Any] else { return "" }
        if let inner = object["model"], !(inner is NSNull) {
            let name = modelName(inner)
            if !name.isEmpty { return name }
        }
        for key in ["display_name", "name", "id"] {
            let name = named(string(object[key]))
            if !name.isEmpty { return name }
        }
        return ""
    }

    /// The one model every entry of `clears` names, when none is an all-models limit.
    static func scopeFromClears(_ clears: [String]) -> String {
        var found = ""
        for limit in clears {
            let name = named(limit
                .replacingOccurrences(of: "^(weekly_scoped_|seven_day_|weekly_)", with: "", options: .regularExpression)
                .replacingOccurrences(of: "(_seven_day|_weekly)$", with: "", options: .regularExpression))
            if name.isEmpty || (!found.isEmpty && found != name) { return "" }
            found = name
        }
        return found
    }

    /// "fable_5" as "Fable 5"; empty for words that do not name a model.
    static func named(_ raw: String) -> String {
        let words = raw.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
            .split(separator: " ").joined(separator: " ")
        guard words.count <= 24, !genericNames.contains(words.lowercased()) else { return "" }
        return words.split(separator: " ").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    }

    static func isGrantID(_ id: String) -> Bool {
        id.range(of: "^[a-z0-9_-]{1,40}$", options: .regularExpression) != nil
    }

    private static func string(_ value: Any?) -> String {
        value as? String ?? ""
    }

    private static func strings(_ value: Any?) -> [String] {
        ((value as? [Any]) ?? []).compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty }
    }

    private static func int(_ value: Any?) -> Int {
        JSONSupport.double(value).map { Int($0) } ?? 0
    }

    private static func bool(_ value: Any?, _ fallback: Bool) -> Bool {
        JSONSupport.bool(value, default: fallback)
    }

    // MARK: Inventory

    /// Resets that can still be spent, across all grants.
    public func availableCount(at now: Date) -> Int {
        grants.filter { $0.hasResets(at: now) }.reduce(0) { $0 + $1.resetsLeft }
    }

    /// The same inventory as Codex reset credits, one entry per remaining reset, so the shared
    /// reset-credit views and the Priority tab render Claude resets unchanged.
    public func resetCreditsSnapshot(at now: Date) -> ResetCreditsSnapshot {
        var credits: [RateLimitResetCredit] = []
        for grant in grants where grant.hasResets(at: now) {
            for index in 0..<grant.resetsLeft {
                credits.append(RateLimitResetCredit(
                    id: grant.resetsLeft == 1 ? grant.id : "\(grant.id)#\(index + 1)",
                    resetType: grant.type, status: RateLimitResetCredit.availableStatus,
                    expiresAt: grant.endsAt, title: grant.title, description: grant.clearsLabel
                ))
            }
        }
        return ResetCreditsSnapshot(availableCount: credits.count, credits: credits, fetchedAt: fetchedAt)
    }

    /// "3 Fable resets", "1 Full reset", or "2 Fable resets and 1 Full reset"; empty when none.
    public func inventoryLabel(at now: Date) -> String {
        var order: [String] = []
        var counts: [String: Int] = [:]
        for grant in grants where grant.hasResets(at: now) {
            if counts[grant.title] == nil { order.append(grant.title) }
            counts[grant.title, default: 0] += grant.resetsLeft
        }
        let parts = order.compactMap { title -> String? in
            guard let count = counts[title], count > 0 else { return nil }
            return "\(count) \(title)\(count == 1 ? "" : "s")"
        }
        guard let last = parts.last else { return "" }
        return parts.count == 1 ? last : parts.dropLast().joined(separator: ", ") + " and " + last
    }

    /// "3 Fable resets available", or "No resets available".
    public func availableTitle(at now: Date) -> String {
        let inventory = inventoryLabel(at: now)
        return inventory.isEmpty ? "No resets available" : inventory + " available"
    }

    /// Earliest expiry among grants that still hold resets.
    public func nextExpiry(at now: Date) -> Date? {
        grants.filter { $0.hasResets(at: now) }.compactMap(\.endsAt).min()
    }

    private static func expiryOrder(_ grant: Grant) -> Date {
        grant.endsAt ?? .distantFuture
    }

    /// The grant the server wants spent next, else the one that expires first.
    public func nextGrant(at now: Date) -> Grant? {
        let live = grants.filter { $0.hasResets(at: now) }
        return live.first { $0.id == nextGrantID } ?? live.min { Self.expiryOrder($0) < Self.expiryOrder($1) }
    }

    private func isOpen(at now: Date) -> Bool {
        eligible && (cooldownUntil.map { $0 <= now } ?? true)
    }

    /// The grant a claim would spend right now, or `nil` when none can be used yet.
    public func usableGrant(at now: Date) -> Grant? {
        guard isOpen(at: now) else { return nil }
        if let next = nextGrant(at: now), next.usableNow { return next }
        return grants.filter { $0.hasResets(at: now) && $0.usableNow }
            .min { Self.expiryOrder($0) < Self.expiryOrder($1) }
    }

    /// A usable grant that refills the given window ("5-hour", "weekly"; "monthly" counts as the
    /// long window). The server's next grant wins, then the earliest to expire.
    public func usableGrant(for windowLabel: String, at now: Date) -> Grant? {
        guard isOpen(at: now) else { return nil }
        let fiveHour = windowLabel == "5-hour"
        let matching = grants.filter {
            $0.hasResets(at: now) && $0.usableNow && (fiveHour ? $0.clearsFiveHour : $0.clearsWeekly)
        }
        return matching.first { $0.id == nextGrantID } ?? matching.min { Self.expiryOrder($0) < Self.expiryOrder($1) }
    }

    /// Why the next reset can't be used right now, in words; empty when it can.
    public func unavailableMessage(at now: Date) -> String {
        if usableGrant(at: now) != nil { return "" }
        if let cooldownUntil, cooldownUntil > now { return "Available again after a short cooldown" }
        guard let next = nextGrant(at: now) else { return "Works once you hit a usage limit" }
        return next.heldText.isEmpty ? "Works once you hit a usage limit" : next.heldText
    }

    /// One kind of saved reset the person can pick: all grants of the same kind put together.
    public struct Choice: Sendable, Equatable, Identifiable {
        public let key: String
        public let title: String
        public let refills: String
        public let clearsLabel: String
        public let modelOnly: Bool
        public let count: Int
        public let nextExpiry: Date?
        /// The grant a claim spends.
        public let grantID: String
        public let usableNow: Bool
        /// Why it can't be used right now; empty when it can.
        public let held: String

        public var id: String { key }

        /// "Fable reset" for one, "3 Full resets" for more.
        public var countedTitle: String {
            count == 1 ? title : "\(count) \(title)s"
        }
    }

    private static func choiceKey(_ grant: Grant) -> String {
        "\(grant.title)|\(grant.scope)|\(grant.clearsFiveHour)|\(grant.clearsWeekly)"
    }

    /// The kinds of reset that can still be spent: the one the server would spend next first,
    /// then by soonest expiry.
    public func choices(at now: Date) -> [Choice] {
        let live = grants.filter { $0.hasResets(at: now) }.sorted { Self.expiryOrder($0) < Self.expiryOrder($1) }
        var order: [String] = []
        var groups: [String: [Grant]] = [:]
        if let next = nextGrant(at: now) { order.append(Self.choiceKey(next)) }
        for grant in live {
            let key = Self.choiceKey(grant)
            if !order.contains(key) { order.append(key) }
            groups[key, default: []].append(grant)
        }
        let open = isOpen(at: now)
        return order.compactMap { key in
            guard let group = groups[key], let first = group.first else { return nil }
            var spend: Grant?
            for grant in group where grant.usableNow && (spend == nil || grant.id == nextGrantID) {
                spend = grant
            }
            let usable = open && spend != nil
            let chosen = spend ?? first
            let held: String
            if usable {
                held = ""
            } else if !eligible {
                held = "Anthropic isn't offering resets on this account right now"
            } else if let cooldownUntil, cooldownUntil > now {
                held = "Available again after a short cooldown"
            } else {
                held = chosen.heldText
            }
            return Choice(key: key, title: chosen.title, refills: chosen.refillsText, clearsLabel: chosen.clearsLabel,
                          modelOnly: chosen.modelOnly, count: group.reduce(0) { $0 + $1.resetsLeft },
                          nextExpiry: group.compactMap(\.endsAt).min(), grantID: chosen.id, usableNow: usable, held: held)
        }
    }

    /// A copy with one reset taken off `grantID`, so the card updates straight away.
    public func afterUse(_ grantID: String) -> ClaudeSavedResets {
        ClaudeSavedResets(eligible: eligible, ineligibleReason: ineligibleReason, atLimit: false,
                          grants: grants.map { $0.id == grantID ? $0.afterUse() : $0 },
                          nextGrantID: "", cooldownUntil: cooldownUntil, fetchedAt: fetchedAt)
    }

    // MARK: Claiming

    /// Request body for `POST /api/organizations/{org}/reset_rate_limits`; `nil` for a malformed id.
    public static func claimBody(grantID: String, requestID: String) -> Data? {
        guard isGrantID(grantID), requestID.range(of: "^[A-Za-z0-9_-]{1,64}$", options: .regularExpression) != nil else {
            return nil
        }
        return try? JSONSerialization.data(withJSONObject: [
            "program": program, "grant_id": grantID, "request_id": requestID,
        ], options: [.sortedKeys])
    }

    /// `https://api.anthropic.com/api/organizations/{org}/reset_rate_limits`, or `nil` for a
    /// malformed organization.
    public static func resetURL(organization: String) -> URL? {
        let org = organization.trimmingCharacters(in: .whitespaces)
        guard org.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil else { return nil }
        return URL(string: "https://api.anthropic.com/api/organizations/\(org)/reset_rate_limits")
    }

    /// True when the granted scopes are known and lack the Claude Code sign-in scope.
    public static func needsClaudeCodeSignIn(grantedScope: String) -> Bool {
        let scopes = grantedScope.split(whereSeparator: \.isWhitespace).map(String.init)
        return !scopes.isEmpty && !scopes.contains(sessionsScope)
    }
}

/// Outcome of spending a saved reset. `result` mirrors the server's word, plus local failures.
public struct ClaudeResetClaimResult: Sendable, Equatable {
    public enum Result: String, Sendable {
        case reset
        case alreadyUsed = "already_used"
        case notLimited = "not_limited"
        case cooldown
        case ineligible
        case unavailable
        case rateLimited = "rate_limited"
        case authError = "auth_error"
        case error
    }

    public let result: Result
    public let reason: String
    public let resetsLeft: Int?
    public let cleared: [String]

    public init(result: Result, reason: String = "", resetsLeft: Int? = nil, cleared: [String] = []) {
        self.result = result
        self.reason = reason
        self.resetsLeft = resetsLeft
        self.cleared = cleared
    }

    public static func parse(_ data: Data) -> ClaudeResetClaimResult {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return ClaudeResetClaimResult(result: .error, reason: "unreadable_response")
        }
        let word = json["result"] as? String ?? ""
        let known: [Result] = [.reset, .alreadyUsed, .notLimited, .cooldown, .ineligible, .unavailable]
        let result = Result(rawValue: word).flatMap { known.contains($0) ? $0 : nil } ?? .unavailable
        let left = JSONSupport.double(json["resets_left"]).map { Int($0) }
        let cleared = ((json["cleared"] as? [Any]) ?? []).compactMap { $0 as? String }
        return ClaudeResetClaimResult(result: result, reason: json["reason"] as? String ?? "",
                                      resetsLeft: left, cleared: cleared)
    }

    public var applied: Bool { result == .reset }

    /// Network or server trouble worth retrying later.
    public var retryable: Bool { result == .error || result == .rateLimited }

    public var userMessage: String {
        switch result {
        case .reset:
            let windows = cleared.count
            return windows > 0 ? "Reset applied to \(windows) usage window\(windows == 1 ? "." : "s.")"
                : "Claude Code usage reset applied."
        case .notLimited: return "There is no used Claude Code allowance to reset right now."
        case .alreadyUsed: return "That reset request was already redeemed."
        case .ineligible, .unavailable: return "No reset credit is currently available."
        case .cooldown: return "Resets are cooling down. Try again in a few minutes."
        case .rateLimited: return "Claude is rate-limiting requests. Try again in a few minutes."
        case .authError: return "Claude rejected the saved sign-in. Sign in with Claude again."
        case .error: return "The reset could not be applied."
        }
    }
}

/// Should a saved Claude Code reset be spent automatically right now? Same rules, lead times
/// and thresholds as the Codex reset credits (Android `ClaudeResetAutoUse`): before it expires,
/// and, opt-in, when a window's remaining allowance reaches the threshold, once per window.
public struct ClaudeResetAutoUse: Sendable, Equatable {
    public enum Outcome: String, Sendable {
        case fireExpiring = "fire_expiring"
        case fireLowUsage = "fire_low_usage"
        case wait
        case skipAlreadyTried = "skip_already_tried"
        case skipNoMatchingReset = "skip_no_matching_reset"
        /// Running low, but that limit refills on its own within the chosen wait.
        case skipRefillsSoon = "skip_refills_soon"
    }

    /// Only a status this fresh may spend a reset.
    public static let maxStatusAge: TimeInterval = 2 * 60

    public let outcome: Outcome
    public let grant: ClaudeSavedResets.Grant?
    /// Remembered after an attempt so the same grant or window is tried once.
    public let attemptKey: String
    public let windowLabel: String
    public let remainingPercent: Int
    /// For `.skipRefillsSoon`: when that limit refills on its own.
    public let resetAt: Date?

    public var fires: Bool { outcome == .fireExpiring || outcome == .fireLowUsage }

    public init(outcome: Outcome, grant: ClaudeSavedResets.Grant?, attemptKey: String, windowLabel: String,
                remainingPercent: Int, resetAt: Date? = nil) {
        self.outcome = outcome
        self.grant = grant
        self.attemptKey = attemptKey
        self.windowLabel = windowLabel
        self.remainingPercent = remainingPercent
        self.resetAt = resetAt
    }

    public static func evaluate(
        status: ClaudeSavedResets?,
        usage: UsageSnapshot?,
        expiryEnabled: Bool,
        leadMinutes: Int,
        lowUsageEnabled: Bool,
        threshold: Int,
        metric: LowUsageAutoUseMetric = .both,
        lastExpiryKey: String?,
        lastLowUsageToken: String?,
        fiveHourWaitMinutes: Int = LowUsageAutoUse.defaultWaitMinutes,
        weeklyWaitMinutes: Int = LowUsageAutoUse.defaultWaitMinutes,
        now: Date
    ) -> ClaudeResetAutoUse {
        let waiting = ClaudeResetAutoUse(outcome: .wait, grant: nil, attemptKey: "", windowLabel: "", remainingPercent: -1)
        guard expiryEnabled || lowUsageEnabled, let status,
              now.timeIntervalSince(status.fetchedAt) <= maxStatusAge else { return waiting }
        var skipped: ClaudeResetAutoUse?
        if expiryEnabled {
            let lead = CreditExpiryAutoApply.leadInterval(leadMinutes)
            let expiring = status.grants
                .filter { grant in
                    guard grant.hasResets(at: now), grant.usableNow, let ends = grant.endsAt else { return false }
                    return ends.timeIntervalSince(now) <= lead
                }
                .min { ($0.endsAt ?? .distantFuture) < ($1.endsAt ?? .distantFuture) }
            if let expiring {
                let key = "expiry:\(expiring.id):\(expiring.resetsLeft)"
                if key != lastExpiryKey {
                    return ClaudeResetAutoUse(outcome: .fireExpiring, grant: expiring, attemptKey: key,
                                              windowLabel: "", remainingPercent: -1)
                }
                skipped = ClaudeResetAutoUse(outcome: .skipAlreadyTried, grant: expiring, attemptKey: key,
                                             windowLabel: "", remainingPercent: -1)
            }
        }
        if lowUsageEnabled, let usage {
            let low = LowUsageAutoUse.evaluate(snapshot: usage, metric: metric, threshold: threshold,
                                               availableCredits: status.availableCount(at: now),
                                               lastFiredToken: lastLowUsageToken,
                                               fiveHourWaitMinutes: fiveHourWaitMinutes,
                                               weeklyWaitMinutes: weeklyWaitMinutes, now: now)
            if low.fires {
                guard let grant = status.usableGrant(for: low.windowLabel, at: now) else {
                    return ClaudeResetAutoUse(outcome: .skipNoMatchingReset, grant: nil, attemptKey: low.windowToken,
                                              windowLabel: low.windowLabel, remainingPercent: low.remainingPercent)
                }
                return ClaudeResetAutoUse(outcome: .fireLowUsage, grant: grant, attemptKey: low.windowToken,
                                          windowLabel: low.windowLabel, remainingPercent: low.remainingPercent)
            }
            if low.outcome == .skipAlreadyUsed {
                return ClaudeResetAutoUse(outcome: .skipAlreadyTried, grant: nil, attemptKey: low.windowToken,
                                          windowLabel: low.windowLabel, remainingPercent: low.remainingPercent)
            }
            if low.outcome == .skipResetImminent, skipped == nil {
                return ClaudeResetAutoUse(outcome: .skipRefillsSoon, grant: nil, attemptKey: low.windowToken,
                                          windowLabel: low.windowLabel, remainingPercent: low.remainingPercent,
                                          resetAt: low.resetAt)
            }
        }
        return skipped ?? waiting
    }
}
