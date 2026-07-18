import ActivityKit
import CodexMeterCore
import SwiftUI
import WidgetKit

struct UsageLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: UsageLiveMonitorAttributes.self) { context in
            lockScreenView(context: context)
                .widgetURL(URL(string: "codexmeter://dashboard"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("5h")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(percentLabel(context.state.fiveHourRemainingPercent))
                            .font(.title3.bold().monospacedDigit())
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("Week")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(percentLabel(context.state.weeklyRemainingPercent))
                            .font(.title3.bold().monospacedDigit())
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    HStack(spacing: 6) {
                        Image(systemName: "gauge.with.dots.needle.67percent")
                        Text("Codex Meter")
                            .font(.headline)
                        if context.state.isDemo {
                            Text("Demo")
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.blue.opacity(0.2), in: Capsule())
                        }
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        if let next = context.state.nextResetAt, next > .now {
                            Label {
                                Text(next, style: .timer)
                                    .monospacedDigit()
                            } icon: {
                                Image(systemName: "clock.arrow.circlepath")
                            }
                        } else {
                            Text("No upcoming reset")
                        }
                        Spacer()
                        Label("\(context.state.creditCount)", systemImage: "bolt.fill")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "gauge.with.dots.needle.67percent")
            } compactTrailing: {
                Text(percentLabel(context.state.primaryRemainingPercent))
                    .font(.caption.bold().monospacedDigit())
                    .minimumScaleFactor(0.7)
            } minimal: {
                Text(shortPercent(context.state.primaryRemainingPercent))
                    .font(.caption2.bold().monospacedDigit())
            }
            .widgetURL(URL(string: "codexmeter://dashboard"))
        }
    }

    private func lockScreenView(
        context: ActivityViewContext<UsageLiveMonitorAttributes>
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "gauge.with.dots.needle.67percent")
                    .foregroundStyle(.tint)
                Text("Codex Meter")
                    .font(.headline)
                if !context.state.planLabel.isEmpty {
                    Text(context.state.planLabel)
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.tint.opacity(0.14), in: Capsule())
                }
                Spacer()
                if context.state.isDemo {
                    Text("Demo")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.blue)
                } else if context.state.isCached {
                    Text("Cached")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.orange)
                }
            }

            HStack(spacing: 16) {
                meterColumn(title: "5-hour", remaining: context.state.fiveHourRemainingPercent)
                meterColumn(title: "Weekly", remaining: context.state.weeklyRemainingPercent)
            }

            HStack {
                if let next = context.state.nextResetAt, next > .now {
                    Label {
                        Text(next, style: .timer)
                            .monospacedDigit()
                    } icon: {
                        Image(systemName: "clock.arrow.circlepath")
                    }
                    .font(.caption)
                } else {
                    Text("Reset unavailable")
                        .font(.caption)
                }
                Spacer()
                Label("\(context.state.creditCount) credits", systemImage: "bolt.fill")
                    .font(.caption)
            }
            .foregroundStyle(.secondary)
        }
        .padding(16)
        .activityBackgroundTint(Color(.secondarySystemBackground).opacity(0.85))
    }

    private func meterColumn(title: String, remaining: Int?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(percentLabel(remaining))
                .font(.title2.bold().monospacedDigit())
            ProgressView(value: Double(remaining ?? 0), total: 100)
                .tint(remaining.map { $0 <= 25 ? Color.orange : Color.accentColor } ?? .secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func percentLabel(_ value: Int?) -> String {
        guard let value else { return "—" }
        return "\(value)%"
    }

    private func shortPercent(_ value: Int?) -> String {
        guard let value else { return "—" }
        return "\(value)"
    }
}
