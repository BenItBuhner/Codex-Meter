import AppKit
import CodexMeterCore
import SwiftUI

/// The status item: a gauge whose needle follows the tightest allowance of the chosen
/// provider, with its remaining percentage beside it.
struct MenuBarLabel: View {
    @Environment(AppModel.self) private var model
    @AppStorage(MacPreferences.menuBarShowsPercentKey) private var showsPercent = true
    @AppStorage(MacPreferences.menuBarProviderKey) private var providerID = ""

    var body: some View {
        let provider = MacPreferences.menuBarProvider(storedID: providerID, model: model)
        let meter = model.macStatus(for: provider).tightestMeter
        HStack(spacing: 4) {
            Image(systemName: MacMeterLevel.gaugeSymbol(remainingPercent: meter?.remainingPercent))
            if showsPercent, let meter {
                Text("\(meter.remainingPercent)%")
                    .monospacedDigit()
            }
        }
        .accessibilityLabel(
            meter.map { "Codex Meter, \(provider.displayName) \($0.remainingPercent) percent left" }
                ?? "Codex Meter"
        )
        .task {
            await model.startIfNeeded()
        }
    }
}

/// The panel under the status item: every enabled provider's meters at a glance, with
/// refresh, sign-in, and a way into the full window.
struct MenuBarPanel: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 10)

            Divider()

            ScrollView {
                VStack(spacing: 10) {
                    if model.mode == .demo {
                        Label("Demo data — no network requests", systemImage: "sparkles")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(model.enabledProviders) { provider in
                        MenuBarProviderCard(
                            status: model.macStatus(for: provider),
                            onOpen: { open(provider) },
                            onSignIn: { signIn(provider) }
                        )
                    }
                }
                .padding(12)
            }
            .frame(maxHeight: 520)
            .fixedSize(horizontal: false, vertical: true)

            Divider()

            footer
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
        }
        .frame(width: 360)
        .background(.background)
        .task {
            await model.macPanelOpened()
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 26, height: 26)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text("Codex Meter")
                    .font(.headline)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Text(updatedText(now: context.date))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Button {
                Task { await model.macRefreshAll() }
            } label: {
                if model.macIsRefreshingAny {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "arrow.clockwise")
                }
            }
            .buttonStyle(.borderless)
            .help("Refresh all providers")
            .disabled(model.macIsRefreshingAny)
            .accessibilityLabel("Refresh all providers")
            .keyboardShortcut("r")
        }
    }

    private var footer: some View {
        HStack(spacing: 4) {
            Button("Open Codex Meter") {
                openMainWindow()
            }
            .keyboardShortcut("o")
            Spacer()
            Button {
                NSApp.activate()
                openSettings()
            } label: {
                Image(systemName: "gearshape")
            }
            .help("Settings")
            .accessibilityLabel("Settings")
            .keyboardShortcut(",")
            Button {
                NSApp.terminate(nil)
            } label: {
                Image(systemName: "power")
            }
            .help("Quit Codex Meter")
            .accessibilityLabel("Quit Codex Meter")
            .keyboardShortcut("q")
        }
        .buttonStyle(.borderless)
        .controlSize(.regular)
    }

    private func updatedText(now: Date) -> String {
        let dates = model.enabledProviders.compactMap { model.macStatus(for: $0).snapshot?.fetchedAt }
        guard let latest = dates.max() else { return "No usage loaded yet" }
        return UsageFormat.updated(fetchedAt: latest, now: now)
    }

    private func openMainWindow() {
        NSApp.activate()
        openWindow(id: MacSceneID.main)
    }

    private func open(_ provider: UsageProvider) {
        model.select(provider: provider)
        openMainWindow()
    }

    private func signIn(_ provider: UsageProvider) {
        openMainWindow()
        model.macBeginSignIn(provider)
    }
}

private struct MenuBarProviderCard: View {
    let status: MacProviderStatus
    let onOpen: () -> Void
    let onSignIn: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                ProviderMark(provider: status.provider)
                    .frame(width: 16, height: 16)
                    .foregroundStyle(.primary)
                Text(status.provider.displayName)
                    .font(.subheadline.weight(.semibold))
                if !status.plan.isEmpty {
                    PlanBadge(title: status.plan)
                }
                Spacer()
                Button(action: onOpen) {
                    Image(systemName: "arrow.up.forward.app")
                }
                .buttonStyle(.borderless)
                .help("Open \(status.provider.displayName) dashboard")
                .accessibilityLabel("Open \(status.provider.displayName) dashboard")
            }

            if status.meters.isEmpty {
                emptyState
            } else {
                ForEach(status.meters) { meter in
                    MenuBarMeterRow(meter: meter, fetchedAt: status.snapshot?.fetchedAt ?? .now)
                }
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("menu-card-\(status.provider.rawValue)")
    }

    @ViewBuilder
    private var emptyState: some View {
        if !status.isSignedIn && !status.isDemo {
            HStack {
                Text("Not signed in")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Spacer()
                Button(status.provider.signInLabel, action: onSignIn)
                    .controlSize(.small)
            }
        } else {
            Text(status.summary)
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
    }
}

private struct MenuBarMeterRow: View {
    let meter: MacMeter
    let fetchedAt: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(meter.title)
                    .font(.callout)
                Spacer()
                Text("\(meter.remainingPercent)% left")
                    .font(.callout.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(tint)
            }
            ProgressView(value: Double(meter.remainingPercent), total: 100)
                .tint(tint)
                .accessibilityHidden(true)
            TimelineView(.periodic(from: .now, by: 60)) { context in
                let reset = UsageFormat.reset(
                    meter.window,
                    display: .relative,
                    fetchedAt: fetchedAt,
                    now: context.date
                )
                if !reset.isEmpty {
                    Text(reset)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var tint: Color {
        switch MacMeterLevel(remainingPercent: meter.remainingPercent) {
        case .healthy: .accentColor
        case .low: .orange
        case .critical: .red
        }
    }
}
