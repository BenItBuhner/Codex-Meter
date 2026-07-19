import CodexMeterCore
import SwiftUI

struct UsageMeterCard: View {
    let title: LocalizedStringKey
    let systemImage: String
    let window: UsageWindow?
    let accent: Color
    var fetchedAt: Date = .now
    var pace: UsagePaceAssessment?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var remaining: Int { window?.remainingPercent ?? 0 }
    private var used: Int { window?.usedPercent ?? 0 }

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18))
            : AnyLayout(HStackLayout(spacing: 18))
        layout {
            ZStack {
                Circle()
                    .stroke(accent.opacity(0.16), lineWidth: 11)
                Circle()
                    .trim(from: 0, to: Double(remaining) / 100)
                    .stroke(accent, style: StrokeStyle(lineWidth: 11, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : .snappy, value: remaining)
                VStack(spacing: -2) {
                    Text("\(remaining)")
                        .font(.title2.bold())
                        .contentTransition(.numericText())
                    Text("%")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 92, height: 92)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(title))
            .accessibilityValue(accessibilityValue)

            VStack(alignment: .leading, spacing: 8) {
                Label(title, systemImage: systemImage)
                    .font(.headline)

                if window != nil {
                    Text("\(used)% used")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .contentTransition(.numericText())
                }

                if let window, let resetAt = window.effectiveResetDate(relativeTo: fetchedAt) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Resets in")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(resetAt, style: .relative)
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                        Text(resetAt, format: .dateTime.weekday(.abbreviated).hour().minute())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text(window == nil ? "Waiting for data" : "Reset time unavailable")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if let pace, let exhaustion = pace.estimatedExhaustionAt {
                    Label {
                        Text(
                            pace.isAccelerated
                                ? "Projected empty \(UsageFormat.relative(until: exhaustion))"
                                : "Current pace lasts through reset"
                        )
                    } icon: {
                        Image(systemName: pace.isAccelerated ? "speedometer" : "chart.line.uptrend.xyaxis")
                    }
                    .font(.caption.weight(pace.isAccelerated ? .semibold : .regular))
                    .foregroundStyle(pace.isAccelerated ? .orange : .secondary)
                    .accessibilityLabel(
                        pace.isAccelerated
                            ? "Projected allowance exhaustion \(UsageFormat.relative(until: exhaustion))"
                            : "Current usage pace lasts through reset"
                    )
                }
            }
            Spacer(minLength: 0)
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
        .cardSurface()
    }

    private var accessibilityValue: String {
        guard window != nil else {
            return "Unavailable"
        }
        return "\(remaining) percent remaining, \(used) percent used"
    }
}
