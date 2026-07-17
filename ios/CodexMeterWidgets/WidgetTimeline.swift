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
        let resetReload = snapshot.nextReset.map { $0.addingTimeInterval(10) }
        let reloadDate = resetReload.map { min($0, normalReload) } ?? normalReload
        return Timeline(entries: [entry], policy: .after(reloadDate))
    }
}

struct AccessoryWidgetEntry: TimelineEntry, Sendable {
    let date: Date
    let snapshot: WidgetDisplaySnapshot
}

struct AccessoryWidgetTimelineProvider: TimelineProvider {
    private let store = WidgetSnapshotStore()

    func placeholder(in context: Context) -> AccessoryWidgetEntry {
        AccessoryWidgetEntry(date: .now, snapshot: .preview)
    }

    func getSnapshot(
        in context: Context,
        completion: @escaping (AccessoryWidgetEntry) -> Void
    ) {
        completion(
            AccessoryWidgetEntry(
                date: .now,
                snapshot: context.isPreview ? .preview : store.load()
            )
        )
    }

    func getTimeline(
        in context: Context,
        completion: @escaping (Timeline<AccessoryWidgetEntry>) -> Void
    ) {
        let now = Date.now
        let snapshot = store.load()
        let normalReload = now.addingTimeInterval(
            snapshot.freshness == .stale ? 5 * 60 : 15 * 60
        )
        let resetReload = snapshot.nextReset.map { $0.addingTimeInterval(10) }
        let reloadDate = resetReload.map { min($0, normalReload) } ?? normalReload
        completion(
            Timeline(
                entries: [AccessoryWidgetEntry(date: now, snapshot: snapshot)],
                policy: .after(reloadDate)
            )
        )
    }
}

