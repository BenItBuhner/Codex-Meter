import CodexMeterCore
import SwiftUI
import WidgetKit

private enum AccessoryMetric {
    case fiveHour
    case weekly

    func title(in snapshot: WidgetDisplaySnapshot) -> LocalizedStringKey {
        switch self {
        case .fiveHour: LocalizedStringKey(snapshot.shortWindowShortTitle)
        case .weekly: LocalizedStringKey(snapshot.longWindowIsMonthly ? "Month" : "Week")
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
        AppIntentConfiguration(
            kind: Self.kind,
            intent: FiveHourAccessoryConfigurationIntent.self,
            provider: FiveHourAccessoryTimelineProvider()
        ) { entry in
            ConfiguredAccessoryUsageView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Five-hour allowance")
        .description("A configurable circular allowance dial for Codex or another connected provider.")
        .supportedFamilies([.accessoryCircular])
    }
}

struct WeeklyAccessoryWidget: Widget {
    static let kind = "CodexMeter.Accessory.Weekly"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: Self.kind,
            intent: WeeklyAccessoryConfigurationIntent.self,
            provider: WeeklyAccessoryTimelineProvider()
        ) { entry in
            ConfiguredAccessoryUsageView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Weekly allowance")
        .description("A configurable circular allowance dial for Codex or another connected provider.")
        .supportedFamilies([.accessoryCircular])
    }
}

struct DualAllowanceAccessoryWidget: Widget {
    static let kind = "CodexMeter.Accessory.Dual"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: Self.kind,
            intent: DualAccessoryConfigurationIntent.self,
            provider: DualAccessoryTimelineProvider()
        ) { entry in
            ConfiguredAccessoryUsageView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Allowances")
        .description("Both allowance windows or one focused metric with locally updating reset timers.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

private struct ConfiguredAccessoryUsageView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ConfiguredAccessoryWidgetEntry

    @ViewBuilder
    var body: some View {
        if family == .accessoryCircular {
            switch entry.allowance {
            case .both:
                DualAccessoryCircularUsageView(snapshot: entry.snapshot)
            case .fiveHour:
                AccessoryCircularUsageView(snapshot: entry.snapshot, metric: .fiveHour)
            case .weekly:
                AccessoryCircularUsageView(snapshot: entry.snapshot, metric: .weekly)
            }
        } else {
            DualAccessoryUsageView(
                snapshot: entry.snapshot,
                allowance: entry.allowance
            )
        }
    }
}

private struct AccessoryCircularUsageView: View {
    let snapshot: WidgetDisplaySnapshot
    let metric: AccessoryMetric

    private var window: WidgetUsageWindow {
        metric.window(in: snapshot)
    }

    var body: some View {
        if snapshot.mode == .signedOut {
            Image(systemName: "person.crop.circle.badge.exclamationmark")
                .font(.title2)
                .widgetURL(URL(string: "codexmeter://dashboard"))
                .accessibilityLabel("Sign in to Codex Meter")
        } else {
            Gauge(value: window.usedPercent ?? 0, in: 0...100) {
                Text(metric.title(in: snapshot))
            } currentValueLabel: {
                VStack(spacing: 0) {
                    Text(percentText(window.usedPercent, symbol: true))
                        .font(.caption2.bold().monospacedDigit())
                    if let reset = window.resetsAt, reset > .now {
                        Text(reset, style: .timer)
                            .font(.system(size: 7, weight: .medium, design: .monospaced))
                            .minimumScaleFactor(0.8)
                            .lineLimit(1)
                    }
                }
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .widgetAccentable()
            .widgetURL(URL(string: "codexmeter://dashboard"))
            .accessibilityLabel(Text(metric.title(in: snapshot)))
            .accessibilityValue(percentText(window.usedPercent, symbol: true))
        }
    }
}

private struct DualAccessoryCircularUsageView: View {
    let snapshot: WidgetDisplaySnapshot

    var body: some View {
        if snapshot.mode == .signedOut {
            Image(systemName: "person.crop.circle.badge.exclamationmark")
                .font(.title2)
                .widgetURL(URL(string: "codexmeter://dashboard"))
                .accessibilityLabel("Sign in to Codex Meter")
        } else {
            // Weekly leads on the outer ring; the five-hour window sits inside it as a
            // thinner, lighter ring of the same tint.
            ZStack {
                Circle()
                    .stroke(.primary.opacity(0.18), lineWidth: 4)
                Circle()
                    .trim(from: 0, to: progress(snapshot.weekly))
                    .stroke(
                        .primary,
                        style: StrokeStyle(lineWidth: 4, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .widgetAccentable()

                Circle()
                    .stroke(.primary.opacity(0.10), lineWidth: 3)
                    .padding(8)
                Circle()
                    .trim(from: 0, to: progress(snapshot.fiveHour))
                    .stroke(
                        .secondary,
                        style: StrokeStyle(lineWidth: 3, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .padding(8)
                    .widgetAccentable()

                VStack(spacing: -1) {
                    Text("\(snapshot.longWindowIsMonthly ? "M" : "W") \(percentText(snapshot.weekly.usedPercent, symbol: false))")
                    Text("\(snapshot.shortWindowShortTitle) \(percentText(snapshot.fiveHour.usedPercent, symbol: false))")
                        .foregroundStyle(.secondary)
                }
                .font(.system(size: 8, weight: .bold, design: .rounded))
                .minimumScaleFactor(0.7)
                .lineLimit(1)
            }
            .padding(2)
            .widgetURL(URL(string: "codexmeter://dashboard"))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Both allowance windows")
            .accessibilityValue(
                "\(snapshot.longWindowTitle) \(percentText(snapshot.weekly.usedPercent, symbol: true)) used, \(snapshot.shortWindowTitle.lowercased()) \(percentText(snapshot.fiveHour.usedPercent, symbol: true)) used"
            )
        }
    }

    private func progress(_ window: WidgetUsageWindow) -> CGFloat {
        CGFloat(min(max((window.usedPercent ?? 0) / 100, 0), 1))
    }
}

private struct DualAccessoryUsageView: View {
    @Environment(\.widgetFamily) private var family
    let snapshot: WidgetDisplaySnapshot
    let allowance: WidgetAllowance

    var body: some View {
        if snapshot.mode == .signedOut {
            Label("Open Codex Meter to sign in", systemImage: "person.crop.circle")
                .widgetURL(URL(string: "codexmeter://dashboard"))
        } else if family == .accessoryInline {
            inlineContent
        } else {
            rectangularContent
        }
    }

    @ViewBuilder
    private var inlineContent: some View {
        Group {
            switch allowance {
            case .both:
                HStack(spacing: 4) {
                    Image(systemName: "gauge.with.dots.needle.33percent")
                    Text("\(snapshot.longWindowIsMonthly ? "M" : "W") \(percentText(snapshot.weekly.usedPercent, symbol: true))")
                    if let reset = snapshot.weekly.resetsAt, reset > .now {
                        Text(reset, style: .timer).monospacedDigit()
                    }
                    Text("· \(snapshot.shortWindowShortTitle) \(percentText(snapshot.fiveHour.usedPercent, symbol: true))")
                    if let reset = snapshot.fiveHour.resetsAt, reset > .now {
                        Text(reset, style: .timer).monospacedDigit()
                    }
                }
            case .fiveHour:
                inlineMetric(.fiveHour)
            case .weekly:
                inlineMetric(.weekly)
            }
        }
        .widgetAccentable()
        .widgetURL(URL(string: "codexmeter://dashboard"))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var rectangularContent: some View {
        Group {
            switch allowance {
            case .both:
                VStack(alignment: .leading, spacing: 5) {
                    accessoryRow(title: LocalizedStringKey(snapshot.longWindowTitle), window: snapshot.weekly)
                    accessoryRow(title: LocalizedStringKey(snapshot.shortWindowTitle), window: snapshot.fiveHour)
                }
            case .fiveHour:
                accessoryRow(title: LocalizedStringKey(snapshot.shortWindowTitle), window: snapshot.fiveHour)
                    .frame(maxHeight: .infinity, alignment: .center)
            case .weekly:
                accessoryRow(title: LocalizedStringKey(snapshot.longWindowTitle), window: snapshot.weekly)
                    .frame(maxHeight: .infinity, alignment: .center)
            }
        }
        .widgetURL(URL(string: "codexmeter://dashboard"))
    }

    private func inlineMetric(_ metric: AccessoryMetric) -> some View {
        let window = metric.window(in: snapshot)
        return HStack(spacing: 4) {
            Image(systemName: "gauge.with.dots.needle.33percent")
            switch metric {
            case .fiveHour:
                Text("\(snapshot.shortWindowShortTitle) \(percentText(window.usedPercent, symbol: true))")
            case .weekly:
                Text("\(snapshot.longWindowIsMonthly ? "M" : "W") \(percentText(window.usedPercent, symbol: true))")
            }
            if let reset = window.resetsAt, reset > .now {
                Text(reset, style: .timer).monospacedDigit()
            }
        }
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
