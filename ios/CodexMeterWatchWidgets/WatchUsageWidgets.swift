import CodexMeterCore
import SwiftUI
import WidgetKit

private let watchAppGroup = "group.com.bukovinafilip.CodexMeter"

struct WatchUsageEntry: TimelineEntry {
    let date: Date
    let snapshot: SharedWidgetSnapshot
}

struct WatchUsageProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchUsageEntry {
        WatchUsageEntry(date: .now, snapshot: previewSnapshot)
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchUsageEntry) -> Void) {
        completion(WatchUsageEntry(date: .now, snapshot: context.isPreview ? previewSnapshot : load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchUsageEntry>) -> Void) {
        completion(Timeline(entries: [WatchUsageEntry(date: .now, snapshot: load())], policy: .after(.now.addingTimeInterval(15 * 60))))
    }

    private func load() -> SharedWidgetSnapshot {
        guard let defaults = UserDefaults(suiteName: watchAppGroup),
              let data = defaults.data(forKey: WatchSyncPayload.watchDefaultsKey),
              let envelope = try? WatchSyncPayload.decodeEnvelope(data)
        else { return .signedOut }
        return envelope.snapshot
    }

    private var previewSnapshot: SharedWidgetSnapshot {
        SharedWidgetSnapshot(
            mode: .demo,
            fetchedAt: .now,
            planType: "plus",
            fiveHour: UsageWindow(usedPercent: 38, windowSeconds: 18_000),
            weekly: UsageWindow(usedPercent: 64, windowSeconds: 604_800),
            resetCreditsAvailable: 2,
            freshness: .fresh
        )
    }
}

struct WatchFiveHourWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeter.Watch.FiveHour", provider: WatchUsageProvider()) { entry in
            WatchSingleUsageView(title: "5h", remaining: entry.snapshot.fiveHour?.remainingPercent)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Codex 5-hour")
        .description("Five-hour allowance remaining.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner])
    }
}

struct WatchWeeklyWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeter.Watch.Weekly", provider: WatchUsageProvider()) { entry in
            WatchSingleUsageView(title: "Wk", remaining: entry.snapshot.weekly?.remainingPercent)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Codex weekly")
        .description("Weekly allowance remaining.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner])
    }
}

struct WatchDualUsageWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CodexMeter.Watch.Dual", provider: WatchUsageProvider()) { entry in
            WatchDualUsageView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Codex allowances")
        .description("Five-hour and weekly allowance for complications and Smart Stack.")
        .supportedFamilies([.accessoryRectangular, .accessoryInline])
    }
}

private struct WatchSingleUsageView: View {
    let title: String
    let remaining: Int?

    var body: some View {
        Gauge(value: Double(remaining ?? 0), in: 0...100) {
            Text(title)
        } currentValueLabel: {
            Text(remaining.map(String.init) ?? "—").monospacedDigit()
        }
        .gaugeStyle(.accessoryCircular)
        .tint((remaining ?? 100) <= 25 ? .orange : .accentColor)
        .accessibilityLabel("\(title) allowance")
        .accessibilityValue(remaining.map { "\($0) percent remaining" } ?? "Unavailable")
    }
}

private struct WatchDualUsageView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchUsageEntry

    var body: some View {
        if family == .accessoryInline {
            Text("5h \(percent(entry.snapshot.fiveHour)) · Wk \(percent(entry.snapshot.weekly))")
        } else {
            VStack(alignment: .leading, spacing: 3) {
                Text("Codex").font(.caption2.weight(.semibold))
                HStack {
                    Label("5h \(percent(entry.snapshot.fiveHour))", systemImage: "clock")
                    Spacer(minLength: 4)
                    Label("Wk \(percent(entry.snapshot.weekly))", systemImage: "calendar")
                }
                .font(.caption2.monospacedDigit())
                if entry.snapshot.freshness != .fresh {
                    Text("Cached").font(.caption2).foregroundStyle(.orange)
                }
            }
        }
    }

    private func percent(_ window: UsageWindow?) -> String {
        window.map { "\($0.remainingPercent)%" } ?? "—"
    }
}
