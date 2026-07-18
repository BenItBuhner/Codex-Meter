import Foundation

/// Portable Codex Meter transfer document for iOS.
///
/// Inspired by Android `SettingsTransfer` (format + sections). Authentication
/// sections contain usable ChatGPT OAuth tokens and must never be shared.
enum SettingsTransfer {
    static let format = "codex_meter_transfer"
    static let version = 1
    static let platform = "ios"

    static let sectionAppSettings = "app_settings"
    static let sectionNotifications = "notifications"
    static let sectionAuthentication = "authentication"

    static let securityWarning =
        "IMPORTANT: This file contains ChatGPT authentication tokens. "
        + "Anyone with this file can access your ChatGPT account. "
        + "Do not share it, upload it, or send it to anyone. "
        + "Keep it only on devices you trust."

    struct Document: Codable, Equatable, Sendable {
        var format: String
        var version: Int
        var platform: String?
        var exportedAt: Date
        var securityWarning: String?
        var sections: Sections

        struct Sections: Codable, Equatable, Sendable {
            var appSettings: AppSettingsPayload?
            var notifications: NotificationsPayload?
            var authentication: AuthenticationPayload?

            enum CodingKeys: String, CodingKey {
                case appSettings = "app_settings"
                case notifications
                case authentication
            }

            var hasAnySection: Bool {
                appSettings != nil || notifications != nil || authentication != nil
            }
        }
    }

    struct AppSettingsPayload: Codable, Equatable, Sendable {
        var appearance: String?
        var refreshOnLaunch: Bool?
        var refreshMinutes: Int?

        enum CodingKeys: String, CodingKey {
            case appearance
            case refreshOnLaunch = "refresh_on_launch"
            case refreshMinutes = "refresh_minutes"
        }
    }

    struct NotificationsPayload: Codable, Equatable, Sendable {
        var notificationsEnabled: Bool?
        var alertMetric: String?
        var alertThreshold: Int?
        var creditIncreaseAlertsEnabled: Bool?
        var unexpectedRefillAlertsEnabled: Bool?
        var creditExpiryRemindersEnabled: Bool?
        var creditExpiryLeadMinutes: [Int]?

        enum CodingKeys: String, CodingKey {
            case notificationsEnabled = "notifications_enabled"
            case alertMetric = "alert_metric"
            case alertThreshold = "alert_threshold"
            case creditIncreaseAlertsEnabled = "credit_increase_alerts_enabled"
            case unexpectedRefillAlertsEnabled = "unexpected_refill_alerts_enabled"
            case creditExpiryRemindersEnabled = "credit_expiry_reminders_enabled"
            case creditExpiryLeadMinutes = "credit_expiry_lead_minutes"
        }
    }

    struct AuthenticationPayload: Codable, Equatable, Sendable {
        var accessToken: String
        var refreshToken: String
        var idToken: String
        var expiresAt: Date
        var accountID: String?
        var email: String?

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token"
            case refreshToken = "refresh_token"
            case idToken = "id_token"
            case expiresAt = "expires_at"
            case accountID = "account_id"
            case email
        }
    }

    struct ExportOptions: Equatable, Sendable {
        var includeAppSettings = true
        var includeNotifications = true
        var includeAuthentication = false
    }

    struct ImportOptions: Equatable, Sendable {
        var importAppSettings = true
        var importNotifications = true
        var importAuthentication = false
    }

    enum TransferError: LocalizedError {
        case empty
        case invalidFormat
        case unsupportedVersion(Int)
        case noSections
        case noSelectedSections
        case invalidAuthentication

        var errorDescription: String? {
            switch self {
            case .empty:
                return "Transfer file is empty."
            case .invalidFormat:
                return "Not a Codex Meter transfer file."
            case .unsupportedVersion(let version):
                return "Unsupported transfer file version: \(version)."
            case .noSections:
                return "Transfer file does not contain any sections."
            case .noSelectedSections:
                return "Choose at least one section to import."
            case .invalidAuthentication:
                return "Authentication section is incomplete or unusable."
            }
        }
    }

    // MARK: - Build / parse

    static func makeDocument(
        settings: AppSettings,
        tokens: AuthTokens?,
        options: ExportOptions,
        exportedAt: Date = Date()
    ) -> Document {
        var sections = Document.Sections()
        if options.includeAppSettings {
            sections.appSettings = AppSettingsPayload(
                appearance: settings.appearance.rawValue,
                refreshOnLaunch: settings.refreshOnLaunch,
                refreshMinutes: settings.refreshMinutes
            )
        }
        if options.includeNotifications {
            sections.notifications = NotificationsPayload(
                notificationsEnabled: settings.notificationsEnabled,
                alertMetric: settings.alertMetric.rawValue,
                alertThreshold: settings.alertThreshold,
                creditIncreaseAlertsEnabled: settings.creditIncreaseAlertsEnabled,
                unexpectedRefillAlertsEnabled: settings.unexpectedRefillAlertsEnabled,
                creditExpiryRemindersEnabled: settings.creditExpiryRemindersEnabled,
                creditExpiryLeadMinutes: settings.creditExpiryLeadMinutes
            )
        }
        var warning: String?
        if options.includeAuthentication, let tokens, tokens.isUsable {
            sections.authentication = AuthenticationPayload(
                accessToken: tokens.accessToken,
                refreshToken: tokens.refreshToken,
                idToken: tokens.idToken,
                expiresAt: tokens.expiresAt,
                accountID: tokens.accountID,
                email: tokens.email
            )
            warning = securityWarning
        }
        return Document(
            format: format,
            version: version,
            platform: platform,
            exportedAt: exportedAt,
            securityWarning: warning,
            sections: sections
        )
    }

    static func encode(_ document: Document) throws -> Data {
        guard document.sections.hasAnySection else {
            throw TransferError.noSections
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(document)
    }

    static func parse(_ data: Data) throws -> Document {
        guard !data.isEmpty else { throw TransferError.empty }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let document: Document
        do {
            document = try decoder.decode(Document.self, from: data)
        } catch {
            throw TransferError.invalidFormat
        }
        guard document.format == format else {
            throw TransferError.invalidFormat
        }
        guard document.version >= 1, document.version <= version else {
            throw TransferError.unsupportedVersion(document.version)
        }
        guard document.sections.hasAnySection else {
            throw TransferError.noSections
        }
        return document
    }

    static func apply(
        document: Document,
        to settings: AppSettings,
        options: ImportOptions
    ) throws -> (settings: AppSettings, tokens: AuthTokens?) {
        if !options.importAppSettings && !options.importNotifications && !options.importAuthentication {
            throw TransferError.noSelectedSections
        }

        var next = settings
        if options.importAppSettings, let app = document.sections.appSettings {
            if let raw = app.appearance, let appearance = AppAppearance(rawValue: raw) {
                next.appearance = appearance
            }
            if let refreshOnLaunch = app.refreshOnLaunch {
                next.refreshOnLaunch = refreshOnLaunch
            }
            if let minutes = app.refreshMinutes,
               AppSettings.allowedRefreshMinutes.contains(minutes) {
                next.refreshMinutes = minutes
            }
        }

        if options.importNotifications, let notes = document.sections.notifications {
            if let enabled = notes.notificationsEnabled {
                next.notificationsEnabled = enabled
            }
            if let raw = notes.alertMetric, let metric = AlertMetric(rawValue: raw) {
                next.alertMetric = metric
            }
            if let threshold = notes.alertThreshold,
               AppSettings.allowedAlertThresholds.contains(threshold) {
                next.alertThreshold = threshold
            }
            if let value = notes.creditIncreaseAlertsEnabled {
                next.creditIncreaseAlertsEnabled = value
            }
            if let value = notes.unexpectedRefillAlertsEnabled {
                next.unexpectedRefillAlertsEnabled = value
            }
            if let value = notes.creditExpiryRemindersEnabled {
                next.creditExpiryRemindersEnabled = value
            }
            if let leads = notes.creditExpiryLeadMinutes {
                next.creditExpiryLeadMinutes = leads.filter {
                    AppSettings.allowedCreditExpiryLeadMinutes.contains($0)
                }.sorted()
                if next.creditExpiryLeadMinutes.isEmpty {
                    next.creditExpiryLeadMinutes = AppSettings.defaultCreditExpiryLeadMinutes
                }
            }
        }

        var tokens: AuthTokens?
        if options.importAuthentication {
            guard let auth = document.sections.authentication else {
                throw TransferError.invalidAuthentication
            }
            let candidate = AuthTokens(
                accessToken: auth.accessToken,
                refreshToken: auth.refreshToken,
                idToken: auth.idToken,
                expiresAt: auth.expiresAt,
                accountID: auth.accountID ?? "",
                email: auth.email ?? ""
            )
            guard candidate.isUsable else {
                throw TransferError.invalidAuthentication
            }
            tokens = candidate
        }

        return (next, tokens)
    }

    static func defaultFileName(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "codex-meter-transfer-\(formatter.string(from: date)).json"
    }
}
