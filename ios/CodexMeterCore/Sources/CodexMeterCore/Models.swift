import Foundation

public struct UsageWindow: Codable, Sendable, Equatable {
    public let usedPercent: Int
    public let windowSeconds: Int64
    public let resetAfterSeconds: Int64
    public let resetAt: Date?

    public init(
        usedPercent: Int,
        windowSeconds: Int64,
        resetAfterSeconds: Int64 = 0,
        resetAt: Date? = nil
    ) {
        self.usedPercent = min(100, max(0, usedPercent))
        self.windowSeconds = max(0, windowSeconds)
        self.resetAfterSeconds = max(0, resetAfterSeconds)
        self.resetAt = resetAt.flatMap {
            let seconds = $0.timeIntervalSince1970
            return seconds.isFinite && seconds > 0 ? $0 : nil
        }
    }

    public var remainingPercent: Int {
        min(100, max(0, 100 - usedPercent))
    }

    public func effectiveResetDate(relativeTo referenceDate: Date) -> Date? {
        if let resetAt {
            return resetAt
        }
        guard resetAfterSeconds > 0 else {
            return nil
        }
        return referenceDate.addingTimeInterval(TimeInterval(resetAfterSeconds))
    }

    public func hasRemainingAllowance(atOrBelow threshold: Int) -> Bool {
        remainingPercent <= min(100, max(0, threshold))
    }

    private enum CodingKeys: String, CodingKey {
        case usedPercent
        case windowSeconds
        case resetAfterSeconds
        case resetAt
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            usedPercent: try container.decodeIfPresent(Int.self, forKey: .usedPercent) ?? 0,
            windowSeconds: try container.decodeIfPresent(Int64.self, forKey: .windowSeconds) ?? 0,
            resetAfterSeconds: try container.decodeIfPresent(Int64.self, forKey: .resetAfterSeconds) ?? 0,
            resetAt: try container.decodeIfPresent(Date.self, forKey: .resetAt)
        )
    }
}

public struct UsageLimit: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let meteredFeature: String
    public let allowed: Bool
    public let limitReached: Bool
    public let primary: UsageWindow?
    public let secondary: UsageWindow?

    public init(
        id: String,
        name: String = "",
        meteredFeature: String = "",
        allowed: Bool = true,
        limitReached: Bool = false,
        primary: UsageWindow?,
        secondary: UsageWindow?
    ) {
        self.id = id.trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.meteredFeature = meteredFeature.trimmingCharacters(in: .whitespacesAndNewlines)
        self.allowed = allowed
        self.limitReached = limitReached
        self.primary = primary
        self.secondary = secondary
    }

    public var displayName: String {
        if !name.isEmpty {
            return name
        }
        if !meteredFeature.isEmpty {
            return meteredFeature
                .replacingOccurrences(of: "_", with: " ")
                .replacingOccurrences(of: "-", with: " ")
                .capitalized
        }
        return "Additional usage"
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case name
        case meteredFeature
        case allowed
        case limitReached
        case primary
        case secondary
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: try container.decodeIfPresent(String.self, forKey: .id) ?? "",
            name: try container.decodeIfPresent(String.self, forKey: .name) ?? "",
            meteredFeature: try container.decodeIfPresent(String.self, forKey: .meteredFeature) ?? "",
            allowed: try container.decodeIfPresent(Bool.self, forKey: .allowed) ?? true,
            limitReached: try container.decodeIfPresent(Bool.self, forKey: .limitReached) ?? false,
            primary: try container.decodeIfPresent(UsageWindow.self, forKey: .primary),
            secondary: try container.decodeIfPresent(UsageWindow.self, forKey: .secondary)
        )
    }
}

public struct UsageCredits: Codable, Sendable, Equatable {
    public let hasCredits: Bool
    public let unlimited: Bool
    public let balance: String?

    public init(hasCredits: Bool, unlimited: Bool, balance: String?) {
        self.hasCredits = hasCredits
        self.unlimited = unlimited
        let cleanBalance = balance?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.balance = (hasCredits || unlimited) && cleanBalance?.isEmpty == false
            ? cleanBalance : nil
    }

    private enum CodingKeys: String, CodingKey {
        case hasCredits
        case unlimited
        case balance
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            hasCredits: try container.decodeIfPresent(Bool.self, forKey: .hasCredits) ?? false,
            unlimited: try container.decodeIfPresent(Bool.self, forKey: .unlimited) ?? false,
            balance: try container.decodeIfPresent(String.self, forKey: .balance)
        )
    }
}

public struct UsageSnapshot: Codable, Sendable, Equatable {
    public let planType: String
    public let allowed: Bool
    public let limitReached: Bool
    public let fiveHour: UsageWindow?
    public let weekly: UsageWindow?
    public let resetCreditsAvailable: Int?
    public let additionalLimits: [UsageLimit]
    public let usageCredits: UsageCredits?
    public let fetchedAt: Date

    public init(
        planType: String,
        allowed: Bool,
        limitReached: Bool,
        fiveHour: UsageWindow?,
        weekly: UsageWindow?,
        resetCreditsAvailable: Int? = nil,
        additionalLimits: [UsageLimit] = [],
        usageCredits: UsageCredits? = nil,
        fetchedAt: Date
    ) {
        self.planType = planType
        self.allowed = allowed
        self.limitReached = limitReached
        self.fiveHour = fiveHour
        self.weekly = weekly
        self.resetCreditsAvailable = resetCreditsAvailable.map { max(0, $0) }
        self.additionalLimits = additionalLimits.filter {
            $0.primary != nil || $0.secondary != nil
        }
        self.usageCredits = usageCredits
        self.fetchedAt = fetchedAt
    }

    public func nextReset(after date: Date) -> Date? {
        ([fiveHour, weekly] + additionalLimits.flatMap { [$0.primary, $0.secondary] })
            .compactMap { $0?.effectiveResetDate(relativeTo: fetchedAt) }
            .filter { $0 > date }
            .min()
    }

    public var hasDisplayableData: Bool {
        fiveHour != nil || weekly != nil || !additionalLimits.isEmpty
            || usageCredits?.hasCredits == true || usageCredits?.unlimited == true
            || usageCredits?.balance != nil || resetCreditsAvailable != nil
    }

    public func isStale(at date: Date = Date(), maxAge: TimeInterval) -> Bool {
        date.timeIntervalSince(fetchedAt) > max(0, maxAge)
    }

    private enum CodingKeys: String, CodingKey {
        case planType
        case allowed
        case limitReached
        case fiveHour
        case weekly
        case resetCreditsAvailable
        case additionalLimits
        case usageCredits
        case fetchedAt
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            planType: try container.decodeIfPresent(String.self, forKey: .planType) ?? "",
            allowed: try container.decodeIfPresent(Bool.self, forKey: .allowed) ?? true,
            limitReached: try container.decodeIfPresent(Bool.self, forKey: .limitReached) ?? false,
            fiveHour: try container.decodeIfPresent(UsageWindow.self, forKey: .fiveHour),
            weekly: try container.decodeIfPresent(UsageWindow.self, forKey: .weekly),
            resetCreditsAvailable: try container.decodeIfPresent(Int.self, forKey: .resetCreditsAvailable),
            additionalLimits: try container.decodeIfPresent([UsageLimit].self, forKey: .additionalLimits) ?? [],
            usageCredits: try container.decodeIfPresent(UsageCredits.self, forKey: .usageCredits),
            fetchedAt: try container.decodeIfPresent(Date.self, forKey: .fetchedAt)
                ?? Date(timeIntervalSince1970: 0)
        )
    }
}

public struct RateLimitResetCredit: Codable, Sendable, Equatable, Identifiable {
    public static let availableStatus = "available"
    public static let redeemedStatus = "redeemed"
    public static let redeemingStatus = "redeeming"

    public let id: String
    public let resetType: String
    public let status: String
    public let grantedAt: Date?
    public let expiresAt: Date?
    public let title: String
    public let description: String

    public init(
        id: String,
        resetType: String,
        status: String,
        grantedAt: Date? = nil,
        expiresAt: Date? = nil,
        title: String = "",
        description: String = ""
    ) {
        self.id = id
        self.resetType = resetType
        self.status = status
        self.grantedAt = grantedAt.flatMap {
            let seconds = $0.timeIntervalSince1970
            return seconds.isFinite && seconds > 0 ? $0 : nil
        }
        self.expiresAt = expiresAt.flatMap {
            let seconds = $0.timeIntervalSince1970
            return seconds.isFinite && seconds > 0 ? $0 : nil
        }
        self.title = title
        self.description = description
    }

    public var isAvailable: Bool {
        status.caseInsensitiveCompare(Self.availableStatus) == .orderedSame
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case resetType
        case status
        case grantedAt
        case expiresAt
        case title
        case description
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: try container.decodeIfPresent(String.self, forKey: .id) ?? "",
            resetType: try container.decodeIfPresent(String.self, forKey: .resetType) ?? "",
            status: try container.decodeIfPresent(String.self, forKey: .status) ?? "",
            grantedAt: try container.decodeIfPresent(Date.self, forKey: .grantedAt),
            expiresAt: try container.decodeIfPresent(Date.self, forKey: .expiresAt),
            title: try container.decodeIfPresent(String.self, forKey: .title) ?? "",
            description: try container.decodeIfPresent(String.self, forKey: .description) ?? ""
        )
    }
}

public struct ResetCreditsSnapshot: Codable, Sendable, Equatable {
    public let availableCount: Int
    public let credits: [RateLimitResetCredit]
    public let fetchedAt: Date

    public init(availableCount: Int, credits: [RateLimitResetCredit], fetchedAt: Date) {
        self.availableCount = max(0, availableCount)
        self.credits = credits
        self.fetchedAt = fetchedAt
    }

    public static func summary(availableCount: Int, fetchedAt: Date) -> Self {
        Self(availableCount: availableCount, credits: [], fetchedAt: fetchedAt)
    }

    public func nextExpiringAvailable(at date: Date) -> RateLimitResetCredit? {
        var best: RateLimitResetCredit?
        for credit in credits where credit.isAvailable {
            if let expiry = credit.expiresAt, expiry <= date {
                continue
            }

            guard let currentBest = best else {
                best = credit
                continue
            }

            if let expiry = credit.expiresAt,
               currentBest.expiresAt == nil || expiry < currentBest.expiresAt! {
                best = credit
            }
        }
        return best
    }

    public func preferredCreditID(at date: Date) -> String? {
        nextExpiringAvailable(at: date)?.id
    }

    public func nextExpiry(after date: Date) -> Date? {
        nextExpiringAvailable(at: date)?.expiresAt
    }

    private enum CodingKeys: String, CodingKey {
        case availableCount
        case credits
        case fetchedAt
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            availableCount: try container.decodeIfPresent(Int.self, forKey: .availableCount) ?? 0,
            credits: try container.decodeIfPresent([RateLimitResetCredit].self, forKey: .credits) ?? [],
            fetchedAt: try container.decodeIfPresent(Date.self, forKey: .fetchedAt)
                ?? Date(timeIntervalSince1970: 0)
        )
    }
}

public enum ResetConsumeOutcome: Sendable, Equatable, Hashable {
    case reset
    case nothingToReset
    case noCredit
    case alreadyRedeemed
    case unknown(String)

    public init(code: String) {
        switch code {
        case "reset": self = .reset
        case "nothing_to_reset": self = .nothingToReset
        case "no_credit": self = .noCredit
        case "already_redeemed": self = .alreadyRedeemed
        default: self = .unknown(code)
        }
    }

    public var code: String {
        switch self {
        case .reset: "reset"
        case .nothingToReset: "nothing_to_reset"
        case .noCredit: "no_credit"
        case .alreadyRedeemed: "already_redeemed"
        case let .unknown(code): code
        }
    }
}

extension ResetConsumeOutcome: Codable {
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        self.init(code: try container.decode(String.self))
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(code)
    }
}

public struct ResetConsumeResult: Codable, Sendable, Equatable {
    public let outcome: ResetConsumeOutcome
    public let windowsReset: Int
    public let refreshWarning: String?

    public init(
        outcome: ResetConsumeOutcome,
        windowsReset: Int,
        refreshWarning: String? = nil
    ) {
        self.outcome = outcome
        self.windowsReset = max(0, windowsReset)
        self.refreshWarning = refreshWarning?.isEmpty == true ? nil : refreshWarning
    }

    public init(code: String, windowsReset: Int, refreshWarning: String? = nil) {
        self.init(
            outcome: ResetConsumeOutcome(code: code),
            windowsReset: windowsReset,
            refreshWarning: refreshWarning
        )
    }

    public var applied: Bool {
        outcome == .reset
    }

    public var userMessage: String {
        switch outcome {
        case .reset:
            let message: String
            if windowsReset > 0 {
                message = "Reset applied to \(windowsReset) usage window\(windowsReset == 1 ? "." : "s.")"
            } else {
                message = "Codex usage reset applied."
            }
            if let refreshWarning {
                return "\(message) \(refreshWarning)"
            }
            return message
        case .nothingToReset:
            return "There is no used Codex allowance to reset right now."
        case .noCredit:
            return "No reset credit is currently available."
        case .alreadyRedeemed:
            return "That reset request was already redeemed."
        case .unknown:
            return "OpenAI returned an unrecognized reset result."
        }
    }

    private enum CodingKeys: String, CodingKey {
        case outcome
        case windowsReset
        case refreshWarning
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            outcome: try container.decodeIfPresent(ResetConsumeOutcome.self, forKey: .outcome)
                ?? .unknown(""),
            windowsReset: try container.decodeIfPresent(Int.self, forKey: .windowsReset) ?? 0,
            refreshWarning: try container.decodeIfPresent(String.self, forKey: .refreshWarning)
        )
    }
}

public enum WidgetSnapshotMode: String, Codable, Sendable, Equatable, CaseIterable {
    case signedOut
    case live
    case demo
}

public enum WidgetSnapshotFreshness: String, Codable, Sendable, Equatable, CaseIterable {
    case fresh
    case stale
    case unavailable

    public static func evaluate(
        fetchedAt: Date?,
        now: Date = Date(),
        staleAfter: TimeInterval
    ) -> Self {
        guard let fetchedAt else {
            return .unavailable
        }
        return now.timeIntervalSince(fetchedAt) > max(0, staleAfter) ? .stale : .fresh
    }
}

public struct SharedWidgetSnapshot: Codable, Sendable, Equatable {
    public static let currentVersion = 1
    public static let defaultFileName = "widget-snapshot.json"

    public let version: Int
    public let mode: WidgetSnapshotMode
    public let fetchedAt: Date?
    public let planType: String
    public let fiveHour: UsageWindow?
    public let weekly: UsageWindow?
    public let resetCreditsAvailable: Int?
    public let freshness: WidgetSnapshotFreshness

    public init(
        version: Int = Self.currentVersion,
        mode: WidgetSnapshotMode,
        fetchedAt: Date?,
        planType: String,
        fiveHour: UsageWindow?,
        weekly: UsageWindow?,
        resetCreditsAvailable: Int?,
        freshness: WidgetSnapshotFreshness
    ) {
        self.version = max(1, version)
        self.mode = mode
        self.fetchedAt = fetchedAt
        self.planType = planType
        self.fiveHour = fiveHour
        self.weekly = weekly
        self.resetCreditsAvailable = resetCreditsAvailable.map { max(0, $0) }
        self.freshness = freshness
    }

    public init(
        mode: WidgetSnapshotMode,
        usage: UsageSnapshot?,
        freshness: WidgetSnapshotFreshness
    ) {
        self.init(
            mode: mode,
            fetchedAt: usage?.fetchedAt,
            planType: usage?.planType ?? "",
            fiveHour: usage?.fiveHour,
            weekly: usage?.weekly,
            resetCreditsAvailable: usage?.resetCreditsAvailable,
            freshness: freshness
        )
    }

    public static let signedOut = SharedWidgetSnapshot(
        mode: .signedOut,
        fetchedAt: nil,
        planType: "",
        fiveHour: nil,
        weekly: nil,
        resetCreditsAvailable: nil,
        freshness: .unavailable
    )

    private enum CodingKeys: String, CodingKey {
        case version
        case mode
        case fetchedAt
        case planType
        case fiveHour
        case weekly
        case resetCreditsAvailable
        case freshness
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            version: try container.decodeIfPresent(Int.self, forKey: .version) ?? Self.currentVersion,
            mode: try container.decodeIfPresent(WidgetSnapshotMode.self, forKey: .mode) ?? .signedOut,
            fetchedAt: try container.decodeIfPresent(Date.self, forKey: .fetchedAt),
            planType: try container.decodeIfPresent(String.self, forKey: .planType) ?? "",
            fiveHour: try container.decodeIfPresent(UsageWindow.self, forKey: .fiveHour),
            weekly: try container.decodeIfPresent(UsageWindow.self, forKey: .weekly),
            resetCreditsAvailable: try container.decodeIfPresent(Int.self, forKey: .resetCreditsAvailable),
            freshness: try container.decodeIfPresent(WidgetSnapshotFreshness.self, forKey: .freshness) ?? .unavailable
        )
    }
}
