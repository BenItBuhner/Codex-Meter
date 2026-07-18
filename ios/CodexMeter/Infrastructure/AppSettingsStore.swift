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

nonisolated public struct AppSettings: Codable, Sendable, Equatable {
    public static let allowedRefreshMinutes = [5, 10, 15, 30, 60, 120]
    public static let allowedAlertThresholds = [100, 10, 25, 50, 75]
    /// Lead times before credit expiry, in minutes.
    public static let allowedCreditExpiryLeadMinutes = [60, 360, 720, 1_440, 2_880, 10_080]
    public static let defaultCreditExpiryLeadMinutes = [1_440]
    public static let defaults = AppSettings()

    public var appearance: AppAppearance
    public var refreshOnLaunch: Bool
    public var refreshMinutes: Int
    public var notificationsEnabled: Bool
    public var alertMetric: AlertMetric
    /// Remaining allowance percentage. `100` corresponds to the “Always” UI option.
    public var alertThreshold: Int
    public var creditIncreaseAlertsEnabled: Bool
    public var unexpectedRefillAlertsEnabled: Bool
    public var creditExpiryRemindersEnabled: Bool
    /// Minutes before expiry. Multiple values schedule one reminder each (2.2 parity).
    public var creditExpiryLeadMinutes: [Int]
    /// Auto-start Live Activity when remaining allowance hits the threshold.
    public var liveMonitorAutoStartEnabled: Bool
    public var liveMonitorAutoStartMetric: AlertMetric
    public var liveMonitorAutoStartThreshold: Int

    public init(
        appearance: AppAppearance = .system,
        refreshOnLaunch: Bool = true,
        refreshMinutes: Int = 30,
        notificationsEnabled: Bool = false,
        alertMetric: AlertMetric = .both,
        alertThreshold: Int = 25,
        creditIncreaseAlertsEnabled: Bool = true,
        unexpectedRefillAlertsEnabled: Bool = true,
        creditExpiryRemindersEnabled: Bool = true,
        creditExpiryLeadMinutes: [Int] = AppSettings.defaultCreditExpiryLeadMinutes,
        liveMonitorAutoStartEnabled: Bool = false,
        liveMonitorAutoStartMetric: AlertMetric = .both,
        liveMonitorAutoStartThreshold: Int = 25
    ) {
        self.appearance = appearance
        self.refreshOnLaunch = refreshOnLaunch
        self.refreshMinutes = Self.allowedRefreshMinutes.contains(refreshMinutes) ? refreshMinutes : 30
        self.notificationsEnabled = notificationsEnabled
        self.alertMetric = alertMetric
        self.alertThreshold = Self.allowedAlertThresholds.contains(alertThreshold) ? alertThreshold : 25
        self.creditIncreaseAlertsEnabled = creditIncreaseAlertsEnabled
        self.unexpectedRefillAlertsEnabled = unexpectedRefillAlertsEnabled
        self.creditExpiryRemindersEnabled = creditExpiryRemindersEnabled
        self.creditExpiryLeadMinutes = Self.sanitizedLeadMinutes(creditExpiryLeadMinutes)
        self.liveMonitorAutoStartEnabled = liveMonitorAutoStartEnabled
        self.liveMonitorAutoStartMetric = liveMonitorAutoStartMetric
        self.liveMonitorAutoStartThreshold = Self.allowedAlertThresholds.contains(liveMonitorAutoStartThreshold)
            ? liveMonitorAutoStartThreshold
            : 25
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
        case refreshMinutes
        case notificationsEnabled
        case alertMetric
        case alertThreshold
        case creditIncreaseAlertsEnabled
        case unexpectedRefillAlertsEnabled
        case creditExpiryRemindersEnabled
        case creditExpiryLeadMinutes
        case liveMonitorAutoStartEnabled
        case liveMonitorAutoStartMetric
        case liveMonitorAutoStartThreshold
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            appearance: try container.decodeIfPresent(AppAppearance.self, forKey: .appearance) ?? .system,
            refreshOnLaunch: try container.decodeIfPresent(Bool.self, forKey: .refreshOnLaunch) ?? true,
            refreshMinutes: try container.decodeIfPresent(Int.self, forKey: .refreshMinutes) ?? 30,
            notificationsEnabled: try container.decodeIfPresent(Bool.self, forKey: .notificationsEnabled) ?? false,
            alertMetric: try container.decodeIfPresent(AlertMetric.self, forKey: .alertMetric) ?? .both,
            alertThreshold: try container.decodeIfPresent(Int.self, forKey: .alertThreshold) ?? 25,
            creditIncreaseAlertsEnabled: try container.decodeIfPresent(Bool.self, forKey: .creditIncreaseAlertsEnabled) ?? true,
            unexpectedRefillAlertsEnabled: try container.decodeIfPresent(Bool.self, forKey: .unexpectedRefillAlertsEnabled) ?? true,
            creditExpiryRemindersEnabled: try container.decodeIfPresent(Bool.self, forKey: .creditExpiryRemindersEnabled) ?? true,
            creditExpiryLeadMinutes: try container.decodeIfPresent([Int].self, forKey: .creditExpiryLeadMinutes)
                ?? Self.defaultCreditExpiryLeadMinutes,
            liveMonitorAutoStartEnabled: try container.decodeIfPresent(Bool.self, forKey: .liveMonitorAutoStartEnabled) ?? false,
            liveMonitorAutoStartMetric: try container.decodeIfPresent(AlertMetric.self, forKey: .liveMonitorAutoStartMetric) ?? .both,
            liveMonitorAutoStartThreshold: try container.decodeIfPresent(Int.self, forKey: .liveMonitorAutoStartThreshold) ?? 25
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
