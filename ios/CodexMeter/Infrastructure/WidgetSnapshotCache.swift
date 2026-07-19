import CodexMeterCore
import Foundation

/// Writes only `SharedWidgetSnapshot`, the deliberately sanitized schema from
/// CodexMeterCore. This API cannot persist tokens or arbitrary app cache data.
public actor WidgetSnapshotCache {
    public nonisolated static let appGroupIdentifier = "group.com.bukovinafilip.CodexMeter"
    public nonisolated static let shared = WidgetSnapshotCache()
    public nonisolated static let unavailableMessage =
        "Home Screen widgets need an App Group. Configure signing and the group.com.bukovinafilip.CodexMeter entitlement in Xcode."

    /// `false` when the App Group container is missing (unsigned / misconfigured builds).
    public nonisolated let isAvailable: Bool

    private let fileURL: URL?
    private let fileManager: FileManager

    public init(
        appGroupIdentifier: String = WidgetSnapshotCache.appGroupIdentifier,
        fileManager: FileManager = .default
    ) {
        self.fileManager = fileManager
        let resolved = fileManager
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)?
            .appendingPathComponent(SharedWidgetSnapshot.defaultFileName)
        self.fileURL = resolved
        self.isAvailable = resolved != nil
    }

    public init(fileURL: URL, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.fileURL = fileURL
        self.isAvailable = true
    }

    public func save(_ snapshot: SharedWidgetSnapshot, nextCreditExpiry: Date? = nil) async throws {
        guard let fileURL else {
            throw CodexServiceError.storage(Self.unavailableMessage)
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(snapshot)
        try data.write(to: fileURL, options: [.atomic])
        await MainActor.run {
            PhoneWatchBridge.shared.push(snapshot: snapshot, nextCreditExpiry: nextCreditExpiry)
        }
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
        try await save(snapshot, nextCreditExpiry: credits?.nextExpiry(after: now))
    }

    public func publishSignedOut() async throws {
        do {
            try await save(.signedOut)
        } catch {
            // Never leave an authenticated-looking snapshot behind if the
            // signed-out write fails (disk/App Group issues, etc.).
            try? await clear()
            throw error
        }
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
