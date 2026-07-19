import Foundation

/// Property-list-safe WatchConnectivity payload wrapping `SharedWidgetSnapshot`.
/// Phone and watch exchange only this sanitized schema — never tokens.
public enum WatchSyncPayload {
    public static let contextKey = "codex_meter_watch_snapshot_v1"
    public static let format = "codex_meter_watch_snapshot"
    public static let version = 1

    public static func encode(_ snapshot: SharedWidgetSnapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(Envelope(format: format, version: version, snapshot: snapshot))
    }

    public static func decode(_ data: Data) throws -> SharedWidgetSnapshot {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let envelope = try decoder.decode(Envelope.self, from: data)
        guard envelope.format == format, envelope.version == version else {
            throw WatchSyncError.invalidFormat
        }
        return envelope.snapshot
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

    private struct Envelope: Codable, Sendable {
        var format: String
        var version: Int
        var snapshot: SharedWidgetSnapshot
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
