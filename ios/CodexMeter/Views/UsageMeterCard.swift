import CodexMeterCore
import SwiftUI

struct UsageMeterCard: View {
    let title: LocalizedStringKey
    let systemImage: String
    let window: UsageWindow?
    let accent: Color
    var fetchedAt: Date = .now
    var paceEnabled = true
    var paceSensitivity: UsagePace.Sensitivity = .balanced

    private var remaining: Int { window?.remainingPercent ?? 0 }
    private var used: Int { window?.usedPercent ?? 0 }

    private var showsResetCountdown: Bool {
        guard let window, window.usedPercent > 0 else { return false }
        return window.effectiveResetDate(relativeTo: fetchedAt) != nil
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let paceNow = paceEnabled
                ? UsagePace.assess(
                    window: window,
                    observedAt: fetchedAt,
                    now: context.date,
                    sensitivity: paceSensitivity
                )
                : .unavailable

            HStack(spacing: 18) {
                ZStack {
                    Circle()
                        .stroke(accent.opacity(0.16), lineWidth: 11)
                    Circle()
                        .trim(from: 0, to: Double(remaining) / 100)
                        .stroke(
                            paceNow.accelerated ? Color.orange : accent,
                            style: StrokeStyle(lineWidth: 11, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .animation(.snappy, value: remaining)
                        .animation(.snappy, value: paceNow.accelerated)
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
                .accessibilityValue(accessibilityValue(pace: paceNow))

                VStack(alignment: .leading, spacing: 8) {
                    Label(title, systemImage: systemImage)
                        .font(.headline)

                    if window != nil {
                        Text("\(used)% used")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText())
                    }

                    if paceNow.available {
                        Label {
                            Text(UsageFormat.estimatedRemaining(paceNow))
                        } icon: {
                            Image(systemName: paceNow.accelerated
                                  ? "flame.fill"
                                  : "speedometer")
                        }
                        .font(.subheadline.weight(paceNow.accelerated ? .semibold : .regular))
                        .foregroundStyle(paceNow.accelerated ? .orange : .secondary)
                    }

                    if showsResetCountdown, let resetAt = window?.effectiveResetDate(relativeTo: fetchedAt) {
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
                    } else if window?.usedPercent == 0 {
                        Text("Unused this window")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Reset time unavailable")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(AppChrome.cardPadding)
            .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
            .cardSurface()
            .overlay {
                if paceNow.accelerated {
                    RoundedRectangle(cornerRadius: AppChrome.cardRadius, style: .continuous)
                        .stroke(Color.orange.opacity(0.35), lineWidth: 1)
                }
            }
        }
    }

    private func accessibilityValue(pace: UsagePace.Assessment) -> String {
        guard window != nil else {
            return "Unavailable"
        }
        var parts = ["\(remaining) percent remaining, \(used) percent used"]
        if pace.available {
            parts.append(UsageFormat.estimatedRemaining(pace))
            if pace.accelerated {
                parts.append("accelerated usage")
            }
        }
        return parts.joined(separator: ", ")
    }
}
