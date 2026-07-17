import SwiftUI
import WidgetKit

struct MeterWidgetContainer<Content: View>: View {
    let configuration: MeterWidgetConfigurationIntent
    @ViewBuilder let content: Content

    var body: some View {
        content
            .modifier(WidgetColorSchemeModifier(appearance: configuration.appearance))
            .containerBackground(for: .widget) {
                WidgetBackground(
                    accent: configuration.accent.color,
                    surfaceOpacity: configuration.surfaceOpacity.value
                )
                .modifier(WidgetColorSchemeModifier(appearance: configuration.appearance))
            }
    }
}

private struct WidgetBackground: View {
    let accent: Color
    let surfaceOpacity: Double

    var body: some View {
        ZStack {
            Color(.systemBackground)
            LinearGradient(
                colors: [accent.opacity(0.14), accent.opacity(0.025)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
        .opacity(surfaceOpacity)
    }
}

private struct WidgetColorSchemeModifier: ViewModifier {
    let appearance: WidgetAppearance

    @ViewBuilder
    func body(content: Content) -> some View {
        switch appearance {
        case .system:
            content
        case .light:
            content.environment(\.colorScheme, .light)
        case .dark:
            content.environment(\.colorScheme, .dark)
        }
    }
}

struct MeterSurface<Content: View>: View {
    let configuration: MeterWidgetConfigurationIntent
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(8)
            .background {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(.secondarySystemBackground).opacity(configuration.surfaceOpacity.value))
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(
                                configuration.accent.color.opacity(
                                    configuration.surfaceOpacity.value * 0.16
                                )
                            )
                    }
            }
    }
}

struct UsageRing: View {
    let title: LocalizedStringKey
    let window: WidgetUsageWindow
    let configuration: MeterWidgetConfigurationIntent
    var compact = false

    private var displayPercent: Double? {
        switch configuration.usageDisplay {
        case .used: window.usedPercent
        case .remaining: window.remainingPercent
        }
    }

    private var progress: Double {
        (displayPercent ?? 0) / 100
    }

    var body: some View {
        VStack(spacing: compact ? 3 : 6) {
            ZStack {
                Circle()
                    .stroke(.primary.opacity(0.10), lineWidth: compact ? 6 : 8)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(
                        configuration.accent.color,
                        style: StrokeStyle(
                            lineWidth: compact ? 6 : 8,
                            lineCap: .round
                        )
                    )
                    .rotationEffect(.degrees(-90))
                    .widgetAccentable()
                Text(percentText(displayPercent, symbol: configuration.showsPercentSymbol))
                    .font(compact ? .caption2.bold() : .headline.bold())
                    .minimumScaleFactor(0.65)
                    .lineLimit(1)
            }
            .aspectRatio(1, contentMode: .fit)

            Text(title)
                .font(compact ? .caption2 : .caption)
                .fontWeight(.semibold)
                .lineLimit(1)

            if !compact, let resetsAt = window.resetsAt, resetsAt > .now {
                Text(resetsAt, style: .timer)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(title))
        .accessibilityValue(
            Text(accessibilityPercent(displayPercent, display: configuration.usageDisplay))
        )
    }
}

struct BatteryUsageRow: View {
    let title: LocalizedStringKey
    let window: WidgetUsageWindow
    let configuration: MeterWidgetConfigurationIntent

    private var displayPercent: Double? {
        switch configuration.usageDisplay {
        case .used: window.usedPercent
        case .remaining: window.remainingPercent
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                Text(percentText(displayPercent, symbol: configuration.showsPercentSymbol))
                    .font(.headline.monospacedDigit())
            }

            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(.primary.opacity(0.10))
                    Capsule()
                        .fill(configuration.accent.color)
                        .frame(
                            width: proxy.size.width * min(max((displayPercent ?? 0) / 100, 0), 1)
                        )
                        .widgetAccentable()
                }
            }
            .frame(height: 11)

            HStack {
                Text(configuration.usageDisplay == .used ? "Used" : "Remaining")
                Spacer(minLength: 8)
                if let resetsAt = window.resetsAt, resetsAt > .now {
                    Label {
                        Text(resetsAt, style: .timer)
                            .monospacedDigit()
                    } icon: {
                        Image(systemName: "arrow.clockwise")
                    }
                } else {
                    Text("Reset unavailable")
                }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

struct RoundMetricTile: View {
    let symbol: String
    let value: String
    let title: LocalizedStringKey
    let configuration: MeterWidgetConfigurationIntent

    var body: some View {
        VStack(spacing: 5) {
            ZStack {
                Circle()
                    .fill(configuration.accent.color.opacity(0.16))
                    .widgetAccentable()
                VStack(spacing: 1) {
                    Image(systemName: symbol)
                        .font(.caption2)
                    Text(value)
                        .font(.caption2.bold().monospacedDigit())
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                }
            }
            .aspectRatio(1, contentMode: .fit)
            Text(title)
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

struct CountdownMetricTile: View {
    let date: Date?
    let title: LocalizedStringKey
    let configuration: MeterWidgetConfigurationIntent

    var body: some View {
        VStack(spacing: 5) {
            ZStack {
                Circle()
                    .fill(configuration.accent.color.opacity(0.16))
                    .widgetAccentable()
                VStack(spacing: 1) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.caption2)
                    if let date, date > .now {
                        Text(date, style: .timer)
                            .font(.caption2.bold().monospacedDigit())
                            .minimumScaleFactor(0.5)
                            .lineLimit(1)
                    } else {
                        Text("—")
                            .font(.caption2.bold())
                    }
                }
                .padding(4)
            }
            .aspectRatio(1, contentMode: .fit)
            Text(title)
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

struct WidgetStateBadge: View {
    let snapshot: WidgetDisplaySnapshot
    let accent: Color

    var body: some View {
        if snapshot.mode == .demo {
            Label("Demo", systemImage: "sparkles")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(accent)
                .widgetAccentable()
        } else if snapshot.freshness == .stale {
            Label("Cached", systemImage: "wifi.slash")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}

struct SignedOutWidgetView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "gauge.with.dots.needle.33percent")
                .font(.title)
                .symbolRenderingMode(.hierarchical)
                .widgetAccentable()
            Text("Codex Meter")
                .font(.headline)
            Text("Sign in or explore demo")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens Codex Meter")
    }
}

struct EmptyWidgetView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "arrow.clockwise.circle")
                .font(.title2)
                .widgetAccentable()
            Text("No usage yet")
                .font(.headline)
            Text("Open the app to refresh")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

func percentText(_ value: Double?, symbol: Bool) -> String {
    guard let value else { return "—" }
    return "\(Int(value.rounded()))\(symbol ? "%" : "")"
}

private func accessibilityPercent(
    _ value: Double?,
    display: WidgetUsageDisplay
) -> String {
    guard let value else { return String(localized: "Unavailable") }
    let suffix = display == .used
        ? String(localized: "percent used")
        : String(localized: "percent remaining")
    return "\(Int(value.rounded())) \(suffix)"
}

func shortResetText(_ date: Date?) -> String {
    guard let date, date > .now else { return "—" }
    let interval = date.timeIntervalSinceNow
    if interval < 3_600 {
        return "\(max(1, Int(interval / 60)))m"
    }
    if interval < 86_400 {
        return "\(max(1, Int(interval / 3_600)))h"
    }
    return "\(max(1, Int(interval / 86_400)))d"
}
