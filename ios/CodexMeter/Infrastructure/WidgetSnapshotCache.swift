import CodexMeterCore
import Foundation

/// Writes only `SharedWidgetSnapshot`, the deliberately sanitized schema from
/// CodexMeterCore. This API cannot persist tokens or arbitrary app cache data.
public actor WidgetSnapshotCache {
    public nonisolated static let appGroupIdentifier = "group.com.bukovinafilip.CodexMeter"
    public nonisolated static let shared = WidgetSnapshotCache()

    private let fileURL: URL?
    private let fileManager: FileManager

    public init(
        appGroupIdentifier: String = WidgetSnapshotCache.appGroupIdentifier,
        fileManager: FileManager = .default
    ) {
        self.fileManager = fileManager
        self.fileURL = fileManager
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)?
            .appendingPathComponent(SharedWidgetSnapshot.defaultFileName)
    }

    public init(fileURL: URL, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.fileURL = fileURL
    }

    public func save(_ snapshot: SharedWidgetSnapshot) async throws {
        guard let fileURL else {
            throw CodexServiceError.storage("The App Group container is unavailable.")
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(snapshot)
        try data.write(to: fileURL, options: [.atomic])
    }

    public func publish(
        mode: WidgetSnapshotMode,
        usage: UsageSnapshot?,
        credits: ResetCreditsSnapshot?,
        now: Date = Date(),
        staleAfter: TimeInterval = 2 * 60 * 60
    ) async throws {
        let freshness = WidgetSnapshotFreshness.evaluate(
            fetchedAt: usage?.fetchedAt,
            now: now,
            staleAfter: staleAfter
        )
        let snapshot = SharedWidgetSnapshot(
            mode: mode,
            fetchedAt: usage?.fetchedAt,
            planType: usage?.planType ?? "",
            fiveHour: usage?.fiveHour,
            weekly: usage?.weekly,
            resetCreditsAvailable: credits?.availableCount ?? usage?.resetCreditsAvailable,
            freshness: freshness
        )
        try await save(snapshot)
    }

    public func publishSignedOut() async throws {
        try await save(.signedOut)
    }

    public func load() async throws -> SharedWidgetSnapshot? {
        guard let fileURL, fileManager.fileExists(atPath: fileURL.path) else {
            return nil
        }
        let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe, .uncached])
        guard data.count <= 256 * 1_024 else {
            throw CodexServiceError.storage("The widget snapshot was unexpectedly large.")
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(SharedWidgetSnapshot.self, from: data)
    }

    public func clear() async throws {
        guard let fileURL, fileManager.fileExists(atPath: fileURL.path) else { return }
        try fileManager.removeItem(at: fileURL)
    }
}
