import Foundation

public struct WatchSnapshotEnvelope: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1

    public var schemaVersion: Int
    public var fiveHour: UsageWindow?
    public var weekly: UsageWindow?
    public var creditsAvailable: Int?
    public var nextCreditExpiry: Date?
    public var planLabel: String
    public var mode: WidgetSnapshotMode
    public var fetchTime: Date?
    public var sourceTime: Date
    public var freshness: WidgetSnapshotFreshness

    public init(
        schemaVersion: Int = Self.currentSchemaVersion,
        fiveHour: UsageWindow?,
        weekly: UsageWindow?,
        creditsAvailable: Int?,
        nextCreditExpiry: Date?,
        planLabel: String,
        mode: WidgetSnapshotMode,
        fetchTime: Date?,
        sourceTime: Date = Date(),
        freshness: WidgetSnapshotFreshness
    ) {
        self.schemaVersion = schemaVersion
        self.fiveHour = fiveHour
        self.weekly = weekly
        self.creditsAvailable = creditsAvailable.map { max(0, $0) }
        self.nextCreditExpiry = nextCreditExpiry
        self.planLabel = planLabel
        self.mode = mode
        self.fetchTime = fetchTime
        self.sourceTime = sourceTime
        self.freshness = freshness
    }

    public init(snapshot: SharedWidgetSnapshot, nextCreditExpiry: Date? = nil, sourceTime: Date = Date()) {
        self.init(
            fiveHour: snapshot.fiveHour,
            weekly: snapshot.weekly,
            creditsAvailable: snapshot.resetCreditsAvailable,
            nextCreditExpiry: nextCreditExpiry,
            planLabel: snapshot.planType,
            mode: snapshot.mode,
            fetchTime: snapshot.fetchedAt,
            sourceTime: sourceTime,
            freshness: snapshot.freshness
        )
    }

    public var snapshot: SharedWidgetSnapshot {
        SharedWidgetSnapshot(
            version: schemaVersion,
            mode: mode,
            fetchedAt: fetchTime,
            planType: planLabel,
            fiveHour: fiveHour,
            weekly: weekly,
            resetCreditsAvailable: creditsAvailable,
            freshness: freshness
        )
    }
}

/// Property-list-safe, credential-free WatchConnectivity payload.
public enum WatchSyncPayload {
    public static let contextKey = "codex_meter_watch_snapshot_v1"
    /// UserDefaults key on the watch for the last received snapshot (app + complications).
    public static let watchDefaultsKey = "codex-meter.watch.snapshot-v1"
    public static let format = "codex_meter_watch_snapshot"
    public static let version = 1

    public static func encode(_ envelope: WatchSnapshotEnvelope) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(envelope)
    }

    public static func encode(_ snapshot: SharedWidgetSnapshot) throws -> Data {
        try encode(WatchSnapshotEnvelope(snapshot: snapshot))
    }

    public static func decodeEnvelope(_ data: Data) throws -> WatchSnapshotEnvelope {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let envelope = try decoder.decode(WatchSnapshotEnvelope.self, from: data)
        guard envelope.schemaVersion == WatchSnapshotEnvelope.currentSchemaVersion else {
            throw WatchSyncError.invalidFormat
        }
        return envelope
    }

    public static func decode(_ data: Data) throws -> SharedWidgetSnapshot {
        try decodeEnvelope(data).snapshot
    }

    /// `WCSession` applicationContext / userInfo entry.
    public static func applicationContext(for snapshot: SharedWidgetSnapshot) throws -> [String: Any] {
        [contextKey: try encode(snapshot)]
    }

    public static func snapshot(fromApplicationContext context: [String: Any]) throws -> SharedWidgetSnapshot {
        guard let data = context[contextKey] as? Data else {
            throw WatchSyncError.missingPayload
        }
        return try decode(data)
    }

    public enum WatchSyncError: Error, LocalizedError {
        case invalidFormat
        case missingPayload

        public var errorDescription: String? {
            switch self {
            case .invalidFormat: "Invalid watch snapshot format."
            case .missingPayload: "Watch payload missing."
            }
        }
    }
}
