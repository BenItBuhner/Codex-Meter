import CodexMeterCore
import Foundation

/// Opt-in, privacy-filtered, rotating JSONL diagnostics stored only in app-private storage.
enum DiagnosticLog {
    static let unlockKey = "codex-meter.diagnostics-unlocked-v1"
    private static let enabledKey = "codex-meter.diagnostics-enabled-v1"
    private static let sessionKey = "codex-meter.diagnostics-session-v1"
    private static let directoryName = "diagnostic-logs"
    private static let currentFileName = "events.jsonl"
    private static let maxArchives = 2
    private static let maxFileBytes = 1_024 * 1_024
    private static let lock = NSLock()
    nonisolated(unsafe) private static var sequence: Int64 = 0

    struct Stats: Sendable, Equatable {
        var bytes: Int64
        var files: Int

        var hasLogs: Bool { bytes > 0 }
    }

    static func isUnlocked(defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: unlockKey)
    }

    static func unlock(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: unlockKey)
    }

    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: enabledKey)
    }

    static func setEnabled(_ enabled: Bool) {
        let wasEnabled = isEnabled
        guard wasEnabled != enabled else { return }
        if enabled {
            UserDefaults.standard.set(UUID().uuidString, forKey: sessionKey)
            UserDefaults.standard.set(true, forKey: enabledKey)
            info("diagnostics", "tracing_enabled", details: [
                "app_version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
                "ios": ProcessInfo.processInfo.operatingSystemVersionString
            ])
        } else {
            info("diagnostics", "tracing_disabled")
            UserDefaults.standard.set(false, forKey: enabledKey)
        }
    }

    static func info(_ category: String, _ event: String, details: [String: String] = [:]) {
        write(level: "info", category: category, event: event, error: nil, details: details)
    }

    static func warn(_ category: String, _ event: String, details: [String: String] = [:]) {
        write(level: "warn", category: category, event: event, error: nil, details: details)
    }

    static func error(
        _ category: String,
        _ event: String,
        error: Error? = nil,
        details: [String: String] = [:]
    ) {
        write(level: "error", category: category, event: event, error: error, details: details)
    }

    static func stats() -> Stats {
        lock.lock()
        defer { lock.unlock() }
        var bytes: Int64 = 0
        var files = 0
        for file in orderedFiles() where FileManager.default.fileExists(atPath: file.path) {
            if let size = try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber {
                let length = size.int64Value
                if length > 0 {
                    bytes += length
                    files += 1
                }
            }
        }
        return Stats(bytes: bytes, files: files)
    }

    static func clear() {
        lock.lock()
        defer { lock.unlock() }
        for file in orderedFiles() where FileManager.default.fileExists(atPath: file.path) {
            try? FileManager.default.removeItem(at: file)
        }
    }

    static func exportURL() throws -> URL {
        info("diagnostics", "export_started")
        lock.lock()
        defer { lock.unlock() }
        let export = FileManager.default.temporaryDirectory
            .appendingPathComponent("codex-meter-diagnostics.jsonl")
        if FileManager.default.fileExists(atPath: export.path) {
            try FileManager.default.removeItem(at: export)
        }
        FileManager.default.createFile(atPath: export.path, contents: nil)
        let handle = try FileHandle(forWritingTo: export)
        defer { try? handle.close() }
        for file in orderedFiles() {
            guard FileManager.default.fileExists(atPath: file.path),
                  let data = try? Data(contentsOf: file),
                  !data.isEmpty else {
                continue
            }
            try handle.write(contentsOf: data)
        }
        return export
    }

    static func formatBytes(_ bytes: Int64) -> String {
        if bytes < 1_024 {
            return "\(bytes) B"
        }
        if bytes < 1_024 * 1_024 {
            return "\(max(1, bytes / 1_024)) KB"
        }
        return String(format: "%.1f MB", Double(bytes) / (1_024.0 * 1_024.0))
    }

    private static func write(
        level: String,
        category: String,
        event: String,
        error: Error?,
        details: [String: String]
    ) {
        guard isEnabled else { return }
        do {
            var record: [String: Any] = [
                "timestamp": ISO8601DateFormatter().string(from: Date()),
                "sequence": nextSequence(),
                "session_id": UserDefaults.standard.string(forKey: sessionKey) ?? "",
                "level": DiagnosticSanitizer.redact(level),
                "category": DiagnosticSanitizer.redact(category),
                "event": DiagnosticSanitizer.redact(event),
                "thread": DiagnosticSanitizer.redact(Thread.current.name ?? "main")
            ]
            if !details.isEmpty {
                record["details"] = details.reduce(into: [String: String]()) { result, item in
                    result[DiagnosticSanitizer.redact(item.key)] = DiagnosticSanitizer.redact(item.value)
                }
            }
            if let error {
                record["error"] = [
                    "type": String(describing: type(of: error)),
                    "message": DiagnosticSanitizer.redact(error.localizedDescription)
                ]
            }
            let data = try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
            var line = data
            line.append(contentsOf: "\n".utf8)
            append(line)
        } catch {
            // Diagnostics must never break the operation being diagnosed.
        }
    }

    private static func nextSequence() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        sequence += 1
        return sequence
    }

    private static func append(_ line: Data) {
        lock.lock()
        defer { lock.unlock() }
        let directory = directoryURL()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let current = directory.appendingPathComponent(currentFileName)
        let existing = (try? FileManager.default.attributesOfItem(atPath: current.path)[.size] as? NSNumber)?
            .int64Value ?? 0
        if existing + Int64(line.count) > maxFileBytes {
            rotate(directory)
        }
        if !FileManager.default.fileExists(atPath: current.path) {
            FileManager.default.createFile(atPath: current.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: current) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: line)
    }

    private static func rotate(_ directory: URL) {
        let oldest = archive(directory, index: maxArchives)
        if FileManager.default.fileExists(atPath: oldest.path) {
            try? FileManager.default.removeItem(at: oldest)
        }
        for index in stride(from: maxArchives - 1, through: 1, by: -1) {
            let source = archive(directory, index: index)
            if FileManager.default.fileExists(atPath: source.path) {
                try? FileManager.default.moveItem(at: source, to: archive(directory, index: index + 1))
            }
        }
        let current = directory.appendingPathComponent(currentFileName)
        if FileManager.default.fileExists(atPath: current.path) {
            try? FileManager.default.moveItem(at: current, to: archive(directory, index: 1))
        }
    }

    private static func orderedFiles() -> [URL] {
        let directory = directoryURL()
        return [
            archive(directory, index: 2),
            archive(directory, index: 1),
            directory.appendingPathComponent(currentFileName)
        ]
    }

    private static func archive(_ directory: URL, index: Int) -> URL {
        directory.appendingPathComponent("events.\(index).jsonl")
    }

    private static func directoryURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("CodexMeter", isDirectory: true)
            .appendingPathComponent(directoryName, isDirectory: true)
    }
}
