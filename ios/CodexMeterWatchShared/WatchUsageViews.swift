import CodexMeterCore
import SwiftUI
import WidgetKit

/// Views shared by the watch app and its complications, so the in-app complication gallery
/// draws exactly what the watch face shows.

/// A provider's mark from `ProviderMarks.xcassets` (template images converted from the
/// Android vectors). Unknown ids fall back to a gauge glyph.
struct ProviderMark: View {
    let providerID: String

    var body: some View {
        if WatchProviderAssets.hasMark(providerID) {
            Image("ProviderMark-\(providerID)")
                .resizable()
                .renderingMode(.template)
                .scaledToFit()
        } else {
            Image(systemName: "gauge.with.dots.needle.50percent")
                .resizable()
                .scaledToFit()
        }
    }
}

enum WatchProviderAssets {
    /// Provider ids with a mark in `ProviderMarks.xcassets`, matching Android's `UsageProvider`.
    static let marks: Set<String> = [
        "amp", "antigravity", "augment", "chutes", "claude_code", "cline", "codebuff", "codex",
        "copilot", "cursor", "devin", "factory", "grok", "kilo", "kimi", "kiro", "minimax",
        "opencode_go", "openrouter", "synthetic", "v0", "warp", "windsurf", "zai",
    ]

    static func hasMark(_ id: String) -> Bool { marks.contains(id) }
}

/// Tint for a meter by how much is left: the app accent, then orange and red as it runs low.
func meterTint(remaining: Int) -> Color {
    if remaining <= 15 { return .red }
    if remaining <= 35 { return .orange }
    return .accentColor
}

/// "2h 14m", "3d 4h" or "12m" until `date`.
func compactCountdown(to date: Date, from now: Date) -> String {
    let minutes = max(1, Int((date.timeIntervalSince(now) / 60).rounded(.up)))
    let days = minutes / 1_440
    let hours = (minutes % 1_440) / 60
    let rest = minutes % 60
    if days > 0 { return hours > 0 ? "\(days)d \(hours)h" : "\(days)d" }
    if hours > 0 { return rest > 0 ? "\(hours)h \(rest)m" : "\(hours)h" }
    return "\(minutes)m"
}

/// The complication shapes the watch supports, mirrored so the app can draw them too.
enum WatchComplicationShape: String, CaseIterable, Identifiable {
    case circular
    case corner
    case rectangular
    case inline

    var id: String { rawValue }
}

/// The main usage complication: the followed provider's first meter (5-hour for Codex) as a
/// ring, with both meters spelled out where there is room.
struct UsageComplicationView: View {
    let provider: WatchProviderSummary?
    let shape: WatchComplicationShape
    let date: Date

    var body: some View {
        switch shape {
        case .circular: circular
        case .corner: corner
        case .rectangular: rectangular
        case .inline: inline
        }
    }

    private var primary: WatchMeter? { provider?.primary(at: date) }
    private var secondary: WatchMeter? { provider?.secondary(at: date) }

    private var circular: some View {
        Gauge(value: Double(primary?.remainingPercent ?? 0), in: 0...100) {
            ProviderMark(providerID: provider?.id ?? "codex")
                .padding(1)
        } currentValueLabel: {
            Text(primary.map { "\($0.remainingPercent)" } ?? "--")
                .font(.system(.body, design: .rounded).weight(.semibold))
                .minimumScaleFactor(0.6)
        }
        .gaugeStyle(.accessoryCircularCapacity)
        .widgetAccentable()
        .accessibilityLabel(accessibilityText)
    }

    private var corner: some View {
        Text(primary.map { "\($0.remainingPercent)%" } ?? "--")
            .font(.system(.title3, design: .rounded).weight(.semibold))
            .widgetCurvesContent()
            .widgetLabel {
                Gauge(value: Double(primary?.remainingPercent ?? 0), in: 0...100) {
                    Text(primary?.shortLabel ?? "")
                } currentValueLabel: {
                    Text(primary?.shortLabel ?? "")
                }
            }
            .accessibilityLabel(accessibilityText)
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                ProviderMark(providerID: provider?.id ?? "codex")
                    .frame(width: 13, height: 13)
                Text(provider?.name ?? "Codex Meter")
                    .font(.headline)
                    .lineLimit(1)
                    .widgetAccentable()
            }
            if let provider, provider.signedIn, primary != nil {
                ForEach([primary, secondary].compactMap { $0 }) { meter in
                    HStack(spacing: 4) {
                        Text(meter.shortLabel)
                            .font(.caption2)
                            .frame(width: 34, alignment: .leading)
                            .lineLimit(1)
                        Gauge(value: Double(meter.remainingPercent), in: 0...100) {
                            EmptyView()
                        }
                        .gaugeStyle(.accessoryLinearCapacity)
                        Text("\(meter.remainingPercent)%")
                            .font(.caption2.monospacedDigit())
                            .frame(width: 30, alignment: .trailing)
                    }
                }
            } else {
                Text(provider?.signedIn == false ? "Sign in on iPhone" : "Open on iPhone to sync")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var inline: some View {
        Text("\(provider?.name ?? "Codex") \(provider?.pairText(at: date) ?? "--")")
    }

    private var accessibilityText: String {
        guard let provider else { return "Codex Meter, waiting for iPhone" }
        return "\(provider.name), \(provider.pairText(at: date)) remaining"
    }
}

/// Countdown to the followed provider's soonest reset.
struct ResetComplicationView: View {
    let provider: WatchProviderSummary?
    let shape: WatchComplicationShape
    let date: Date

    var body: some View {
        let next = provider?.nextReset(after: date)
        switch shape {
        case .circular, .corner:
            Gauge(value: progress(next), in: 0...1) {
                Image(systemName: "arrow.clockwise")
            } currentValueLabel: {
                Text(next.map { compactCountdown(to: $0.date, from: date) } ?? "--")
                    .font(.system(.caption, design: .rounded).weight(.semibold))
                    .minimumScaleFactor(0.5)
            }
            .gaugeStyle(.accessoryCircular)
            .widgetAccentable()
        case .rectangular:
            VStack(alignment: .leading, spacing: 1) {
                Text("Next reset")
                    .font(.headline)
                    .widgetAccentable()
                Text(next.map { "\($0.meter.label) in \(compactCountdown(to: $0.date, from: date))" }
                     ?? "No reset yet")
                    .font(.caption)
                Text(provider?.name ?? "Codex Meter")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        case .inline:
            Text(next.map { "Reset in \(compactCountdown(to: $0.date, from: date))" }
                 ?? "No reset yet")
        }
    }

    private func progress(_ next: (meter: WatchMeter, date: Date)?) -> Double {
        guard let next, next.meter.window.windowSeconds > 0 else { return 0 }
        let total = Double(next.meter.window.windowSeconds)
        let left = next.date.timeIntervalSince(date)
        return min(1, max(0, 1 - left / total))
    }
}
