import CodexMeterCore
import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model
    /// More's tab is in front (its list, or a provider pushed from it).
    @State private var moreSelected = false
    /// The provider More pushed, kept while other tabs are in front, as iOS's More does.
    @State private var morePath: [UsageProvider] = []

    static let moreTag = "more"
    /// Only iPhone collapses tabs into More; iPad's sidebar lists them all.
    static var usesMoreTab: Bool { UIDevice.current.userInterfaceIdiom == .phone }

    var body: some View {
        @Bindable var model = model

        // Stock tab bar with each provider's mark. Only providers turned on in Settings get a
        // tab, and with just one on the bar is hidden. On iPad the same tabs become a sidebar
        // (or the floating top tab bar, which the sidebar button toggles). Tabs that don't fit
        // the floating bar in portrait stay in the sidebar instead of collapsing into a More tab.
        // Priority comes first, as on Android, unless it is turned off in Settings.
        let showsPriority = model.settings.showPriority && model.hasPriorityContent
        let tabCount = model.enabledProviders.count + (showsPriority ? 1 : 0)
        // iPhone's tab bar holds five: with more, the first four keep tabs and the rest go under
        // More, Apple's pushed list. The app draws that More itself: the system's own More nests
        // each provider's NavigationStack inside its navigation controller, which stacked two bars
        // on a provider pushed from it. iPad shows every tab in the sidebar instead.
        let overflow = Self.usesMoreTab && tabCount > 5
        let ownProviders = overflow
            ? Array(model.enabledProviders.prefix(showsPriority ? 3 : 4))
            : model.enabledProviders
        let moreProviders = overflow ? Array(model.enabledProviders.dropFirst(ownProviders.count)) : []
        TabView(selection: Binding(
            get: {
                if overflow && (moreSelected || moreProviders.contains(model.selectedProvider)) {
                    return Self.moreTag
                }
                return model.isPriorityTabSelected && showsPriority ? AppModel.priorityTag : model.selectedProvider.rawValue
            },
            set: { tag in
                if tag == Self.moreTag {
                    moreSelected = true
                    // More shows the provider it had pushed, or its list
                    if let shown = morePath.last {
                        model.select(provider: shown)
                    }
                    return
                }
                moreSelected = false
                if tag == AppModel.priorityTag {
                    model.setPriorityTabSelected(true)
                } else if let provider = UsageProvider(rawValue: tag) {
                    model.select(provider: provider)
                }
            }
        )) {
            if showsPriority {
                Tab("Priority", systemImage: "exclamationmark.circle", value: AppModel.priorityTag) {
                    NavigationStack {
                        PriorityView()
                    }
                    .toolbar(tabCount > 1 ? .visible : .hidden, for: .tabBar)
                }
                .accessibilityIdentifier("tab-priority")
            }
            ForEach(ownProviders) { provider in
                Tab(provider.displayName, image: provider.markImageName, value: provider.rawValue) {
                    NavigationStack {
                        DashboardView(provider: provider)
                    }
                    .toolbar(tabCount > 1 ? .visible : .hidden, for: .tabBar)
                }
            }
            if overflow {
                Tab("More", systemImage: "ellipsis", value: Self.moreTag) {
                    NavigationStack(path: $morePath) {
                        MoreList(providers: moreProviders)
                            .navigationDestination(for: UsageProvider.self) { provider in
                                DashboardView(provider: provider)
                                    .onAppear { model.select(provider: provider) }
                            }
                    }
                }
                .accessibilityIdentifier("tab-more")
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .appHaptics()
        // Claude Code, Cursor and the added providers refresh on their own; keep the Live
        // Activity current when any of them lands.
        .onChange(of: model.priorityProviders.map { model.usageSnapshot(for: $0)?.fetchedAt }) {
            Task { await model.syncLiveActivity() }
        }
        .tabViewSidebarBottomBar {
            Button {
                model.isShowingSettings = true
            } label: {
                Label("Settings", systemImage: "gearshape")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.borderless)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .accessibilityIdentifier("sidebar-settings")
        }
        .sheet(isPresented: $model.isShowingSettings) {
            NavigationStack {
                SettingsView()
            }
        }
        .sheet(isPresented: $model.isShowingReset) {
            NavigationStack {
                ResetCreditView()
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $model.isShowingSignIn) {
            NavigationStack {
                SignInView()
            }
            .interactiveDismissDisabled(model.isAuthenticating)
        }
        .fullScreenCover(isPresented: $model.isShowingOnboarding) {
            OnboardingView()
        }
        // A sheet, like Apple's own welcome screens: swiping it down counts as Continue.
        .sheet(isPresented: $model.isShowingWhatsNew, onDismiss: model.finishWhatsNew) {
            WhatsNewView()
        }
        .preferredColorScheme(model.settings.appearance.colorScheme)
        .onOpenURL { model.handle(url: $0) }
        // Keeps the Apple Watch app and its complications on the same providers and usage.
        .task {
            WatchSyncCoordinator.shared.start { await model.refreshForWatch() }
        }
        .onChange(of: model.watchProviders, initial: true) { _, providers in
            WatchSyncCoordinator.shared.publish(providers: providers,
                                                selectedID: model.selectedProvider.rawValue)
        }
        .onChange(of: model.selectedProvider) { _, selected in
            WatchSyncCoordinator.shared.publish(providers: model.watchProviders,
                                                selectedID: selected.rawValue)
        }
    }
}

/// More's list: a row per provider without its own tab, its mark and name; a row pushes the
/// provider's page with Back to More.
private struct MoreList: View {
    let providers: [UsageProvider]

    var body: some View {
        List(providers) { provider in
            NavigationLink(value: provider) {
                Label {
                    Text(provider.displayName)
                } icon: {
                    ProviderMark(provider: provider)
                        .frame(width: 22, height: 22)
                }
            }
            .accessibilityIdentifier("more-\(provider.rawValue)")
        }
        .listStyle(.plain)
        .navigationTitle("More")
    }
}

#Preview {
    ContentView()
        .environment(AppModel.preview)
}
