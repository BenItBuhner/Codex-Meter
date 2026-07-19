import CodexMeterCore
import Foundation

/// Maps app preferences to the credential-free shared transfer contract.
enum SettingsTransfer {
    typealias Document = SettingsTransferDocument

    static func makeDocument(settings: AppSettings, exportedAt: Date = Date()) -> Document {
        Document(
            exportedAt: exportedAt,
            appearance: .init(mode: settings.appearance.rawValue),
            refresh: .init(
                refreshOnLaunch: settings.refreshOnLaunch,
                preferredMinutes: settings.refreshMinutes
            ),
            usageWarnings: .init(
                estimatesEnabled: settings.usagePaceEnabled,
                sensitivity: settings.usagePaceSensitivity
            ),
            notifications: .init(
                enabled: settings.notificationsEnabled,
                metric: settings.alertMetric.rawValue,
                threshold: settings.alertThreshold,
                creditIncrease: settings.creditIncreaseAlertsEnabled,
                unexpectedRefill: settings.unexpectedRefillAlertsEnabled,
                creditExpiry: settings.creditExpiryRemindersEnabled,
                creditExpiryLeadMinutes: settings.creditExpiryLeadMinutes
            ),
            liveActivity: .init(
                autoStartOnAcceleratedUsage: settings.liveMonitorAutoStartOnAcceleratedUsage
            )
        )
    }

    static func encode(_ document: Document) throws -> Data {
        try document.encoded()
    }

    static func parse(_ data: Data) throws -> Document {
        try Document.decodeValidated(data)
    }

    /// Returns a validated copy. Callers persist only after this method succeeds,
    /// so unsupported future versions never partially change current settings.
    static func apply(document: Document, to settings: AppSettings) -> AppSettings {
        var next = settings
        if let appearance = document.appearance,
           let mode = AppAppearance(rawValue: appearance.mode) {
            next.appearance = mode
        }
        if let refresh = document.refresh {
            next.refreshOnLaunch = refresh.refreshOnLaunch
            if AppSettings.allowedRefreshMinutes.contains(refresh.preferredMinutes) {
                next.refreshMinutes = refresh.preferredMinutes
            }
        }
        if let warnings = document.usageWarnings {
            next.usagePaceEnabled = warnings.estimatesEnabled
            next.usagePaceSensitivity = warnings.sensitivity
        }
        if let notifications = document.notifications {
            next.notificationsEnabled = notifications.enabled
            if let metric = AlertMetric(rawValue: notifications.metric) {
                next.alertMetric = metric
            }
            if AppSettings.allowedAlertThresholds.contains(notifications.threshold) {
                next.alertThreshold = notifications.threshold
            }
            next.creditIncreaseAlertsEnabled = notifications.creditIncrease
            next.unexpectedRefillAlertsEnabled = notifications.unexpectedRefill
            next.creditExpiryRemindersEnabled = notifications.creditExpiry
            let leads = notifications.creditExpiryLeadMinutes.filter {
                AppSettings.allowedCreditExpiryLeadMinutes.contains($0)
            }
            next.creditExpiryLeadMinutes = leads.isEmpty
                ? AppSettings.defaultCreditExpiryLeadMinutes
                : Array(Set(leads)).sorted()
        }
        if let liveActivity = document.liveActivity {
            next.liveMonitorAutoStartOnAcceleratedUsage = liveActivity.autoStartOnAcceleratedUsage
        }
        return next
    }

    static func defaultFileName(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "codex-meter-settings-\(formatter.string(from: date)).json"
    }
}
