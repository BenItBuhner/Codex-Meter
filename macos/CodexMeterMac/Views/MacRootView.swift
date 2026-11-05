import CodexMeterCore
import SwiftUI

/// The main window: a sidebar of providers and the selected provider's dashboard, which is
/// the same SwiftUI view the iPhone and iPad apps show.
struct MacRootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openSettings) private var openSettings
    @Environment(\.openWindow) private var openWindow
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var isShowingAddAccount = false

    var body: some View {
        @Bindable var model = model

        NavigationSplitView(columnVisibility: $columnVisibility) {
            Group {
                if MacLaunchOptions.diagnosticDisables("sidebar") {
                    Text("Sidebar")
                } else {
                    MacSidebar()
                }
            }
            .navigationSplitViewColumnWidth(min: 210, ideal: 240, max: 320)
        } detail: {
            NavigationStack {
                if MacLaunchOptions.diagnosticDisables("dashboard") {
                    Text("Dashboard")
                } else {
                    DashboardView(provider: model.selectedProvider)
                }
            }
            // A fresh stack per provider, so a pushed Edit dashboard or history page never
            // carries over to another provider.
            .id(model.selectedProvider)
        }
        .frame(minWidth: 780, minHeight: 560)
        .sheet(isPresented: $model.isShowingReset) {
            NavigationStack {
                ResetCreditView()
            }
            .frame(minWidth: 480, idealWidth: 520, minHeight: 460, idealHeight: 560)
        }
        .sheet(isPresented: $model.isShowingSignIn) {
            NavigationStack {
                SignInView()
            }
            .frame(minWidth: 520, idealWidth: 560, minHeight: 440, idealHeight: 520)
            .interactiveDismissDisabled(model.isAuthenticating)
        }
        // The iOS Add account flow: pick a service, then its sign-in, in one sheet.
        .sheet(isPresented: $isShowingAddAccount) {
            AddAccountFlow()
                .frame(minWidth: 480, idealWidth: 520, minHeight: 520, idealHeight: 620)
        }
        .onReceive(NotificationCenter.default.publisher(for: .codexMeterAddAccount)) { _ in
            isShowingAddAccount = true
        }
        .sheet(isPresented: $model.isShowingOnboarding) {
            OnboardingView()
                .frame(minWidth: 580, idealWidth: 640, minHeight: 640, idealHeight: 720)
        }
        // The shared dashboards ask for Settings with this flag (a sheet on iOS); on the Mac
        // it opens the Settings window instead.
        .onChange(of: model.isShowingSettings, initial: true) { _, isShowing in
            guard isShowing else { return }
            model.isShowingSettings = false
            openSettings()
        }
        .onReceive(NotificationCenter.default.publisher(for: .codexMeterOpenRoute)) { note in
            if let route = note.userInfo?["route"] as? String {
                model.handle(route: route)
            }
        }
        .onOpenURL { model.handle(url: $0) }
        .preferredColorScheme(model.settings.appearance.colorScheme)
        .task {
            await model.startIfNeeded()
            if MacLaunchOptions.opensMenuBarPreview {
                openWindow(id: MacSceneID.menuBarPreview)
            }
            if MacLaunchOptions.opensSettings {
                openSettings()
            }
        }
    }
}

/// Providers with a dashboard, each with its current headroom, plus the account mode.
struct MacSidebar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List(selection: selection) {
            Section("Providers") {
                ForEach(model.enabledProviders) { provider in
                    MacSidebarRow(status: model.macStatus(for: provider))
                        .tag(provider)
                        .accessibilityIdentifier("sidebar-\(provider.rawValue)")
                }
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) {
            MacSidebarFooter()
        }
        .navigationTitle("Codex Meter")
    }

    private var selection: Binding<UsageProvider?> {
        Binding(
            get: { model.selectedProvider },
            set: { provider in
                if let provider {
                    model.select(provider: provider)
                }
            }
        )
    }
}

private struct MacSidebarRow: View {
    let status: MacProviderStatus

    var body: some View {
        HStack(spacing: 10) {
            ProviderMark(provider: status.provider)
                .frame(width: 18, height: 18)
                .foregroundStyle(.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(status.provider.displayName)
                    .lineLimit(1)
                Text(status.summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if let meter = status.tightestMeter {
                MacMeterRing(remainingPercent: meter.remainingPercent)
                    .frame(width: 20, height: 20)
            } else if status.isRefreshing {
                ProgressView()
                    .controlSize(.small)
            }
        }
        .padding(.vertical, 3)
        .accessibilityElement(children: .combine)
    }
}

private struct MacSidebarFooter: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Divider()
            // Like the + at the foot of Mail's and Finder's sidebars.
            Button {
                NotificationCenter.default.post(name: .codexMeterAddAccount, object: nil)
            } label: {
                Label("Add Account", systemImage: "plus")
            }
            .buttonStyle(.borderless)
            .help("Connect another service (⌘N)")
            .accessibilityIdentifier("sidebar-add-account")
            .padding(.horizontal, 14)
            .padding(.top, 2)
            HStack(spacing: 8) {
                Image(systemName: model.mode == .demo ? "sparkles" : "lock.shield")
                    .foregroundStyle(.secondary)
                Text(model.mode == .demo ? "Demo data — no network requests" : "Tokens stay in your Keychain")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 10)
        }
    }
}

/// A small ring that empties as the allowance is used, tinted by how much is left.
struct MacMeterRing: View {
    let remainingPercent: Int
    var lineWidth: CGFloat = 3

    var body: some View {
        ZStack {
            Circle()
                .stroke(.quaternary, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: CGFloat(max(0, min(100, remainingPercent))) / 100)
                .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .accessibilityLabel("\(remainingPercent) percent left")
    }

    private var tint: Color {
        switch MacMeterLevel(remainingPercent: remainingPercent) {
        case .healthy: .accentColor
        case .low: .orange
        case .critical: .red
        }
    }
}
