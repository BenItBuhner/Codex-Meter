#if os(iOS)
import ActivityKit
import CodexMeterCore
import SwiftUI
import WidgetKit

/// Live usage on the Lock Screen and in the Dynamic Island: the tightest limit across every
/// connected service, how much is left, and when it resets.
struct LiveUsageActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LiveUsageAttributes.self) { context in
            LiveUsageLockScreenView(state: context.state)
                .padding(16)
                .activityBackgroundTint(nil)
                .widgetURL(URL(string: "codexmeter://priority"))
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    LiveUsageRing(state: state, lineWidth: 5)
                        .frame(width: 44, height: 44)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.title)
                            .font(.headline)
                            .lineLimit(1)
                        Text("\(state.remainingPercent)% left")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let reset = state.resetAt, reset > .now {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("Resets")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text(timerInterval: Date.now...reset, countsDown: true)
                                .font(.caption.monospacedDigit())
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 64)
                        }
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if !state.paceNote.isEmpty {
                        Label(state.paceNote, systemImage: "speedometer")
                            .font(.caption)
                            .foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } compactLeading: {
                LiveUsageRing(state: state, lineWidth: 3)
                    .frame(width: 18, height: 18)
            } compactTrailing: {
                Text("\(state.remainingPercent)%")
                    .font(.caption.bold().monospacedDigit())
                    .foregroundStyle(LiveUsageRing.tint(for: state))
            } minimal: {
                LiveUsageRing(state: state, lineWidth: 3)
                    .frame(width: 18, height: 18)
            }
            .widgetURL(URL(string: "codexmeter://priority"))
            .keylineTint(LiveUsageRing.tint(for: state))
        }
    }
}

private struct LiveUsageLockScreenView: View {
    let state: LiveUsageState

    var body: some View {
        HStack(spacing: 14) {
            LiveUsageRing(state: state, lineWidth: 6)
                .frame(width: 52, height: 52)
                .overlay {
                    Text("\(state.remainingPercent)%")
                        .font(.caption.bold().monospacedDigit())
                }
            VStack(alignment: .leading, spacing: 3) {
                Text(state.title)
                    .font(.headline)
                    .lineLimit(1)
                if let reset = state.resetAt, reset > .now {
                    Text("\(state.remainingPercent)% left · resets \(reset, style: .relative)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text("\(state.remainingPercent)% left")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if !state.paceNote.isEmpty {
                    Text(state.paceNote)
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .lineLimit(1)
                }
                Text("Updated \(state.updatedAt, style: .time)")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct LiveUsageRing: View {
    let state: LiveUsageState
    let lineWidth: CGFloat

    static func tint(for state: LiveUsageState) -> Color {
        if state.remainingPercent <= 10 { return .red }
        if state.remainingPercent <= 25 || state.isFast { return .orange }
        return .green
    }

    var body: some View {
        ZStack {
            Circle()
                .stroke(.primary.opacity(0.15), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: CGFloat(state.remainingPercent) / 100)
                .stroke(Self.tint(for: state), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .accessibilityLabel("\(state.remainingPercent) percent left")
    }
}
#endif
