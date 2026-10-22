import CodexMeterCore
import SwiftUI

/// One vertical page per provider tab on the iPhone. The page on screen is the provider the
/// complications follow, so glancing at Claude Code on the watch puts Claude Code on the face.
struct WatchRootView: View {
    @Environment(WatchModel.self) private var model
    @State private var isShowingGallery = false

    var body: some View {
        NavigationStack {
            Group {
                if let payload = model.payload, !payload.providers.isEmpty {
                    TabView(selection: Binding(
                        get: { model.followed?.id ?? payload.providers[0].id },
                        set: { model.follow($0) }
                    )) {
                        ForEach(payload.providers) { provider in
                            ProviderPage(provider: provider)
                                .tag(provider.id)
                        }
                    }
                    .tabViewStyle(.verticalPage)
                } else {
                    WaitingForPhoneView()
                }
            }
            .navigationDestination(isPresented: $isShowingGallery) {
                ComplicationGalleryView()
            }
        }
        .onAppear { isShowingGallery = model.startsInGallery }
    }
}

/// One provider: its first meter as a ring, the next ones as bars, and the soonest reset.
struct ProviderPage: View {
    @Environment(WatchModel.self) private var model
    let provider: WatchProviderSummary

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            content(at: context.date)
        }
        .navigationTitle {
            HStack(spacing: 5) {
                ProviderMark(providerID: provider.id)
                    .frame(width: 14, height: 14)
                    .foregroundStyle(Color.accentColor)
                Text(provider.name)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    model.requestRefresh()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(model.isRefreshing)
                .accessibilityLabel("Refresh from iPhone")
            }
        }
    }

    @ViewBuilder
    private func content(at date: Date) -> some View {
        let meters = provider.currentMeters(at: date)
        if !provider.signedIn {
            MessageView(
                systemImage: "iphone",
                title: "Not connected",
                message: "Sign in to \(provider.name) in Codex Meter on your iPhone."
            )
        } else if let primary = meters.first {
            VStack(spacing: 6) {
                PrimaryRing(meter: primary)
                ForEach(meters.dropFirst().prefix(2)) { meter in
                    MeterBar(meter: meter)
                }
                footer(at: date)
            }
            .padding(.horizontal, 4)
        } else {
            MessageView(
                systemImage: "hourglass",
                title: "No usage yet",
                message: "Open \(provider.name) in Codex Meter on your iPhone to load it."
            )
        }
    }

    @ViewBuilder
    private func footer(at date: Date) -> some View {
        if let error = model.refreshError {
            Text(error)
                .font(.caption2)
                .foregroundStyle(.orange)
                .multilineTextAlignment(.center)
                .lineLimit(2)
        } else if let next = provider.nextReset(after: date) {
            Text("\(next.meter.shortLabel) resets in \(compactCountdown(to: next.date, from: date))")
                .font(.caption2)
                .foregroundStyle(provider.isStale(at: date) ? .orange : .secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
    }
}

private struct PrimaryRing: View {
    let meter: WatchMeter

    var body: some View {
        Gauge(value: Double(meter.remainingPercent), in: 0...100) {
            Text(meter.shortLabel)
        } currentValueLabel: {
            VStack(spacing: -2) {
                Text("\(meter.remainingPercent)%")
                    .font(.system(size: 26, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Text(meter.label)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .gaugeStyle(RingGaugeStyle(tint: meterTint(remaining: meter.remainingPercent)))
        .frame(width: 92, height: 92)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(meter.label), \(meter.remainingPercent) percent left")
    }
}

/// A thick ring like the Activity app, since the stock circular gauge is sized for
/// complications rather than a full screen.
private struct RingGaugeStyle: GaugeStyle {
    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        ZStack {
            Circle()
                .stroke(tint.opacity(0.22), lineWidth: 9)
            Circle()
                .trim(from: 0, to: configuration.value)
                .stroke(tint, style: StrokeStyle(lineWidth: 9, lineCap: .round))
                .rotationEffect(.degrees(-90))
            configuration.currentValueLabel
        }
        .padding(5)
    }
}

private struct MeterBar: View {
    let meter: WatchMeter

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(meter.label)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text("\(meter.remainingPercent)%")
                    .monospacedDigit()
                    .fontWeight(.semibold)
            }
            .font(.footnote)
            ProgressView(value: Double(meter.remainingPercent), total: 100)
                .tint(meterTint(remaining: meter.remainingPercent))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(meter.label), \(meter.remainingPercent) percent left")
    }
}

private struct MessageView: View {
    let systemImage: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.title2)
                .foregroundStyle(Color.accentColor)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 6)
    }
}

private struct WaitingForPhoneView: View {
    var body: some View {
        ScrollView {
            MessageView(
                systemImage: "applewatch.and.arrow.forward",
                title: "Codex Meter",
                message: "Open Codex Meter on your iPhone and sign in. Your providers appear "
                    + "here and on your watch face."
            )
            .padding(.top, 8)
        }
    }
}
