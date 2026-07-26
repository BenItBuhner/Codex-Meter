import Combine
import Foundation

nonisolated public enum AppAppearance: String, Codable, Sendable, CaseIterable, Identifiable {
    case system
    case light
    case dark

    public var id: String { rawValue }
}

nonisolated public enum AlertMetric: String, Codable, Sendable, CaseIterable, Identifiable {
    case both
    case fiveHour
    case weekly

    public var id: String { rawValue }

    /// Whether low-usage / reset alerts should run for `metric`.
    ///
    /// The picker chooses which windows to monitor (Both / 5-hour / Weekly),
    /// not which window to exclude.
    public func includes(_ metric: AlertMetric) -> Bool {
        switch self {
        case .both:
            return metric == .fiveHour || metric == .weekly
        case .fiveHour:
            return metric == .fiveHour
        case .weekly:
            return metric == .weekly
        }
    }
}

nonisolated public enum RefreshMode: String, Codable, Sendable, CaseIterable, Identifiable {
    case automatic
    case manual

    public var id: String { rawValue }
}

nonisolated public struct AppSettings: Codable, Sendable, Equatable {
    public static let allowedRefreshMinutes = [5, 10, 15, 30, 60, 120]
    public static let allowedAlertThresholds = [100, 10, 25, 50, 75]
    /// Lead times before credit expiry, in minutes.
    public static let allowedCreditExpiryLeadMinutes = [60, 360, 720, 1_440, 2_880, 10_080]
    public static let defaultCreditExpiryLeadMinutes = [1_440]
    public static let defaults = AppSettings()

    public var appearance: AppAppearance
    public var refreshOnLaunch: Bool
    public var refreshMode: RefreshMode
    public var refreshMinutes: Int
    public var showFiveHour: Bool
    public var showWeekly: Bool
    public var showAdditionalLimits: Bool
    public var showUsageCredits: Bool
    public var showResetCredits: Bool
    public var notificationsEnabled: Bool
    public var alertMetric: AlertMetric
    /// Remaining allowance percentage. `100` corresponds to the “Always” UI option.
    public var alertThreshold: Int
    public var creditIncreaseAlertsEnabled: Bool
    public var unexpectedRefillAlertsEnabled: Bool
    public var creditExpiryRemindersEnabled: Bool
    /// Minutes before expiry. Multiple values schedule one reminder each (2.2 parity).
    public var creditExpiryLeadMinutes: [Int]

    public init(
        appearance: AppAppearance = .system,
        refreshOnLaunch: Bool = true,
        refreshMode: RefreshMode = .automatic,
        refreshMinutes: Int = 30,
        showFiveHour: Bool = true,
        showWeekly: Bool = true,
        showAdditionalLimits: Bool = true,
        showUsageCredits: Bool = true,
        showResetCredits: Bool = true,
        notificationsEnabled: Bool = false,
        alertMetric: AlertMetric = .both,
        alertThreshold: Int = 25,
        creditIncreaseAlertsEnabled: Bool = true,
        unexpectedRefillAlertsEnabled: Bool = true,
        creditExpiryRemindersEnabled: Bool = true,
        creditExpiryLeadMinutes: [Int] = AppSettings.defaultCreditExpiryLeadMinutes
    ) {
        self.appearance = appearance
        self.refreshOnLaunch = refreshOnLaunch
        self.refreshMode = refreshMode
        self.refreshMinutes = Self.allowedRefreshMinutes.contains(refreshMinutes) ? refreshMinutes : 30
        self.showFiveHour = showFiveHour
        self.showWeekly = showWeekly
        self.showAdditionalLimits = showAdditionalLimits
        self.showUsageCredits = showUsageCredits
        self.showResetCredits = showResetCredits
        self.notificationsEnabled = notificationsEnabled
        self.alertMetric = alertMetric
        self.alertThreshold = Self.allowedAlertThresholds.contains(alertThreshold) ? alertThreshold : 25
        self.creditIncreaseAlertsEnabled = creditIncreaseAlertsEnabled
        self.unexpectedRefillAlertsEnabled = unexpectedRefillAlertsEnabled
        self.creditExpiryRemindersEnabled = creditExpiryRemindersEnabled
        self.creditExpiryLeadMinutes = Self.sanitizedLeadMinutes(creditExpiryLeadMinutes)
    }

    public var effectiveCreditExpiryLeadMinutes: [Int] {
        creditExpiryLeadMinutes.isEmpty ? Self.defaultCreditExpiryLeadMinutes : creditExpiryLeadMinutes
    }

    public mutating func toggleCreditExpiryLeadMinutes(_ minutes: Int) {
        guard Self.allowedCreditExpiryLeadMinutes.contains(minutes) else { return }
        var set = Set(creditExpiryLeadMinutes)
        if set.contains(minutes) {
            set.remove(minutes)
        } else {
            set.insert(minutes)
        }
        creditExpiryLeadMinutes = Self.sanitizedLeadMinutes(Array(set))
    }

    private static func sanitizedLeadMinutes(_ values: [Int]) -> [Int] {
        let filtered = Set(values).intersection(Set(allowedCreditExpiryLeadMinutes))
        return filtered.sorted()
    }

    private enum CodingKeys: String, CodingKey {
        case appearance
        case refreshOnLaunch
        case refreshMode
        case refreshMinutes
        case showFiveHour
        case showWeekly
        case showAdditionalLimits
        case showUsageCredits
        case showResetCredits
        case notificationsEnabled
        case alertMetric
        case alertThreshold
        case creditIncreaseAlertsEnabled
        case unexpectedRefillAlertsEnabled
        case creditExpiryRemindersEnabled
        case creditExpiryLeadMinutes
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            appearance: try container.decodeIfPresent(AppAppearance.self, forKey: .appearance) ?? .system,
            refreshOnLaunch: try container.decodeIfPresent(Bool.self, forKey: .refreshOnLaunch) ?? true,
            refreshMode: try container.decodeIfPresent(RefreshMode.self, forKey: .refreshMode) ?? .automatic,
            refreshMinutes: try container.decodeIfPresent(Int.self, forKey: .refreshMinutes) ?? 30,
            showFiveHour: try container.decodeIfPresent(Bool.self, forKey: .showFiveHour) ?? true,
            showWeekly: try container.decodeIfPresent(Bool.self, forKey: .showWeekly) ?? true,
            showAdditionalLimits: try container.decodeIfPresent(Bool.self, forKey: .showAdditionalLimits) ?? true,
            showUsageCredits: try container.decodeIfPresent(Bool.self, forKey: .showUsageCredits) ?? true,
            showResetCredits: try container.decodeIfPresent(Bool.self, forKey: .showResetCredits) ?? true,
            notificationsEnabled: try container.decodeIfPresent(Bool.self, forKey: .notificationsEnabled) ?? false,
            alertMetric: try container.decodeIfPresent(AlertMetric.self, forKey: .alertMetric) ?? .both,
            alertThreshold: try container.decodeIfPresent(Int.self, forKey: .alertThreshold) ?? 25,
            creditIncreaseAlertsEnabled: try container.decodeIfPresent(Bool.self, forKey: .creditIncreaseAlertsEnabled) ?? true,
            unexpectedRefillAlertsEnabled: try container.decodeIfPresent(Bool.self, forKey: .unexpectedRefillAlertsEnabled) ?? true,
            creditExpiryRemindersEnabled: try container.decodeIfPresent(Bool.self, forKey: .creditExpiryRemindersEnabled) ?? true,
            creditExpiryLeadMinutes: try container.decodeIfPresent([Int].self, forKey: .creditExpiryLeadMinutes)
                ?? Self.defaultCreditExpiryLeadMinutes
        )
    }
}

@MainActor
public final class AppSettingsStore: ObservableObject {
    public static let storageKey = "codex-meter.app-settings-v1"

    @Published public var settings: AppSettings {
        didSet { persist() }
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            self.settings = decoded
        } else {
            self.settings = .defaults
        }
    }

    public var appearance: AppAppearance {
        get { settings.appearance }
        set { settings.appearance = newValue }
    }

    public var refreshOnLaunch: Bool {
        get { settings.refreshOnLaunch }
        set { settings.refreshOnLaunch = newValue }
    }

    public var refreshMode: RefreshMode {
        get { settings.refreshMode }
        set { settings.refreshMode = newValue }
    }

    public var refreshMinutes: Int {
        get { settings.refreshMinutes }
        set {
            guard AppSettings.allowedRefreshMinutes.contains(newValue) else { return }
            settings.refreshMinutes = newValue
        }
    }

    public var notificationsEnabled: Bool {
        get { settings.notificationsEnabled }
        set { settings.notificationsEnabled = newValue }
    }

    public var alertMetric: AlertMetric {
        get { settings.alertMetric }
        set { settings.alertMetric = newValue }
    }

    public var alertThreshold: Int {
        get { settings.alertThreshold }
        set {
            guard AppSettings.allowedAlertThresholds.contains(newValue) else { return }
            settings.alertThreshold = newValue
        }
    }

    public func reset() {
        settings = .defaults
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: Self.storageKey)
    }
}

@MainActor
final class RefreshEngagementStore {
    private static let foregroundKey = "codex-meter.refresh-foreground-at-v1"
    private static let lastOpenKey = "codex-meter.refresh-last-open-at-v1"
    private static let scoreKey = "codex-meter.refresh-attention-score-v1"
    private static let scoreDateKey = "codex-meter.refresh-attention-score-date-v1"
    private static let failuresKey = "codex-meter.refresh-failures-v1"
    private static let halfLife: TimeInterval = 6 * 60 * 60
    private static let openDebounce: TimeInterval = 10 * 60
    private static let foregroundUnit: TimeInterval = 10 * 60

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func recordForeground(at date: Date = Date()) {
        var score = decayedScore(at: date)
        let lastOpen = defaults.double(forKey: Self.lastOpenKey)
        if lastOpen <= 0 || date.timeIntervalSince1970 - lastOpen >= Self.openDebounce {
            score += 1
            defaults.set(date.timeIntervalSince1970, forKey: Self.lastOpenKey)
        }
        save(score: score, at: date)
        defaults.set(date.timeIntervalSince1970, forKey: Self.foregroundKey)
    }

    func recordBackground(at date: Date = Date()) {
        var score = decayedScore(at: date)
        let foregroundAt = defaults.double(forKey: Self.foregroundKey)
        if foregroundAt > 0, date.timeIntervalSince1970 > foregroundAt {
            score += min(6, (date.timeIntervalSince1970 - foregroundAt) / Self.foregroundUnit)
        }
        save(score: score, at: date)
        defaults.removeObject(forKey: Self.foregroundKey)
    }

    func attentionScore(at date: Date = Date()) -> Double {
        var score = decayedScore(at: date)
        let foregroundAt = defaults.double(forKey: Self.foregroundKey)
        if foregroundAt > 0, date.timeIntervalSince1970 > foregroundAt {
            score += min(6, (date.timeIntervalSince1970 - foregroundAt) / Self.foregroundUnit)
        }
        return max(0, score)
    }

    var consecutiveFailures: Int {
        min(3, max(0, defaults.integer(forKey: Self.failuresKey)))
    }

    func recordRefreshSuccess() {
        defaults.removeObject(forKey: Self.failuresKey)
    }

    func recordRefreshFailure() {
        defaults.set(min(3, consecutiveFailures + 1), forKey: Self.failuresKey)
    }

    private func decayedScore(at date: Date) -> Double {
        let score = defaults.double(forKey: Self.scoreKey)
        let scoreDate = defaults.double(forKey: Self.scoreDateKey)
        guard score.isFinite, score > 0, scoreDate > 0 else { return 0 }
        let elapsed = max(0, date.timeIntervalSince1970 - scoreDate)
        return score * pow(0.5, elapsed / Self.halfLife)
    }

    private func save(score: Double, at date: Date) {
        defaults.set(max(0, score), forKey: Self.scoreKey)
        defaults.set(date.timeIntervalSince1970, forKey: Self.scoreDateKey)
    }
}
