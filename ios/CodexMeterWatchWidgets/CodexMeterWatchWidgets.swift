import CodexMeterCore
import SwiftUI
import WidgetKit

@main
struct CodexMeterWatchWidgets: WidgetBundle {
    var body: some Widget {
        UsageComplication()
        ResetComplication()
    }
}

struct WatchUsageEntry: TimelineEntry {
    let date: Date
    let provider: WatchProviderSummary?
}

/// Reads the provider the watch app follows from the shared App Group. Entries step every 15
/// minutes so countdowns and windows that reset roll forward between iPhone syncs; the app
/// reloads timelines whenever new data arrives or the followed provider changes.
struct WatchUsageTimeline: TimelineProvider {
    func placeholder(in context: Context) -> WatchUsageEntry {
        WatchUsageEntry(date: .now, provider: WatchSyncPayload.demo().providers.first)
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchUsageEntry) -> Void) {
        let followed = WatchPayloadStore().followed()
        completion(WatchUsageEntry(
            date: .now,
            provider: followed ?? (context.isPreview ? WatchSyncPayload.demo().providers.first : nil)
        ))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchUsageEntry>) -> Void) {
        let provider = WatchPayloadStore().followed()
        let now = Date()
        let entries = (0..<8).map { step in
            WatchUsageEntry(date: now.addingTimeInterval(Double(step) * 15 * 60), provider: provider)
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}

private extension WidgetFamily {
    var shape: WatchComplicationShape {
        switch self {
        case .accessoryCorner: .corner
        case .accessoryRectangular: .rectangular
        case .accessoryInline: .inline
        default: .circular
        }
    }
}

struct UsageComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeterWatchUsage", provider: WatchUsageTimeline()) { entry in
            UsageEntryView(entry: entry)
        }
        .configurationDisplayName("Usage left")
        .description("The provider you last viewed in Codex Meter on your watch.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryRectangular,
                            .accessoryInline])
    }
}

struct ResetComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeterWatchReset", provider: WatchUsageTimeline()) { entry in
            ResetEntryView(entry: entry)
        }
        .configurationDisplayName("Next reset")
        .description("Time until your followed provider's next limit resets.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

private struct UsageEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchUsageEntry

    var body: some View {
        UsageComplicationView(provider: entry.provider, shape: family.shape, date: entry.date)
            .containerBackground(.clear, for: .widget)
    }
}

private struct ResetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchUsageEntry

    var body: some View {
        ResetComplicationView(provider: entry.provider, shape: family.shape, date: entry.date)
            .containerBackground(.clear, for: .widget)
    }
}
