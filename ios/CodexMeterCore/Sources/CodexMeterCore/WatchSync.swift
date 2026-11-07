import Foundation

/// One meter as the Apple Watch shows it: a usage window plus labels that fit a complication.
///
/// Mirrors Android's `WatchMeters`: Codex reports a 5-hour and a weekly window, but other
/// providers report a daily window, only a monthly one, or named limits. Watch surfaces have
/// room for one or two meters, so they are ordered short window, weekly, monthly, then each
/// additional limit. For Codex that is exactly the 5-hour and weekly pair.
public struct WatchMeter: Codable, Sendable, Equatable, Identifiable {
    public enum Kind: String, Codable, Sendable {
        case short
        case weekly
        case monthly
        case limit
    }

    public let id: String
    public let kind: Kind
    /// "5h", "Day", "Week", "Month" or a clipped limit name.
    public let shortLabel: String
    /// "5-hour", "Daily", "Weekly", "Monthly" or the limit name.
    public let label: String
    public let window: UsageWindow

    public init(id: String, kind: Kind, shortLabel: String, label: String, window: UsageWindow) {
        self.id = id
        self.kind = kind
        self.shortLabel = shortLabel
        self.label = label
        self.window = window
    }

    public var remainingPercent: Int { window.remainingPercent }

    /// Whether the meter is calendar-scale (weekly, monthly, or a multi-day limit).
    public var isLongCadence: Bool {
        kind == .weekly || kind == .monthly || window.windowSeconds >= 2 * 86_400
    }

    static let maxLabelLength = 12

    /// Every meter in `snapshot`, in watch order. Windows are kept even when their reset has
    /// passed; `WatchProviderSummary.currentMeters(at:)` drops those at display time.
    public static func meters(for snapshot: UsageSnapshot?) -> [WatchMeter] {
        guard let snapshot else { return [] }
        var meters: [WatchMeter] = []
        if let window = snapshot.fiveHour {
            meters.append(WatchMeter(
                id: "short", kind: .short,
                shortLabel: shortLabel(forSeconds: window.windowSeconds),
                label: label(forSeconds: window.windowSeconds), window: window
            ))
        }
        if let window = snapshot.weekly {
            meters.append(WatchMeter(id: "weekly", kind: .weekly, shortLabel: "Week",
                                     label: "Weekly", window: window))
        }
        if let window = snapshot.monthly {
            meters.append(WatchMeter(id: "monthly", kind: .monthly, shortLabel: "Month",
                                     label: "Monthly", window: window))
        }
        for limit in snapshot.additionalLimits {
            guard let window = limit.primary ?? limit.secondary else { continue }
            let name = limit.displayName
            meters.append(WatchMeter(
                id: "limit:" + (limit.id.isEmpty ? name : limit.id), kind: .limit,
                shortLabel: clip(name), label: name, window: window
            ))
        }
        return meters
    }

    static func shortLabel(forSeconds seconds: Int64) -> String {
        guard seconds > 0 else { return "Now" }
        if seconds >= 20 * 3_600 { return "Day" }
        return "\(max(1, Int((Double(seconds) / 3_600).rounded())))h"
    }

    static func label(forSeconds seconds: Int64) -> String {
        guard seconds > 0 else { return "Current" }
        if seconds >= 20 * 3_600 { return "Daily" }
        return "\(max(1, Int((Double(seconds) / 3_600).rounded())))-hour"
    }

    static func clip(_ name: String) -> String {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return "Limit" }
        guard clean.count > maxLabelLength else { return clean }
        return String(clean.prefix(maxLabelLength - 1))
            .trimmingCharacters(in: .whitespaces) + "\u{2026}"
    }
}

/// One provider tab as the watch sees it. Tokens and account emails never leave the iPhone.
public struct WatchProviderSummary: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let signedIn: Bool
    /// Plan badge such as "Pro", or empty.
    public let plan: String
    public let fetchedAt: Date?
    public let meters: [WatchMeter]
    public let resetCreditsAvailable: Int?
    public let lastError: String

    public init(
        id: String,
        name: String,
        signedIn: Bool,
        plan: String = "",
        fetchedAt: Date? = nil,
        meters: [WatchMeter] = [],
        resetCreditsAvailable: Int? = nil,
        lastError: String = ""
    ) {
        self.id = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = cleanName.isEmpty ? self.id : cleanName
        self.signedIn = signedIn
        self.plan = signedIn ? plan : ""
        self.fetchedAt = signedIn ? fetchedAt : nil
        self.meters = signedIn ? meters : []
        self.resetCreditsAvailable = signedIn ? resetCreditsAvailable : nil
        self.lastError = String(lastError.prefix(160))
    }

    /// Builds a summary from a provider's cached usage on the iPhone.
    public init(
        id: String,
        name: String,
        signedIn: Bool,
        usage: UsageSnapshot?,
        lastError: String? = nil
    ) {
        self.init(
            id: id,
            name: name,
            signedIn: signedIn,
            plan: usage.map { WatchProviderSummary.planLabel($0.planType) } ?? "",
            fetchedAt: usage?.fetchedAt,
            meters: WatchMeter.meters(for: usage),
            resetCreditsAvailable: usage?.resetCreditsAvailable,
            lastError: lastError ?? ""
        )
    }

    /// Meters whose window has not reset since the iPhone fetched them.
    public func currentMeters(at date: Date) -> [WatchMeter] {
        meters.filter { meter in
            guard let reset = meter.window.effectiveResetDate(relativeTo: fetchedAt ?? date) else {
                return true
            }
            return reset > date
        }
    }

    public func primary(at date: Date) -> WatchMeter? { currentMeters(at: date).first }

    public func secondary(at date: Date) -> WatchMeter? {
        let meters = currentMeters(at: date)
        return meters.count > 1 ? meters[1] : nil
    }

    /// The meter that resets soonest, with its reset date.
    public func nextReset(after date: Date) -> (meter: WatchMeter, date: Date)? {
        currentMeters(at: date)
            .compactMap { meter in
                meter.window.effectiveResetDate(relativeTo: fetchedAt ?? date).map { (meter, $0) }
            }
            .filter { $0.1 > date }
            .min { $0.1 < $1.1 }
    }

    /// The tighter of the first two meters, 0–1, for a ranged complication.
    public func constrainedFraction(at date: Date) -> Double {
        let meters = currentMeters(at: date).prefix(2)
        guard let lowest = meters.map(\.remainingPercent).min() else { return 0 }
        return Double(lowest) / 100
    }

    /// "5h 62% · Week 41%" for inline complications and accessibility.
    public func pairText(at date: Date) -> String {
        let meters = currentMeters(at: date).prefix(2)
        guard !meters.isEmpty else { return signedIn ? "No usage yet" : "Sign in on iPhone" }
        return meters.map { "\($0.shortLabel) \($0.remainingPercent)%" }.joined(separator: " · ")
    }

    /// Whether the cached usage is older than `interval`.
    public func isStale(at date: Date, after interval: TimeInterval = 2 * 60 * 60) -> Bool {
        guard let fetchedAt else { return false }
        return date.timeIntervalSince(fetchedAt) > interval
    }

    static func planLabel(_ raw: String) -> String {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = clean.first else { return "" }
        return first.uppercased() + clean.dropFirst()
    }
}

/// Everything the iPhone sends the watch: every provider tab, in tab order, plus the tab
/// selected on the iPhone. Sent as the WatchConnectivity application context, so the watch
/// always holds the latest copy even when it was out of range.
public struct WatchSyncPayload: Codable, Sendable, Equatable {
    public static let currentVersion = 1
    /// Key in the WatchConnectivity application context.
    public static let contextKey = "codex-meter.watch-payload"
    /// Message the watch sends to ask the iPhone for a refresh.
    public static let refreshMessageKey = "codex-meter.refresh"
    /// Upper bound on providers sent, matching the iPhone's maximum tab count.
    public static let maxProviders = 24

    public let version: Int
    public let providers: [WatchProviderSummary]
    public let phoneSelectedID: String
    public let sentAt: Date

    public init(providers: [WatchProviderSummary], phoneSelectedID: String, sentAt: Date) {
        var seen = Set<String>()
        self.version = Self.currentVersion
        self.providers = Array(providers.filter { seen.insert($0.id).inserted }
            .prefix(Self.maxProviders))
        self.phoneSelectedID = phoneSelectedID.lowercased()
        self.sentAt = sentAt
    }

    public func provider(id: String?) -> WatchProviderSummary? {
        guard let id = id?.lowercased(), !id.isEmpty else { return nil }
        return providers.first { $0.id == id }
    }

    /// The provider the watch follows: its own choice while the iPhone still sends it, then
    /// the iPhone's selected tab, then the first signed-in provider, then the first one.
    public func resolve(watchChoice: String?) -> WatchProviderSummary? {
        provider(id: watchChoice)
            ?? provider(id: phoneSelectedID)
            ?? providers.first(where: \.signedIn)
            ?? providers.first
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> WatchSyncPayload {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(WatchSyncPayload.self, from: data)
    }
}

/// The watch's copy of the latest payload and its own provider choice, in an App Group so the
/// watch app and its complications read the same data.
public struct WatchPayloadStore: @unchecked Sendable {
    public static let appGroupIdentifier = "group.com.bukovinafilip.CodexMeter"
    private static let payloadKey = "codex-meter.watch-payload-v1"
    private static let choiceKey = "codex-meter.watch-provider-v1"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
            ?? UserDefaults(suiteName: Self.appGroupIdentifier)
            ?? .standard
    }

    public func load() -> WatchSyncPayload? {
        guard let data = defaults.data(forKey: Self.payloadKey) else { return nil }
        return try? WatchSyncPayload.decode(data)
    }

    /// Saves `payload` unless a newer one is already stored. Returns whether it was saved.
    @discardableResult
    public func save(_ payload: WatchSyncPayload) -> Bool {
        if let current = load(), current.sentAt > payload.sentAt { return false }
        guard let data = try? payload.encoded() else { return false }
        defaults.set(data, forKey: Self.payloadKey)
        return true
    }

    public var watchChoice: String? {
        get { defaults.string(forKey: Self.choiceKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.choiceKey) }
    }

    /// The provider the watch shows and its complications follow.
    public func followed() -> WatchProviderSummary? {
        load()?.resolve(watchChoice: watchChoice)
    }
}

extension WatchSyncPayload {
    /// Sample data for the watch's demo mode, previews and screenshots. Not real accounts.
    public static func demo(now: Date = Date()) -> WatchSyncPayload {
        func window(_ used: Int, _ seconds: Int64, resetIn minutes: Double) -> UsageWindow {
            UsageWindow(usedPercent: used, windowSeconds: seconds,
                        resetAt: now.addingTimeInterval(minutes * 60))
        }
        let codex = UsageSnapshot(
            planType: "pro", allowed: true, limitReached: false,
            fiveHour: window(38, 18_000, resetIn: 134),
            weekly: window(59, 604_800, resetIn: 3 * 1_440 + 260),
            resetCreditsAvailable: 2, fetchedAt: now.addingTimeInterval(-240)
        )
        let claude = UsageSnapshot(
            planType: "max", allowed: true, limitReached: false,
            fiveHour: window(71, 18_000, resetIn: 52),
            weekly: window(34, 604_800, resetIn: 5 * 1_440),
            additionalLimits: [UsageLimit(id: "opus", name: "Opus",
                                          primary: window(46, 604_800, resetIn: 5 * 1_440),
                                          secondary: nil)],
            fetchedAt: now.addingTimeInterval(-600)
        )
        let cursor = UsageSnapshot(
            planType: "pro", allowed: true, limitReached: false,
            fiveHour: nil, weekly: nil,
            monthly: window(27, 2_592_000, resetIn: 11 * 1_440),
            fetchedAt: now.addingTimeInterval(-900)
        )
        let copilot = UsageSnapshot(
            planType: "", allowed: true, limitReached: false, fiveHour: nil, weekly: nil,
            additionalLimits: [UsageLimit(id: "premium", name: "Premium requests",
                                          primary: window(64, 2_592_000, resetIn: 9 * 1_440),
                                          secondary: nil)],
            fetchedAt: now.addingTimeInterval(-1_200)
        )
        return WatchSyncPayload(
            providers: [
                WatchProviderSummary(id: "codex", name: "Codex", signedIn: true, usage: codex),
                WatchProviderSummary(id: "claude_code", name: "Claude Code", signedIn: true,
                                     usage: claude),
                WatchProviderSummary(id: "cursor", name: "Cursor", signedIn: true, usage: cursor),
                WatchProviderSummary(id: "copilot", name: "Copilot", signedIn: true,
                                     usage: copilot),
                WatchProviderSummary(id: "kimi", name: "Kimi Code", signedIn: false, usage: nil),
            ],
            phoneSelectedID: "codex",
            sentAt: now
        )
    }
}
