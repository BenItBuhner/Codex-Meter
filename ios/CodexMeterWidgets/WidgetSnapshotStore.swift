import CodexMeterCore
import Foundation

enum WidgetAccountMode: String, Sendable {
    case signedOut
    case live
    case demo
}

enum WidgetSnapshotFreshness: String, Sendable {
    case fresh
    case stale
    case empty
}

struct WidgetUsageWindow: Sendable, Equatable {
    let usedPercent: Double?
    let durationSeconds: TimeInterval?
    let resetsAt: Date?

    init(usedPercent: Double?, durationSeconds: TimeInterval?, resetsAt: Date?) {
        if let usedPercent, usedPercent.isFinite {
            self.usedPercent = min(max(usedPercent, 0), 100)
        } else {
            self.usedPercent = nil
        }
        self.durationSeconds = durationSeconds
        self.resetsAt = resetsAt
    }

    var remainingPercent: Double? {
        usedPercent.map { 100 - $0 }
    }
}

struct WidgetDisplaySnapshot: Sendable, Equatable {
    let version: Int
    let mode: WidgetAccountMode
    let fetchedAt: Date?
    let plan: String?
    let fiveHour: WidgetUsageWindow
    let weekly: WidgetUsageWindow
    let longWindowIsMonthly: Bool
    let creditCount: Int
    let freshness: WidgetSnapshotFreshness
    var provider: UsageProvider = .codex

    /// The short window's name: "5 hours" for Codex and Claude, otherwise from its length.
    var shortWindowTitle: String {
        let seconds = Int64(fiveHour.durationSeconds ?? 18_000)
        if seconds == 18_000 { return "5 hours" }
        return ProviderUsageParsers.durationName(seconds)
    }

    /// Compact form for accessory widgets: "5h", "1d", "3h".
    var shortWindowShortTitle: String {
        let seconds = Int64(fiveHour.durationSeconds ?? 18_000)
        if seconds >= 86_400, seconds % 86_400 == 0 { return "\(seconds / 86_400)d" }
        if seconds >= 3_600 { return "\(seconds / 3_600)h" }
        return "\(max(1, seconds / 60))m"
    }

    /// Header name: the product for Codex, otherwise the provider.
    var headerTitle: String {
        provider == .codex ? "Codex Meter" : provider.displayName
    }

    var longWindowTitle: String {
        longWindowIsMonthly ? "Monthly" : "Weekly"
    }

    var longWindowShortTitle: String {
        longWindowIsMonthly ? "Mo" : "Wk"
    }

    var nextReset: Date? {
        [fiveHour.resetsAt, weekly.resetsAt]
            .compactMap { $0 }
            .filter { $0 > .now }
            .min()
    }

    static let signedOut = WidgetDisplaySnapshot(
        version: 1,
        mode: .signedOut,
        fetchedAt: nil,
        plan: nil,
        fiveHour: .init(usedPercent: nil, durationSeconds: 18_000, resetsAt: nil),
        weekly: .init(usedPercent: nil, durationSeconds: 604_800, resetsAt: nil),
        longWindowIsMonthly: false,
        creditCount: 0,
        freshness: .empty
    )

    static let empty = WidgetDisplaySnapshot(
        version: 1,
        mode: .live,
        fetchedAt: nil,
        plan: nil,
        fiveHour: .init(usedPercent: nil, durationSeconds: 18_000, resetsAt: nil),
        weekly: .init(usedPercent: nil, durationSeconds: 604_800, resetsAt: nil),
        longWindowIsMonthly: false,
        creditCount: 0,
        freshness: .empty
    )

    static let preview = WidgetDisplaySnapshot(
        version: 1,
        mode: .demo,
        fetchedAt: .now.addingTimeInterval(-240),
        plan: "Plus",
        fiveHour: .init(
            usedPercent: 37,
            durationSeconds: 18_000,
            resetsAt: .now.addingTimeInterval(4_120)
        ),
        weekly: .init(
            usedPercent: 62,
            durationSeconds: 604_800,
            resetsAt: .now.addingTimeInterval(234_000)
        ),
        longWindowIsMonthly: false,
        creditCount: 3,
        freshness: .fresh
    )

    static let stalePreview = WidgetDisplaySnapshot(
        version: 1,
        mode: .live,
        fetchedAt: .now.addingTimeInterval(-10_800),
        plan: "Pro",
        fiveHour: .init(
            usedPercent: 81,
            durationSeconds: 18_000,
            resetsAt: .now.addingTimeInterval(900)
        ),
        weekly: .init(
            usedPercent: 45,
            durationSeconds: 604_800,
            resetsAt: .now.addingTimeInterval(340_000)
        ),
        longWindowIsMonthly: false,
        creditCount: 1,
        freshness: .stale
    )
}

/// Reads only the sanitized widget snapshot from the shared App Group container.
/// The extension intentionally has no authentication, Keychain, or networking code.
struct WidgetSnapshotStore: Sendable {
    static let appGroupIdentifier = "group.com.bukovinafilip.CodexMeter"
    static let snapshotFilename = SharedWidgetSnapshot.defaultFileName
    static let staleAfter: TimeInterval = 2 * 60 * 60

    func load(provider: UsageProvider = .codex, now: Date = .now) -> WidgetDisplaySnapshot {
        var snapshot = loadShared(provider: provider, now: now)
        snapshot.provider = provider
        return snapshot
    }

    /// Codex, then every other provider the app has published live or demo data for.
    func availableProviders() -> [UsageProvider] {
        let others = UsageProvider.allCases.filter { provider in
            guard provider != .codex, let shared = decoded(provider) else { return false }
            return shared.mode != .signedOut
        }
        return [.codex] + others
    }

    private func decoded(_ provider: UsageProvider) -> SharedWidgetSnapshot? {
        guard
            let container = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: Self.appGroupIdentifier
            ),
            let data = try? Data(
                contentsOf: container.appending(path: SharedWidgetSnapshot.fileName(for: provider)),
                options: [.mappedIfSafe, .uncached]
            ),
            data.count <= 256 * 1_024
        else { return nil }
        return decodeSharedSnapshot(from: data)
    }

    private func loadShared(provider: UsageProvider, now: Date) -> WidgetDisplaySnapshot {
        let fileManager = FileManager.default
        guard
            let container = fileManager.containerURL(
                forSecurityApplicationGroupIdentifier: Self.appGroupIdentifier
            ),
            let data = try? Data(
                contentsOf: container.appending(path: SharedWidgetSnapshot.fileName(for: provider)),
                options: [.mappedIfSafe, .uncached]
            ),
            data.count <= 256 * 1_024,
            let shared = decodeSharedSnapshot(from: data),
            shared.version == SharedWidgetSnapshot.currentVersion
        else {
            return .signedOut
        }

        return WidgetDisplaySnapshot(shared: shared, now: now)
    }

    private func decodeSharedSnapshot(from data: Data) -> SharedWidgetSnapshot? {
        let isoDecoder = JSONDecoder()
        isoDecoder.dateDecodingStrategy = .iso8601
        if let snapshot = try? isoDecoder.decode(SharedWidgetSnapshot.self, from: data) {
            return snapshot
        }

        let millisecondsDecoder = JSONDecoder()
        millisecondsDecoder.dateDecodingStrategy = .millisecondsSince1970
        if let snapshot = try? millisecondsDecoder.decode(SharedWidgetSnapshot.self, from: data) {
            return snapshot
        }

        return try? JSONDecoder().decode(SharedWidgetSnapshot.self, from: data)
    }
}

private extension WidgetDisplaySnapshot {
    init(shared: SharedWidgetSnapshot, now: Date) {
        let mode = WidgetAccountMode(rawValue: shared.mode.rawValue) ?? .signedOut
        let freshness: WidgetSnapshotFreshness = switch shared.freshness {
        case .fresh:
            if let fetchedAt = shared.fetchedAt {
                now.timeIntervalSince(fetchedAt) <= WidgetSnapshotStore.staleAfter
                    ? .fresh
                    : .stale
            } else {
                .empty
            }
        case .stale: .stale
        case .unavailable: .empty
        }
        self.init(
            version: shared.version,
            mode: mode,
            fetchedAt: shared.fetchedAt,
            plan: shared.planType.isEmpty ? nil : shared.planType,
            fiveHour: Self.window(
                from: shared.fiveHour,
                fetchedAt: shared.fetchedAt,
                defaultDuration: 18_000
            ),
            weekly: Self.window(
                from: shared.longWindow,
                fetchedAt: shared.fetchedAt,
                defaultDuration: shared.longWindowIsMonthly ? 2_592_000 : 604_800
            ),
            longWindowIsMonthly: shared.longWindowIsMonthly,
            creditCount: max(0, shared.resetCreditsAvailable ?? 0),
            freshness: mode == .signedOut ? .empty : freshness
        )
    }

    static func window(
        from window: UsageWindow?,
        fetchedAt: Date?,
        defaultDuration: TimeInterval
    ) -> WidgetUsageWindow {
        guard let window else {
            return WidgetUsageWindow(
                usedPercent: nil,
                durationSeconds: defaultDuration,
                resetsAt: nil
            )
        }

        let effectiveReset = window.resetAt ?? fetchedAt.map {
            $0.addingTimeInterval(TimeInterval(window.resetAfterSeconds))
        }

        return WidgetUsageWindow(
            usedPercent: Double(window.usedPercent),
            durationSeconds: TimeInterval(window.windowSeconds),
            resetsAt: window.showsResetCountdown ? effectiveReset : nil
        )
    }
}
