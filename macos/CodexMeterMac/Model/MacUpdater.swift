import AppKit
import CryptoKit
import Foundation
import Observation
import UserNotifications

// In-app updates for the Mac app, the same flow as Android and the Windows and Linux apps:
// ask GitHub for at most two small responses (the latest release, plus a short listing on the
// alpha channel), download the release's .zip, check its SHA-256 against SHA256SUMS-macos.txt,
// then quit, swap the app bundle in place and reopen it. A sandboxed build, or an app in a
// folder this user can't write, gets the verified .dmg opened instead.

/// A release version such as 2.9.0 or 2.9.0-alpha.27, compared the SemVer way (Android's rules).
struct MacReleaseVersion: Comparable, Sendable, CustomStringConvertible {
    let major: Int
    let minor: Int
    let patch: Int
    let prerelease: [String]

    var isPrerelease: Bool { !prerelease.isEmpty }

    var description: String {
        "\(major).\(minor).\(patch)" + (prerelease.isEmpty ? "" : "-" + prerelease.joined(separator: "."))
    }

    init?(_ raw: String) {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("v") || text.hasPrefix("V") { text.removeFirst() }
        if let plus = text.firstIndex(of: "+") { text = String(text[..<plus]) }
        var pre: [String] = []
        if let dash = text.firstIndex(of: "-") {
            let tail = text[text.index(after: dash)...]
            pre = tail.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            guard !tail.isEmpty, pre.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" } }) else {
                return nil
            }
            text = String(text[..<dash])
        }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...3).contains(parts.count), parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }) else {
            return nil
        }
        let numbers = parts.compactMap { Int($0) }
        guard numbers.count == parts.count else { return nil }
        major = numbers[0]
        minor = numbers.count > 1 ? numbers[1] : 0
        patch = numbers.count > 2 ? numbers[2] : 0
        prerelease = pre
    }

    static func < (lhs: MacReleaseVersion, rhs: MacReleaseVersion) -> Bool {
        if lhs.major != rhs.major { return lhs.major < rhs.major }
        if lhs.minor != rhs.minor { return lhs.minor < rhs.minor }
        if lhs.patch != rhs.patch { return lhs.patch < rhs.patch }
        if lhs.prerelease.isEmpty != rhs.prerelease.isEmpty { return !lhs.prerelease.isEmpty }
        for (left, right) in zip(lhs.prerelease, rhs.prerelease) where left != right {
            switch (Int(left), Int(right)) {
            case let (l?, r?): return l < r
            case (_?, nil): return true
            case (nil, _?): return false
            default: return left < right
            }
        }
        return lhs.prerelease.count < rhs.prerelease.count
    }
}

enum MacUpdateChannel: String, CaseIterable, Identifiable, Sendable {
    case stable
    case alpha

    var id: String { rawValue }

    var title: String {
        switch self {
        case .stable: "Stable"
        case .alpha: "Alpha (prereleases)"
        }
    }
}

struct MacReleaseAsset: Equatable, Sendable {
    let name: String
    let url: URL
    let size: Int64
}

struct MacRelease: Equatable, Sendable {
    let tag: String
    let version: MacReleaseVersion
    let prerelease: Bool
    let pageURL: URL
    let notes: String
    let zip: MacReleaseAsset?
    let diskImage: MacReleaseAsset?
    let checksums: MacReleaseAsset?

    static func zipName(_ version: MacReleaseVersion) -> String { "CodexMeter-\(version)-macos.zip" }
    static func diskImageName(_ version: MacReleaseVersion) -> String { "CodexMeter-\(version)-macos.dmg" }
    static let checksumsName = "SHA256SUMS-macos.txt"
}

enum MacUpdateError: LocalizedError {
    case rateLimited
    case http(Int)
    case tooLarge
    case untrusted
    case noChecksum
    case checksumMismatch
    case badArchive
    case message(String)

    var errorDescription: String? {
        switch self {
        case .rateLimited: "GitHub's hourly limit for update checks was reached. Try again later."
        case .http(let code): "GitHub answered with HTTP \(code)."
        case .tooLarge: "GitHub sent more release data than expected, so the check stopped."
        case .untrusted: "The download didn't come from this app's GitHub releases."
        case .noChecksum: "The release has no SHA-256 checksum for this download."
        case .checksumMismatch: "The download didn't match the release's SHA-256 checksum, so it was deleted."
        case .badArchive: "The downloaded app didn't look like Codex Meter."
        case .message(let text): text
        }
    }
}

/// Small, size-capped requests to GitHub's releases API (the "too much release metadata" fix).
enum MacReleaseFeed {
    static let repository = "BenItBuhner/Codex-Meter"
    static let api = URL(string: "https://api.github.com/repos/\(repository)/releases")!
    private static let listingLimit = 4 << 20
    private static let releaseLimit = 1 << 20
    private static let checksumLimit = 64 << 10
    private static let downloadLimit: Int64 = 600 << 20

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 15 * 60
        configuration.httpAdditionalHeaders = [
            "Accept": "application/vnd.github+json",
            "User-Agent": "codex-meter-macos-updater/\(MacUpdater.installedVersionText)",
            "X-GitHub-Api-Version": "2022-11-28",
        ]
        return URLSession(configuration: configuration)
    }()

    /// The releases `channel` can offer, newest first.
    static func check(_ channel: MacUpdateChannel) async throws -> [MacRelease] {
        switch channel {
        case .stable:
            do {
                return parse(try await get(api.appending(path: "latest"), limit: releaseLimit))
            } catch MacUpdateError.rateLimited {
                throw MacUpdateError.rateLimited
            } catch {
                return parse(try await get(listing(3), limit: listingLimit))
            }
        case .alpha:
            var releases: [MacRelease]
            do {
                releases = parse(try await get(listing(10), limit: listingLimit))
            } catch MacUpdateError.rateLimited {
                throw MacUpdateError.rateLimited
            } catch {
                releases = parse(try await get(listing(3), limit: listingLimit))
            }
            if let latest = try? await get(api.appending(path: "latest"), limit: releaseLimit) {
                for release in parse(latest) where !releases.contains(where: { $0.tag == release.tag }) {
                    releases.append(release)
                }
            }
            return releases.sorted { $0.version > $1.version }
        }
    }

    private static func listing(_ count: Int) -> URL {
        var components = URLComponents(url: api, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "per_page", value: String(count))]
        return components.url!
    }

    /// Keeps only what the updater needs from a release object or a listing; skips drafts.
    static func parse(_ data: Data) -> [MacRelease] {
        guard let json = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let objects = (json as? [[String: Any]]) ?? ((json as? [String: Any]).map { [$0] } ?? [])
        return objects.compactMap(release).sorted { $0.version > $1.version }
    }

    private static func release(_ object: [String: Any]) -> MacRelease? {
        if object["draft"] as? Bool == true { return nil }
        guard let tag = object["tag_name"] as? String, tag.count <= 100,
              let version = MacReleaseVersion(tag),
              let page = (object["html_url"] as? String).flatMap(URL.init(string:)), isTrustedPage(page) else { return nil }
        var assets: [String: MacReleaseAsset] = [:]
        for asset in object["assets"] as? [[String: Any]] ?? [] {
            guard let name = asset["name"] as? String,
                  let url = (asset["browser_download_url"] as? String).flatMap(URL.init(string:)), isTrustedPage(url) else { continue }
            let size = (asset["size"] as? NSNumber)?.int64Value ?? -1
            assets[name] = MacReleaseAsset(name: name, url: url, size: size)
        }
        let notes = String((object["body"] as? String ?? "").prefix(4_000))
        return MacRelease(
            tag: tag,
            version: version,
            prerelease: object["prerelease"] as? Bool == true || version.isPrerelease,
            pageURL: page,
            notes: notes,
            zip: assets[MacRelease.zipName(version)],
            diskImage: assets[MacRelease.diskImageName(version)],
            checksums: assets[MacRelease.checksumsName]
        )
    }

    static func isTrustedPage(_ url: URL) -> Bool {
        url.scheme == "https" && url.host()?.lowercased() == "github.com"
            && url.path().lowercased().hasPrefix("/\(repository.lowercased())/releases/")
    }

    private static func isTrustedFinal(_ url: URL?, download: Bool) -> Bool {
        guard let url, url.scheme == "https", let host = url.host()?.lowercased() else { return false }
        if !download { return host == "api.github.com" }
        return host == "github.com" || host.hasSuffix(".githubusercontent.com")
    }

    /// GET with a hard size cap, read as it streams in.
    static func get(_ url: URL, limit: Int, download: Bool = false) async throws -> Data {
        let (bytes, response) = try await session.bytes(from: url)
        guard let http = response as? HTTPURLResponse else { throw MacUpdateError.http(0) }
        if http.statusCode == 429 || (http.statusCode == 403 && http.value(forHTTPHeaderField: "x-ratelimit-remaining") == "0") {
            throw MacUpdateError.rateLimited
        }
        guard http.statusCode == 200 else { throw MacUpdateError.http(http.statusCode) }
        guard isTrustedFinal(http.url, download: download) else { throw MacUpdateError.untrusted }
        if http.expectedContentLength > limit { throw MacUpdateError.tooLarge }
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            if data.count > limit { throw MacUpdateError.tooLarge }
        }
        return data
    }

    /// The SHA-256 the release lists for `name`, or nil when it's missing or listed twice.
    static func expectedSha256(_ sums: String, for name: String) -> String? {
        let matches = sums.split(whereSeparator: \.isNewline).compactMap { line -> String? in
            let parts = line.split(maxSplits: 1, whereSeparator: \.isWhitespace)
            guard parts.count == 2 else { return nil }
            var file = parts[1].trimmingCharacters(in: .whitespaces)
            if file.hasPrefix("*") { file.removeFirst() }
            let hash = parts[0].lowercased()
            guard file == name, hash.count == 64, hash.allSatisfy(\.isHexDigit) else { return nil }
            return hash
        }
        return Set(matches).count == 1 ? matches[0] : nil
    }

    /// Downloads `asset`, checks its size and SHA-256, and returns the verified file.
    static func download(_ asset: MacReleaseAsset, sums: MacReleaseAsset, into directory: URL,
                         progress: @escaping @Sendable (Double) -> Void) async throws -> URL {
        let sumsText = String(decoding: try await get(sums.url, limit: checksumLimit, download: true), as: UTF8.self)
        guard let expected = expectedSha256(sumsText, for: asset.name) else { throw MacUpdateError.noChecksum }
        guard asset.size <= downloadLimit else { throw MacUpdateError.tooLarge }

        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appending(path: asset.name)
        let partial = directory.appending(path: asset.name + ".partial")
        try? FileManager.default.removeItem(at: partial)
        FileManager.default.createFile(atPath: partial.path, contents: nil)
        let handle = try FileHandle(forWritingTo: partial)
        var hasher = SHA256()
        var written: Int64 = 0
        do {
            let (bytes, response) = try await session.bytes(from: asset.url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                throw MacUpdateError.http((response as? HTTPURLResponse)?.statusCode ?? 0)
            }
            guard isTrustedFinal(http.url, download: true) else { throw MacUpdateError.untrusted }
            var buffer = Data()
            buffer.reserveCapacity(1 << 16)
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count >= 1 << 16 {
                    try handle.write(contentsOf: buffer)
                    hasher.update(data: buffer)
                    written += Int64(buffer.count)
                    buffer.removeAll(keepingCapacity: true)
                    if written > downloadLimit { throw MacUpdateError.tooLarge }
                    if asset.size > 0 { progress(Double(written) / Double(asset.size)) }
                }
            }
            if !buffer.isEmpty {
                try handle.write(contentsOf: buffer)
                hasher.update(data: buffer)
                written += Int64(buffer.count)
            }
            try handle.close()
        } catch {
            try? handle.close()
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
        let actual = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard (asset.size < 0 || written == asset.size), actual == expected else {
            try? FileManager.default.removeItem(at: partial)
            throw MacUpdateError.checksumMismatch
        }
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.moveItem(at: partial, to: target)
        return target
    }
}

enum MacUpdateStatus: Equatable {
    case idle
    case checking
    case upToDate
    case available
    case downloading
    case installing
    case failed(String)
}

/// Settings → Updates and the app menu's Check for Updates…
@MainActor
@Observable
final class MacUpdater {
    static let shared = MacUpdater()

    private enum Key {
        static let channel = "updates.channel"
        static let autoCheck = "updates.autoCheck"
        static let checkHours = "updates.checkHours"
        static let notify = "updates.notify"
        static let lastCheck = "updates.lastCheck"
        static let lastNotified = "updates.lastNotified"
    }

    static let frequencies = [1, 6, 12, 24, 168]

    private(set) var status: MacUpdateStatus = .idle
    private(set) var available: MacRelease?
    private(set) var latest: MacRelease?
    private(set) var progress: Double = 0
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private let defaults = UserDefaults.standard

    /// The channel the user picked, or nil while they haven't.
    private(set) var chosenChannel: MacUpdateChannel?
    var autoCheck: Bool { didSet { defaults.set(autoCheck, forKey: Key.autoCheck) } }
    var checkHours: Int { didSet { defaults.set(checkHours, forKey: Key.checkHours) } }
    var notify: Bool { didSet { defaults.set(notify, forKey: Key.notify) } }
    private(set) var lastCheck: Date?

    private init() {
        let defaults = UserDefaults.standard
        chosenChannel = MacUpdateChannel(rawValue: defaults.string(forKey: Key.channel) ?? "")
        autoCheck = defaults.object(forKey: Key.autoCheck) as? Bool ?? true
        let hours = defaults.integer(forKey: Key.checkHours)
        checkHours = Self.frequencies.contains(hours) ? hours : 24
        notify = defaults.object(forKey: Key.notify) as? Bool ?? true
        let checked = defaults.double(forKey: Key.lastCheck)
        lastCheck = checked > 0 ? Date(timeIntervalSince1970: checked) : nil
    }

    /// The full release version (2.9.0-alpha.27): CI stamps it into Info.plist because the
    /// bundle's short version must be numbers only.
    nonisolated static var installedVersionText: String {
        let info = Bundle.main.infoDictionary ?? [:]
        if let full = info["CodexMeterReleaseVersion"] as? String, MacReleaseVersion(full) != nil { return full }
        return info["CFBundleShortVersionString"] as? String ?? "0.0.0"
    }

    var installed: MacReleaseVersion { MacReleaseVersion(Self.installedVersionText) ?? MacReleaseVersion("0.0.0")! }

    /// Alpha builds follow alpha until the user picks a channel, like Android.
    var channel: MacUpdateChannel {
        get { chosenChannel ?? (installed.isPrerelease ? .alpha : .stable) }
        set {
            guard newValue != channel || chosenChannel == nil else { return }
            chosenChannel = newValue
            defaults.set(newValue.rawValue, forKey: Key.channel)
            available = nil
            latest = nil
            if status != .downloading, status != .installing { status = .idle }
        }
    }

    static func frequencyTitle(_ hours: Int) -> String {
        switch hours {
        case 1: "Hourly"
        case 24: "Daily"
        case 168: "Weekly"
        default: "Every \(hours) hours"
        }
    }

    /// Going back to stable from an alpha build installs an older version, so it's only offered
    /// when the user picked Stable.
    var isReturnToStable: Bool {
        guard let available else { return false }
        return available.version < installed
    }

    /// Why this copy can't replace itself; it opens the verified disk image instead.
    var swapBlockedReason: String? {
        let bundle = Bundle.main.bundleURL
        if ProcessInfo.processInfo.environment["APP_SANDBOX_CONTAINER_ID"] != nil {
            return "This build runs in the App Sandbox, so the update opens as a disk image to drag into Applications."
        }
        guard bundle.pathExtension == "app" else { return "Codex Meter isn't running from an app bundle." }
        let folder = bundle.deletingLastPathComponent().path
        if !FileManager.default.isWritableFile(atPath: folder) || !FileManager.default.isWritableFile(atPath: bundle.path) {
            return "You can't change \(folder) without an administrator, so the update opens as a disk image."
        }
        if bundle.path.hasPrefix("/Volumes/") { return "Codex Meter is running from a disk image. Copy it to Applications first." }
        return nil
    }

    var statusText: String {
        switch status {
        case .checking: "Checking GitHub for updates…"
        case .downloading: "Downloading \(available.map { "\($0.version)" } ?? "") · \(Int(progress * 100))%"
        case .installing: "Verified. Installing the update…"
        case .available where isReturnToStable:
            "Stable \(available!.version) is available. Installing it leaves the alpha channel."
        case .available: "Codex Meter \(available!.version) is available."
        case .upToDate:
            latest == nil
                ? "No \(channel == .alpha ? "alpha" : "stable") release for the Mac yet. You're on \(Self.installedVersionText)."
                : "You're up to date (\(Self.installedVersionText))."
        case .failed(let message): message
        case .idle: lastCheckText
        }
    }

    var lastCheckText: String {
        guard let lastCheck else { return "Version \(Self.installedVersionText)." }
        return "Version \(Self.installedVersionText). Last checked \(lastCheck.formatted(date: .abbreviated, time: .shortened))."
    }

    /// Automatic checks are off for UI tests and smoke launches, which must not reach GitHub.
    private var automaticChecksAllowed: Bool {
        !ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-ui-testing") || $0 == "smoke" }
    }

    /// Called every few minutes while the app runs.
    func tick() async {
        guard autoCheck, automaticChecksAllowed, status != .downloading, status != .installing else { return }
        if let lastCheck, Date().timeIntervalSince(lastCheck) < Double(checkHours) * 3600 { return }
        await check(manual: false)
    }

    func check(manual: Bool) async {
        guard status != .checking, status != .downloading, status != .installing else { return }
        status = .checking
        lastCheck = Date()
        defaults.set(Date().timeIntervalSince1970, forKey: Key.lastCheck)
        do {
            let releases = try await MacReleaseFeed.check(channel)
            let usable = releases.filter { $0.zip != nil && $0.checksums != nil && (channel == .alpha || !$0.prerelease) }
            latest = usable.first
            if let newest = usable.first, newest.version > installed {
                available = newest
            } else if channel == .stable, chosenChannel == .stable, installed.isPrerelease, let stable = usable.first {
                available = stable
            } else {
                available = nil
            }
            status = available == nil ? .upToDate : .available
            if let available, !manual, notify, defaults.string(forKey: Key.lastNotified) != available.tag {
                defaults.set(available.tag, forKey: Key.lastNotified)
                postNotification(available)
            }
        } catch {
            status = .failed(error.localizedDescription)
        }
    }

    func downloadAndInstall() {
        guard let release = available, let sums = release.checksums, task == nil else { return }
        let blocked = swapBlockedReason
        guard let asset = blocked == nil ? release.zip : (release.diskImage ?? release.zip) else { return }
        status = .downloading
        progress = 0
        let report: @Sendable (Double) -> Void = { [weak self] value in
            Task { @MainActor in self?.progress = min(1, value) }
        }
        task = Task { [weak self] in
            let directory = URL.cachesDirectory.appending(path: "CodexMeter/Updates", directoryHint: .isDirectory)
            do {
                let file = try await MacReleaseFeed.download(asset, sums: sums, into: directory, progress: report)
                guard let self else { return }
                self.status = .installing
                if blocked == nil, file.pathExtension == "zip" {
                    try await self.swap(zip: file, directory: directory)
                } else {
                    NSWorkspace.shared.open(file)
                    self.status = .available
                }
            } catch is CancellationError {
                self?.status = .available
            } catch {
                self?.status = .failed(error.localizedDescription)
            }
            self?.task = nil
        }
    }

    func cancel() {
        task?.cancel()
    }

    /// Unzips the verified app next to the download, then hands off to a small script that waits
    /// for this process to quit, swaps the bundle (restoring the old one if the move fails) and
    /// reopens Codex Meter.
    private func swap(zip: URL, directory: URL) async throws {
        let stage = directory.appending(path: "stage", directoryHint: .isDirectory)
        try? FileManager.default.removeItem(at: stage)
        try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: true)
        try await Self.run("/usr/bin/ditto", ["-x", "-k", zip.path, stage.path])
        let newApp = stage.appending(path: "Codex Meter.app", directoryHint: .isDirectory)
        guard let info = NSDictionary(contentsOf: newApp.appending(path: "Contents/Info.plist")),
              info["CFBundleIdentifier"] as? String == Bundle.main.bundleIdentifier,
              FileManager.default.isExecutableFile(atPath: newApp.appending(path: "Contents/MacOS/Codex Meter").path) else {
            throw MacUpdateError.badArchive
        }
        try? await Self.run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", newApp.path])

        let script = directory.appending(path: "apply-update.sh")
        try """
        #!/bin/sh
        pid="$1"; new="$2"; dest="$3"
        while kill -0 "$pid" 2>/dev/null; do sleep 0.5; done
        old="${dest%.app}.previous.app"
        rm -rf "$old"
        if mv "$dest" "$old" && mv "$new" "$dest"; then
          rm -rf "$old"
        elif [ -d "$old" ] && [ ! -d "$dest" ]; then
          mv "$old" "$dest"
        fi
        open "$dest"
        """.write(to: script, atomically: true, encoding: .utf8)
        let helper = Process()
        helper.executableURL = URL(fileURLWithPath: "/bin/sh")
        helper.arguments = [script.path, String(ProcessInfo.processInfo.processIdentifier), newApp.path, Bundle.main.bundleURL.path]
        try helper.run()
        NSApp.terminate(nil)
    }

    private static func run(_ tool: String, _ arguments: [String]) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: tool)
            process.arguments = arguments
            process.terminationHandler = { finished in
                if finished.terminationStatus == 0 {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: MacUpdateError.message("\(tool) failed with status \(finished.terminationStatus)."))
                }
            }
            do { try process.run() } catch { continuation.resume(throwing: error) }
        }
    }

    private func postNotification(_ release: MacRelease) {
        let content = UNMutableNotificationContent()
        content.title = "Codex Meter \(release.version) is available"
        content.body = "Install it from Settings → Updates. The download is checked against the release's SHA-256 checksums."
        content.userInfo = [Self.notificationKey: release.tag]
        let request = UNNotificationRequest(identifier: "update-\(release.tag)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    nonisolated static let notificationKey = "codexmeter.update"
}
