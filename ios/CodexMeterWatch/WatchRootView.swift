import CodexMeterCore
import SwiftUI

struct WatchRootView: View {
    @Environment(WatchSnapshotStore.self) private var store

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header

                Label(
                    store.phoneReachable ? "iPhone connected" : "iPhone unavailable · cached data",
                    systemImage: store.phoneReachable ? "iphone.radiowaves.left.and.right" : "iphone.slash"
                )
                .font(.caption2)
                .foregroundStyle(store.phoneReachable ? Color.secondary : Color.orange)

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
                        if store.isStale() {
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
                    if let expiry = store.nextCreditExpiry, expiry > .now {
                        Label("Credit expires \(expiry, style: .relative)", systemImage: "calendar.badge.clock")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Button {
                    store.requestPhoneRefresh()
                } label: {
                    Label("Ask iPhone to refresh", systemImage: "arrow.clockwise")
                }
                .font(.caption2)
                .disabled(!store.phoneReachable)
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
        [store.snapshot.fiveHour, store.snapshot.weekly]
            .compactMap { $0?.effectiveResetDate(relativeTo: store.snapshot.fetchedAt ?? .now) }
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
