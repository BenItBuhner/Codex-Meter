import CodexMeterCore
import SwiftUI

struct UsageMeterCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    // The ring frames a .title2 numeral, so it grows with that text style.
    @ScaledMetric(relativeTo: .title2) private var ringDiameter: CGFloat = 92

    let title: LocalizedStringKey
    let systemImage: String
    let window: UsageWindow?
    let accent: Color
    var fetchedAt: Date = .now
    var pace: UsagePaceAssessment = .unavailable

    private var remaining: Int { window?.remainingPercent ?? 0 }
    private var used: Int { window?.usedPercent ?? 0 }
    // Orange is reserved for the accelerated warning, as on Android; card accents stay
    // off it so the warning tint is visible on every card.
    private var ringAccent: Color { pace.isAccelerated ? .orange : accent }

    var body: some View {
        dynamicTypeSize.rowLayout(spacing: 18) {
            ZStack {
                Circle()
                    .stroke(ringAccent.opacity(0.16), lineWidth: 11)
                Circle()
                    .trim(from: 0, to: Double(remaining) / 100)
                    .stroke(ringAccent, style: StrokeStyle(lineWidth: 11, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .motionAnimation(.snappy, value: remaining)
                VStack(spacing: -2) {
                    Text("\(remaining)")
                        .font(.title2.bold())
                        .numericTextTransition()
                    Text("%")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: ringDiameter, height: ringDiameter)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(title))
            .accessibilityValue(accessibilityValue)

            VStack(alignment: .leading, spacing: 8) {
                Label(title, systemImage: systemImage)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)

                if window != nil {
                    Text("\(used)% used")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .numericTextTransition()
                }

                if let window, window.showsResetCountdown,
                   let resetAt = window.effectiveResetDate(relativeTo: fetchedAt) {
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
                } else if window == nil {
                    Text("Waiting for data")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if let estimate = pace.estimateLabel() {
                    Label(
                        estimate,
                        systemImage: pace.isAccelerated ? "exclamationmark.triangle.fill" : "speedometer"
                    )
                    .font(.caption.weight(pace.isAccelerated ? .semibold : .regular))
                    .foregroundStyle(pace.isAccelerated ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                }
            }
            if !dynamicTypeSize.isAccessibilitySize {
                Spacer(minLength: 0)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
        .cardSurface()
    }

    private var accessibilityValue: String {
        guard window != nil else {
            return "Unavailable"
        }
        var value = "\(remaining) percent remaining, \(used) percent used"
        if let estimate = pace.estimateAccessibilityValue() {
            value += ", \(estimate)"
        }
        if pace.isAccelerated {
            value += ", accelerated usage"
        }
        return value
    }
}
