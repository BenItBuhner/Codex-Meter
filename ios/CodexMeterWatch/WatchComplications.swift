import CodexMeterCore
import SwiftUI
import WidgetKit

/// Watch face complication widgets (WidgetKit accessory families).
/// Timelines read the last WCSession snapshot from UserDefaults.
///
/// Note: face complications require these widgets to be part of a WidgetKit
/// extension target in some Xcode versions; the same views power previews and
/// can be wired as a watch widget extension later without schema changes.
struct DualUsageComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeter.Watch.Dual", provider: WatchSnapshotProvider()) { entry in
            DualUsageComplicationView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Codex both")
        .description("Five-hour and weekly remaining.")
        .supportedFamilies([.accessoryRectangular, .accessoryCorner])
    }
}

struct FiveHourComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeter.Watch.FiveHour", provider: WatchSnapshotProvider()) { entry in
            SingleUsageComplicationView(title: "5h", remaining: entry.snapshot.fiveHour?.remainingPercent)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Codex 5-hour")
        .description("Five-hour remaining percent.")
        .supportedFamilies([.accessoryCircular, .accessoryInline, .accessoryCorner])
    }
}

struct WeeklyComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeter.Watch.Weekly", provider: WatchSnapshotProvider()) { entry in
            SingleUsageComplicationView(title: "Wk", remaining: entry.snapshot.weekly?.remainingPercent)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Codex weekly")
        .description("Weekly remaining percent.")
        .supportedFamilies([.accessoryCircular, .accessoryInline, .accessoryCorner])
    }
}

struct WatchSnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: SharedWidgetSnapshot
}

struct WatchSnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchSnapshotEntry {
        WatchSnapshotEntry(date: .now, snapshot: .signedOut)
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchSnapshotEntry) -> Void) {
        completion(WatchSnapshotEntry(date: .now, snapshot: loadSnapshot()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchSnapshotEntry>) -> Void) {
        let entry = WatchSnapshotEntry(date: .now, snapshot: loadSnapshot())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(15 * 60))))
    }

    private func loadSnapshot() -> SharedWidgetSnapshot {
        guard let data = UserDefaults.standard.data(forKey: WatchSyncPayload.watchDefaultsKey),
              let snapshot = try? WatchSyncPayload.decode(data)
        else {
            return .signedOut
        }
        return snapshot
    }
}

struct DualUsageComplicationView: View {
    let entry: WatchSnapshotEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Codex")
                .font(.caption2.weight(.semibold))
            HStack {
                Text("5h \(percent(entry.snapshot.fiveHour?.remainingPercent))")
                Spacer(minLength: 2)
                Text("Wk \(percent(entry.snapshot.weekly?.remainingPercent))")
            }
            .font(.caption2.monospacedDigit())
        }
    }

    private func percent(_ value: Int?) -> String {
        value.map { "\($0)%" } ?? "—"
    }
}

struct SingleUsageComplicationView: View {
    let title: String
    let remaining: Int?

    var body: some View {
        VStack(spacing: 1) {
            Text(title)
                .font(.caption2)
            Text(remaining.map { "\($0)" } ?? "—")
                .font(.headline.bold().monospacedDigit())
            Text("%")
                .font(.caption2)
        }
    }
}
