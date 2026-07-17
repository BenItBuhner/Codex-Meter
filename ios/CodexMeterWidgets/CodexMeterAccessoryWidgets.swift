import SwiftUI
import WidgetKit

private enum AccessoryMetric {
    case fiveHour
    case weekly

    var title: LocalizedStringKey {
        switch self {
        case .fiveHour: "5h"
        case .weekly: "Week"
        }
    }

    func window(in snapshot: WidgetDisplaySnapshot) -> WidgetUsageWindow {
        switch self {
        case .fiveHour: snapshot.fiveHour
        case .weekly: snapshot.weekly
        }
    }
}

struct FiveHourAccessoryWidget: Widget {
    static let kind = "CodexMeter.Accessory.FiveHour"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: AccessoryWidgetTimelineProvider()) { entry in
            AccessoryCircularUsageView(entry: entry, metric: .fiveHour)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Five-hour allowance")
        .description("Your current five-hour Codex allowance.")
        .supportedFamilies([.accessoryCircular])
    }
}

struct WeeklyAccessoryWidget: Widget {
    static let kind = "CodexMeter.Accessory.Weekly"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: AccessoryWidgetTimelineProvider()) { entry in
            AccessoryCircularUsageView(entry: entry, metric: .weekly)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Weekly allowance")
        .description("Your current weekly Codex allowance.")
        .supportedFamilies([.accessoryCircular])
    }
}

struct DualAllowanceAccessoryWidget: Widget {
    static let kind = "CodexMeter.Accessory.Dual"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: AccessoryWidgetTimelineProvider()) { entry in
            DualAccessoryUsageView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Codex allowances")
        .description("Five-hour and weekly usage with locally updating reset timers.")
        .supportedFamilies([.accessoryRectangular, .accessoryInline])
    }
}

private struct AccessoryCircularUsageView: View {
    let entry: AccessoryWidgetEntry
    let metric: AccessoryMetric

    private var window: WidgetUsageWindow {
        metric.window(in: entry.snapshot)
    }

    var body: some View {
        if entry.snapshot.mode == .signedOut {
            Image(systemName: "person.crop.circle.badge.exclamationmark")
                .font(.title2)
                .widgetURL(URL(string: "codexmeter://dashboard"))
                .accessibilityLabel("Sign in to Codex Meter")
        } else {
            Gauge(value: window.usedPercent ?? 0, in: 0...100) {
                Text(metric.title)
            } currentValueLabel: {
                VStack(spacing: 0) {
                    Text(percentText(window.usedPercent, symbol: true))
                        .font(.caption2.bold().monospacedDigit())
                    if let reset = window.resetsAt, reset > .now {
                        Text(reset, style: .timer)
                            .font(.system(size: 7, weight: .medium, design: .monospaced))
                            .lineLimit(1)
                    }
                }
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .widgetAccentable()
            .widgetURL(URL(string: "codexmeter://dashboard"))
            .accessibilityLabel(Text(metric.title))
            .accessibilityValue(percentText(window.usedPercent, symbol: true))
        }
    }
}

private struct DualAccessoryUsageView: View {
    @Environment(\.widgetFamily) private var family
    let entry: AccessoryWidgetEntry

    var body: some View {
        if entry.snapshot.mode == .signedOut {
            Label("Open Codex Meter to sign in", systemImage: "person.crop.circle")
                .widgetURL(URL(string: "codexmeter://dashboard"))
        } else if family == .accessoryInline {
            inlineContent
        } else {
            rectangularContent
        }
    }

    private var inlineContent: some View {
        HStack(spacing: 4) {
            Image(systemName: "gauge.with.dots.needle.33percent")
            Text("5h \(percentText(entry.snapshot.fiveHour.usedPercent, symbol: true))")
            if let reset = entry.snapshot.fiveHour.resetsAt, reset > .now {
                Text(reset, style: .timer).monospacedDigit()
            }
            Text("· W \(percentText(entry.snapshot.weekly.usedPercent, symbol: true))")
            if let reset = entry.snapshot.weekly.resetsAt, reset > .now {
                Text(reset, style: .timer).monospacedDigit()
            }
        }
        .widgetAccentable()
        .widgetURL(URL(string: "codexmeter://dashboard"))
        .accessibilityElement(children: .combine)
    }

    private var rectangularContent: some View {
        VStack(alignment: .leading, spacing: 5) {
            accessoryRow(title: "5 hours", window: entry.snapshot.fiveHour)
            accessoryRow(title: "Weekly", window: entry.snapshot.weekly)
        }
        .widgetURL(URL(string: "codexmeter://dashboard"))
    }

    private func accessoryRow(
        title: LocalizedStringKey,
        window: WidgetUsageWindow
    ) -> some View {
        HStack(spacing: 5) {
            Text(title)
                .fontWeight(.semibold)
                .frame(width: 46, alignment: .leading)
            Gauge(value: window.usedPercent ?? 0, in: 0...100) {
                EmptyView()
            }
            .gaugeStyle(.accessoryLinearCapacity)
            .widgetAccentable()
            Text(percentText(window.usedPercent, symbol: true))
                .font(.caption.monospacedDigit())
                .frame(width: 32, alignment: .trailing)
            if let reset = window.resetsAt, reset > .now {
                Text(reset, style: .timer)
                    .font(.caption2.monospacedDigit())
                    .frame(width: 39, alignment: .trailing)
            } else {
                Text("—")
                    .font(.caption2)
                    .frame(width: 39, alignment: .trailing)
            }
        }
        .font(.caption)
        .accessibilityElement(children: .combine)
    }
}
