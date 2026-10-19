import CodexMeterCore
import Foundation

nonisolated public struct AppCacheSnapshot: Codable, Sendable, Equatable {
    public var usage: UsageSnapshot?
    public var credits: ResetCreditsSnapshot?
    public var lastError: String?
    public var resetCreditsError: String?
    public var updatedAt: Date

    public init(
        usage: UsageSnapshot? = nil,
        credits: ResetCreditsSnapshot? = nil,
        lastError: String? = nil,
        resetCreditsError: String? = nil,
        updatedAt: Date = Date()
    ) {
        self.usage = usage
        self.credits = credits
        self.lastError = lastError
        self.resetCreditsError = resetCreditsError
        self.updatedAt = updatedAt
    }
}

/// Persists the full app-only cache as a single atomically replaced file.
public actor AppCacheStore {
    public nonisolated static let defaultFileName = "app-cache-v1.json"
    public nonisolated static let shared = AppCacheStore()

    private let fileURL: URL
    private let fileManager: FileManager
    private var memorySnapshot: AppCacheSnapshot?

    public init(fileURL: URL? = nil, fileManager: FileManager = .default) {
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

    public func load() async throws -> AppCacheSnapshot? {
        if let memorySnapshot {
            return memorySnapshot
        }
        guard fileManager.fileExists(atPath: fileURL.path) else {
            return nil
        }
        let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe, .uncached])
        guard data.count <= HTTPClientSupport.maximumResponseBytes else {
            throw CodexServiceError.storage("The local cache was unexpectedly large.")
        }
        do {
            let decoded = try Self.decoder.decode(AppCacheSnapshot.self, from: data)
            memorySnapshot = decoded
            return decoded
        } catch {
            throw CodexServiceError.storage("The local cache could not be read.")
        }
    }

    public func save(_ snapshot: AppCacheSnapshot) async throws {
        let data = try Self.encoder.encode(snapshot)
        try createParentDirectory()
        try data.write(to: fileURL, options: [.atomic])
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: fileURL.path
        )
        memorySnapshot = snapshot
    }

    @discardableResult
    public func updateUsage(
        _ usage: UsageSnapshot,
        error: String? = nil,
        at date: Date = Date()
    ) async throws -> AppCacheSnapshot {
        var snapshot = (try await load()) ?? AppCacheSnapshot(updatedAt: date)
        snapshot.usage = usage
        snapshot.lastError = error
        snapshot.updatedAt = date
        try await save(snapshot)
        return snapshot
    }

    @discardableResult
    public func updateCredits(
        _ credits: ResetCreditsSnapshot,
        error: String? = nil,
        at date: Date = Date()
    ) async throws -> AppCacheSnapshot {
        var snapshot = (try await load()) ?? AppCacheSnapshot(updatedAt: date)
        snapshot.credits = credits
        snapshot.resetCreditsError = error
        snapshot.updatedAt = date
        try await save(snapshot)
        return snapshot
    }

    public func recordUsageError(_ message: String, at date: Date = Date()) async {
        var snapshot = (try? await load()) ?? AppCacheSnapshot(updatedAt: date)
        snapshot.lastError = String(message.prefix(240))
        snapshot.updatedAt = date
        try? await save(snapshot)
    }

    public func recordCreditsError(_ message: String, at date: Date = Date()) async {
        var snapshot = (try? await load()) ?? AppCacheSnapshot(updatedAt: date)
        snapshot.resetCreditsError = String(message.prefix(240))
        snapshot.updatedAt = date
        try? await save(snapshot)
    }

    public func clear() async throws {
        memorySnapshot = nil
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        try fileManager.removeItem(at: fileURL)
    }

    private func createParentDirectory() throws {
        let directory = fileURL.deletingLastPathComponent()
        try fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
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
