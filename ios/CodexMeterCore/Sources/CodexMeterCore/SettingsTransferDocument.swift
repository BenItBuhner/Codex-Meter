import Foundation

/// Credential-free preferences document shared by Apple platform targets.
public struct SettingsTransferDocument: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1

    public var schemaVersion: Int
    public var exportedAt: Date
    public var appearance: AppearanceSection?
    public var refresh: RefreshSection?
    public var usageWarnings: UsageWarningsSection?
    public var notifications: NotificationsSection?
    public var liveActivity: LiveActivitySection?

    public init(
        schemaVersion: Int = Self.currentSchemaVersion,
        exportedAt: Date = Date(),
        appearance: AppearanceSection? = nil,
        refresh: RefreshSection? = nil,
        usageWarnings: UsageWarningsSection? = nil,
        notifications: NotificationsSection? = nil,
        liveActivity: LiveActivitySection? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.exportedAt = exportedAt
        self.appearance = appearance
        self.refresh = refresh
        self.usageWarnings = usageWarnings
        self.notifications = notifications
        self.liveActivity = liveActivity
    }

    public var hasSettings: Bool {
        appearance != nil || refresh != nil || usageWarnings != nil
            || notifications != nil || liveActivity != nil
    }

    public struct AppearanceSection: Codable, Equatable, Sendable {
        public var mode: String
        public init(mode: String) { self.mode = mode }
    }

    public struct RefreshSection: Codable, Equatable, Sendable {
        public var refreshOnLaunch: Bool
        public var preferredMinutes: Int
        public init(refreshOnLaunch: Bool, preferredMinutes: Int) {
            self.refreshOnLaunch = refreshOnLaunch
            self.preferredMinutes = preferredMinutes
        }
    }

    public struct UsageWarningsSection: Codable, Equatable, Sendable {
        public var estimatesEnabled: Bool
        public var sensitivity: UsagePaceSensitivity
        public init(estimatesEnabled: Bool, sensitivity: UsagePaceSensitivity) {
            self.estimatesEnabled = estimatesEnabled
            self.sensitivity = sensitivity
        }
    }

    public struct NotificationsSection: Codable, Equatable, Sendable {
        public var enabled: Bool
        public var metric: String
        public var threshold: Int
        public var creditIncrease: Bool
        public var unexpectedRefill: Bool
        public var creditExpiry: Bool
        public var creditExpiryLeadMinutes: [Int]

        public init(
            enabled: Bool,
            metric: String,
            threshold: Int,
            creditIncrease: Bool,
            unexpectedRefill: Bool,
            creditExpiry: Bool,
            creditExpiryLeadMinutes: [Int]
        ) {
            self.enabled = enabled
            self.metric = metric
            self.threshold = threshold
            self.creditIncrease = creditIncrease
            self.unexpectedRefill = unexpectedRefill
            self.creditExpiry = creditExpiry
            self.creditExpiryLeadMinutes = creditExpiryLeadMinutes
        }
    }

    public struct LiveActivitySection: Codable, Equatable, Sendable {
        public var autoStartOnAcceleratedUsage: Bool
        public init(autoStartOnAcceleratedUsage: Bool) {
            self.autoStartOnAcceleratedUsage = autoStartOnAcceleratedUsage
        }
    }

    public enum ValidationError: LocalizedError, Equatable {
        case empty
        case unsupportedVersion(Int)
        case noSettings
        case malformed

        public var errorDescription: String? {
            switch self {
            case .empty: "The settings file is empty."
            case .unsupportedVersion(let version): "Settings schema version \(version) is not supported."
            case .noSettings: "The document does not contain any settings."
            case .malformed: "The settings document is malformed."
            }
        }
    }

    public func encoded() throws -> Data {
        guard schemaVersion == Self.currentSchemaVersion else {
            throw ValidationError.unsupportedVersion(schemaVersion)
        }
        guard hasSettings else { throw ValidationError.noSettings }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }

    public static func decodeValidated(_ data: Data) throws -> Self {
        guard !data.isEmpty else { throw ValidationError.empty }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let document: Self
        do { document = try decoder.decode(Self.self, from: data) }
        catch { throw ValidationError.malformed }
        guard document.schemaVersion == Self.currentSchemaVersion else {
            throw ValidationError.unsupportedVersion(document.schemaVersion)
        }
        guard document.hasSettings else { throw ValidationError.noSettings }
        return document
    }
}
