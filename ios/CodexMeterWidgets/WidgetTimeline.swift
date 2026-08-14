import Foundation
import WidgetKit

struct MeterWidgetEntry: TimelineEntry, Sendable {
    let date: Date
    let snapshot: WidgetDisplaySnapshot
    let configuration: MeterWidgetConfigurationIntent
}

struct MeterWidgetTimelineProvider: AppIntentTimelineProvider {
    private let store = WidgetSnapshotStore()

    func placeholder(in context: Context) -> MeterWidgetEntry {
        MeterWidgetEntry(
            date: .now,
            snapshot: .preview,
            configuration: .preview
        )
    }

    func snapshot(
        for configuration: MeterWidgetConfigurationIntent,
        in context: Context
    ) async -> MeterWidgetEntry {
        MeterWidgetEntry(
            date: .now,
            snapshot: context.isPreview ? .preview : store.load(),
            configuration: configuration
        )
    }

    func timeline(
        for configuration: MeterWidgetConfigurationIntent,
        in context: Context
    ) async -> Timeline<MeterWidgetEntry> {
        let now = Date.now
        let snapshot = store.load()
        let entry = MeterWidgetEntry(
            date: now,
            snapshot: snapshot,
            configuration: configuration
        )

        let normalReload = now.addingTimeInterval(
            snapshot.freshness == .stale ? 5 * 60 : 15 * 60
        )
        let resetReload = configuration.allowance
            .nextReset(in: snapshot, after: now)
            .map { $0.addingTimeInterval(10) }
        let reloadDate = resetReload.map { min($0, normalReload) } ?? normalReload
        return Timeline(entries: [entry], policy: .after(reloadDate))
    }
}

struct ConfiguredAccessoryWidgetEntry: TimelineEntry, Sendable {
    let date: Date
    let snapshot: WidgetDisplaySnapshot
    let allowance: WidgetAllowance
}

private func accessoryTimeline(
    snapshot: WidgetDisplaySnapshot,
    allowance: WidgetAllowance,
    now: Date
) -> Timeline<ConfiguredAccessoryWidgetEntry> {
    let normalReload = now.addingTimeInterval(
        snapshot.freshness == .stale ? 5 * 60 : 15 * 60
    )
    let resetReload = allowance
        .nextReset(in: snapshot, after: now)
        .map { $0.addingTimeInterval(10) }
    let reloadDate = resetReload.map { min($0, normalReload) } ?? normalReload
    return Timeline(
        entries: [
            ConfiguredAccessoryWidgetEntry(
                date: now,
                snapshot: snapshot,
                allowance: allowance
            )
        ],
        policy: .after(reloadDate)
    )
}

struct FiveHourAccessoryTimelineProvider: AppIntentTimelineProvider {
    private let store = WidgetSnapshotStore()

    func placeholder(in context: Context) -> ConfiguredAccessoryWidgetEntry {
        ConfiguredAccessoryWidgetEntry(
            date: .now,
            snapshot: .preview,
            allowance: .fiveHour
        )
    }

    func snapshot(
        for configuration: FiveHourAccessoryConfigurationIntent,
        in context: Context
    ) async -> ConfiguredAccessoryWidgetEntry {
        ConfiguredAccessoryWidgetEntry(
            date: .now,
            snapshot: context.isPreview ? .preview : store.load(),
            allowance: configuration.allowance
        )
    }

    func timeline(
        for configuration: FiveHourAccessoryConfigurationIntent,
        in context: Context
    ) async -> Timeline<ConfiguredAccessoryWidgetEntry> {
        let now = Date.now
        let snapshot = store.load()
        return accessoryTimeline(
            snapshot: snapshot,
            allowance: configuration.allowance,
            now: now
        )
    }
}

struct WeeklyAccessoryTimelineProvider: AppIntentTimelineProvider {
    private let store = WidgetSnapshotStore()

    func placeholder(in context: Context) -> ConfiguredAccessoryWidgetEntry {
        ConfiguredAccessoryWidgetEntry(
            date: .now,
            snapshot: .preview,
            allowance: .weekly
        )
    }

    func snapshot(
        for configuration: WeeklyAccessoryConfigurationIntent,
        in context: Context
    ) async -> ConfiguredAccessoryWidgetEntry {
        ConfiguredAccessoryWidgetEntry(
            date: .now,
            snapshot: context.isPreview ? .preview : store.load(),
            allowance: configuration.allowance
        )
    }

    func timeline(
        for configuration: WeeklyAccessoryConfigurationIntent,
        in context: Context
    ) async -> Timeline<ConfiguredAccessoryWidgetEntry> {
        let now = Date.now
        let snapshot = store.load()
        return accessoryTimeline(
            snapshot: snapshot,
            allowance: configuration.allowance,
            now: now
        )
    }
}

struct DualAccessoryTimelineProvider: AppIntentTimelineProvider {
    private let store = WidgetSnapshotStore()

    func placeholder(in context: Context) -> ConfiguredAccessoryWidgetEntry {
        ConfiguredAccessoryWidgetEntry(
            date: .now,
            snapshot: .preview,
            allowance: .both
        )
    }

    func snapshot(
        for configuration: DualAccessoryConfigurationIntent,
        in context: Context
    ) async -> ConfiguredAccessoryWidgetEntry {
        ConfiguredAccessoryWidgetEntry(
            date: .now,
            snapshot: context.isPreview ? .preview : store.load(),
            allowance: configuration.allowance
        )
    }

    func timeline(
        for configuration: DualAccessoryConfigurationIntent,
        in context: Context
    ) async -> Timeline<ConfiguredAccessoryWidgetEntry> {
        let now = Date.now
        let snapshot = store.load()
        return accessoryTimeline(
            snapshot: snapshot,
            allowance: configuration.allowance,
            now: now
        )
    }
}

extension WidgetAllowance {
    func nextReset(
        in snapshot: WidgetDisplaySnapshot,
        after date: Date = .now
    ) -> Date? {
        let candidates: [Date?] = switch self {
        case .both:
            [snapshot.fiveHour.resetsAt, snapshot.weekly.resetsAt]
        case .fiveHour:
            [snapshot.fiveHour.resetsAt]
        case .weekly:
            [snapshot.weekly.resetsAt]
        }
        return candidates.compactMap { $0 }.filter { $0 > date }.min()
    }
}
