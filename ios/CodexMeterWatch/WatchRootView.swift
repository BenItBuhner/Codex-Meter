import CodexMeterCore
import SwiftUI

struct WatchRootView: View {
    @Environment(WatchSnapshotStore.self) private var store

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header

                if store.snapshot.mode == .signedOut {
                    ContentUnavailableView(
                        "Not connected",
                        systemImage: "iphone.slash",
                        description: Text("Open Codex Meter on iPhone and sign in or use demo.")
                    )
                    .frame(maxWidth: .infinity)
                } else {
                    meterRow(
                        title: "5-hour",
                        window: store.snapshot.fiveHour
                    )
                    meterRow(
                        title: "Weekly",
                        window: store.snapshot.weekly
                    )

                    HStack {
                        Label(
                            "\(store.snapshot.resetCreditsAvailable ?? 0) credits",
                            systemImage: "bolt.fill"
                        )
                        Spacer()
                        if store.snapshot.freshness == .stale {
                            Text("Cached")
                                .foregroundStyle(.orange)
                        }
                    }
                    .font(.caption2)

                    if let next = nextReset {
                        Label {
                            Text(next, style: .timer)
                                .monospacedDigit()
                        } icon: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }

                Button {
                    store.requestPhoneRefresh()
                } label: {
                    Label("Ask iPhone to refresh", systemImage: "arrow.clockwise")
                }
                .font(.caption2)
            }
            .padding(.horizontal, 4)
        }
        .navigationTitle("Codex")
    }

    private var header: some View {
        HStack {
            Image(systemName: "gauge.with.dots.needle.67percent")
            Text("Codex Meter")
                .font(.headline)
            Spacer()
            if store.snapshot.mode == .demo {
                Text("Demo")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.blue)
            }
        }
    }

    private var nextReset: Date? {
        [store.snapshot.fiveHour?.resetAt, store.snapshot.weekly?.resetAt]
            .compactMap { $0 }
            .filter { $0 > .now }
            .min()
    }

    private func meterRow(title: String, window: UsageWindow?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                    .font(.caption.weight(.semibold))
                Spacer()
                Text(window.map { "\($0.remainingPercent)%" } ?? "—")
                    .font(.title3.bold().monospacedDigit())
            }
            ProgressView(value: Double(window?.remainingPercent ?? 0), total: 100)
                .tint((window?.remainingPercent ?? 100) <= 25 ? .orange : .accentColor)
        }
        .padding(8)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

#Preview {
    WatchRootView()
        .environment(WatchSnapshotStore.shared)
}
