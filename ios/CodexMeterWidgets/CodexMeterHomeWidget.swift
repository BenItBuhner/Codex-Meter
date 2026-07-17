import SwiftUI
import WidgetKit

struct CodexMeterHomeWidget: Widget {
    static let kind = "CodexMeter.Home"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: Self.kind,
            intent: MeterWidgetConfigurationIntent.self,
            provider: MeterWidgetTimelineProvider()
        ) { entry in
            CodexMeterHomeWidgetView(entry: entry)
        }
        .configurationDisplayName("Codex Meter")
        .description("See your five-hour and weekly Codex allowances and reset credits.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

struct CodexMeterHomeWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: MeterWidgetEntry

    private var configuration: MeterWidgetConfigurationIntent {
        entry.configuration
    }

    private var destination: URL {
        if entry.snapshot.mode == .signedOut {
            return URL(string: "codexmeter://dashboard")!
        }
        if entry.snapshot.freshness == .empty {
            return URL(string: "codexmeter://refresh")!
        }
        return configuration.tapAction.url
    }

    var body: some View {
        MeterWidgetContainer(configuration: configuration) {
            Group {
                if entry.snapshot.mode == .signedOut {
                    SignedOutWidgetView()
                } else if entry.snapshot.freshness == .empty {
                    EmptyWidgetView()
                } else {
                    populatedContent
                }
            }
            .widgetURL(destination)
        }
    }

    @ViewBuilder
    private var populatedContent: some View {
        switch family {
        case .systemSmall:
            smallLayout
        case .systemMedium:
            mediumLayout
        case .systemLarge:
            detailedLayout(extraLarge: false)
        case .systemExtraLarge:
            detailedLayout(extraLarge: true)
        default:
            smallLayout
        }
    }

    private var smallLayout: some View {
        VStack(spacing: 8) {
            compactHeader
            HStack(spacing: 9) {
                UsageRing(
                    title: "5 hours",
                    window: entry.snapshot.fiveHour,
                    configuration: configuration,
                    compact: true
                )
                UsageRing(
                    title: "Weekly",
                    window: entry.snapshot.weekly,
                    configuration: configuration,
                    compact: true
                )
            }
        }
    }

    private var mediumLayout: some View {
        VStack(spacing: 8) {
            compactHeader
            HStack(alignment: .top, spacing: 10) {
                UsageRing(
                    title: "5 hours",
                    window: entry.snapshot.fiveHour,
                    configuration: configuration,
                    compact: true
                )
                UsageRing(
                    title: "Weekly",
                    window: entry.snapshot.weekly,
                    configuration: configuration,
                    compact: true
                )
                CountdownMetricTile(
                    date: entry.snapshot.nextReset,
                    title: "Next reset",
                    configuration: configuration
                )
                RoundMetricTile(
                    symbol: "bolt.fill",
                    value: "\(entry.snapshot.creditCount)",
                    title: "Credits",
                    configuration: configuration
                )
            }
        }
    }

    private func detailedLayout(extraLarge: Bool) -> some View {
        VStack(alignment: .leading, spacing: extraLarge ? 16 : 11) {
            detailedHeader

            if extraLarge {
                HStack(spacing: 14) {
                    MeterSurface(configuration: configuration) {
                        BatteryUsageRow(
                            title: "Five-hour allowance",
                            window: entry.snapshot.fiveHour,
                            configuration: configuration
                        )
                    }
                    MeterSurface(configuration: configuration) {
                        BatteryUsageRow(
                            title: "Weekly allowance",
                            window: entry.snapshot.weekly,
                            configuration: configuration
                        )
                    }
                }
            } else {
                MeterSurface(configuration: configuration) {
                    BatteryUsageRow(
                        title: "Five-hour allowance",
                        window: entry.snapshot.fiveHour,
                        configuration: configuration
                    )
                }
                MeterSurface(configuration: configuration) {
                    BatteryUsageRow(
                        title: "Weekly allowance",
                        window: entry.snapshot.weekly,
                        configuration: configuration
                    )
                }
            }

            Spacer(minLength: 0)
            footer
        }
    }

    private var compactHeader: some View {
        HStack(spacing: 6) {
            Image(systemName: "gauge.with.dots.needle.33percent")
                .foregroundStyle(configuration.accent.color)
                .widgetAccentable()
            Text("Codex")
                .font(.caption.weight(.bold))
            Spacer(minLength: 3)
            WidgetStateBadge(
                snapshot: entry.snapshot,
                accent: configuration.accent.color
            )
        }
    }

    private var detailedHeader: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "gauge.with.dots.needle.33percent")
                .foregroundStyle(configuration.accent.color)
                .widgetAccentable()
            Text("Codex Meter")
                .font(.headline)
            if let plan = entry.snapshot.plan {
                Text(plan)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(configuration.accent.color.opacity(0.14), in: Capsule())
            }
            Spacer(minLength: 8)
            WidgetStateBadge(
                snapshot: entry.snapshot,
                accent: configuration.accent.color
            )
        }
    }

    private var footer: some View {
        HStack(spacing: 12) {
            Label {
                if let nextReset = entry.snapshot.nextReset {
                    Text(nextReset, style: .timer)
                        .monospacedDigit()
                } else {
                    Text("Reset unavailable")
                }
            } icon: {
                Image(systemName: "clock.arrow.circlepath")
            }
            Spacer(minLength: 8)
            Label("\(entry.snapshot.creditCount) credits", systemImage: "bolt.fill")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}

