import CodexMeterCore
import Foundation

nonisolated struct LocalUsageHistorySnapshot: Codable, Sendable, Equatable {
    var fiveHour: UsageHistory
    var weekly: UsageHistory
    var monthly: UsageHistory

    static let empty = LocalUsageHistorySnapshot(
        fiveHour: .empty(.fiveHour),
        weekly: .empty(.weekly),
        monthly: .empty(.monthly)
    )

    private enum CodingKeys: String, CodingKey {
        case fiveHour
        case weekly
        case monthly
    }

    init(fiveHour: UsageHistory, weekly: UsageHistory, monthly: UsageHistory) {
        self.fiveHour = fiveHour
        self.weekly = weekly
        self.monthly = monthly
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        fiveHour = try container.decodeIfPresent(UsageHistory.self, forKey: .fiveHour)
            ?? .empty(.fiveHour)
        weekly = try container.decodeIfPresent(UsageHistory.self, forKey: .weekly)
            ?? .empty(.weekly)
        monthly = try container.decodeIfPresent(UsageHistory.self, forKey: .monthly)
            ?? .empty(.monthly)
    }
}

/// Stores only bounded allowance samples used for on-device charts and pace estimates.
/// No account identifiers, tokens, or raw API responses are written here.
actor UsageHistoryStore {
    static let defaultFileName = "usage-history-v1.json"
    static let shared = UsageHistoryStore()

    private let fileURL: URL
    private let fileManager: FileManager
    private var memorySnapshot: LocalUsageHistorySnapshot?

    init(fileURL: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? fileManager.temporaryDirectory
            self.fileURL = base
                .appendingPathComponent("CodexMeter", isDirectory: true)
                .appendingPathComponent(Self.defaultFileName)
        }
    }

    func load() -> LocalUsageHistorySnapshot {
        if let memorySnapshot {
            return memorySnapshot
        }
        guard fileManager.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL, options: [.mappedIfSafe, .uncached]),
              data.count <= 512 * 1_024,
              let decoded = try? Self.decoder.decode(LocalUsageHistorySnapshot.self, from: data)
        else {
            memorySnapshot = .empty
            return .empty
        }
        memorySnapshot = decoded
        return decoded
    }

    @discardableResult
    func record(_ usage: UsageSnapshot) throws -> LocalUsageHistorySnapshot {
        var snapshot = load()
        if let fiveHour = usage.fiveHour {
            snapshot.fiveHour = snapshot.fiveHour.appending(
                window: fiveHour,
                observedAt: usage.fetchedAt
            )
        }
        if let weekly = usage.weekly {
            snapshot.weekly = snapshot.weekly.appending(
                window: weekly,
                observedAt: usage.fetchedAt
            )
        }
        if let monthly = usage.monthly {
            snapshot.monthly = snapshot.monthly.appending(
                window: monthly,
                observedAt: usage.fetchedAt
            )
        }
        try save(snapshot)
        return snapshot
    }

    func clear() throws {
        memorySnapshot = .empty
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        try fileManager.removeItem(at: fileURL)
    }

    private func save(_ snapshot: LocalUsageHistorySnapshot) throws {
        let data = try Self.encoder.encode(snapshot)
        let directory = fileURL.deletingLastPathComponent()
        try fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        try data.write(to: fileURL, options: [.atomic])
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: fileURL.path
        )
        memorySnapshot = snapshot
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
