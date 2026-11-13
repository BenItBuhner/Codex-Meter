import CodexMeterCore
import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.drawsBarsInSimulatedRail) private var drawsBarsInSimulatedRail
    /// The provider a window was opened for with Open in New Window, if any.
    var openedProvider: UsageProvider? = nil

    // Each iPad window keeps its own tab; `model.selectedProvider` follows the window in front,
    // so commands, refresh-on-foreground and the watch act on what the person is looking at.
    @State private var windowID = UUID()
    @State private var selection: UsageProvider?
    @SceneStorage("selectedProvider") private var storedProvider: String?
    private let windows = WindowRegistry.shared

    private var currentSelection: UsageProvider {
        UsageProvider.visibleSelection(selection ?? model.selectedProvider, enabled: model.enabledProviders)
    }

    private var anySheetOpen: Bool {
        model.isShowingSettings || model.isShowingReset || model.isShowingSignIn || model.isShowingOnboarding
    }

    /// A model sheet flag, shown only in the window that owns the sheets.
    private func presented(_ flag: ReferenceWritableKeyPath<AppModel, Bool>) -> Binding<Bool> {
        Binding(
            get: { model[keyPath: flag] && windows.presentsSheets(in: windowID) },
            set: { model[keyPath: flag] = $0 }
        )
    }

    /// UI tests that reset settings start every window on the default tab, even one iPadOS
    /// restores from an earlier test.
    private static let restoresWindowTabs =
        !ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-settings")

    private func choose(_ provider: UsageProvider) {
        selection = provider
        storedProvider = provider.rawValue
        model.select(provider: provider)
    }

    var body: some View {
        // Stock tab bar with each provider's mark. Only providers turned on in Settings get a
        // tab, and with just one on the bar is hidden. On iPad the same tabs become a sidebar
        // (or the floating top tab bar, which the sidebar button toggles), so every provider
        // stays one tap away instead of collapsing into a More tab.
        TabView(selection: Binding(
            get: { currentSelection },
            set: { choose($0) }
        )) {
            ForEach(model.enabledProviders) { provider in
                Tab(provider.displayName, image: provider.markImageName, value: provider) {
                    NavigationStack {
                        DashboardView(provider: provider)
                    }
                    .toolbar(model.enabledProviders.count > 1 && !drawsBarsInSimulatedRail ? .visible : .hidden,
                             for: .tabBar)
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
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
        .sheet(isPresented: presented(\.isShowingSettings)) {
            NavigationStack {
                SettingsView()
            }
        }
        .sheet(isPresented: presented(\.isShowingReset)) {
            NavigationStack {
                ResetCreditView()
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: presented(\.isShowingSignIn)) {
            NavigationStack {
                SignInView()
            }
            .interactiveDismissDisabled(model.isAuthenticating)
        }
        .fullScreenCover(isPresented: presented(\.isShowingOnboarding)) {
            OnboardingView()
        }
        .background {
            // Only a switch between windows hands over the selection. The first key event
            // also makes the window key, and re-selecting then would undo the ⌘3 it carries.
            KeyWindowObserver {
                if windows.windowBecameKey(windowID) {
                    model.select(provider: currentSelection)
                }
            }
        }
        .onAppear {
            windows.windowAppeared(windowID)
            if selection == nil {
                selection = Self.restoresWindowTabs
                    ? openedProvider ?? storedProvider.flatMap(UsageProvider.init(rawValue:)) ?? model.selectedProvider
                    : model.selectedProvider
            }
        }
        .onDisappear {
            windows.windowDisappeared(windowID)
        }
        // ⌘1–⌘9, notification routes and tabs being turned off change the model's selection;
        // only the window in front follows.
        .onChange(of: model.selectedProvider) { _, provider in
            guard windows.isKey(windowID), provider != selection else { return }
            selection = provider
            storedProvider = provider.rawValue
        }
        // Menu commands switch this window's tab while it is in front.
        .focusedSceneValue(\.windowTabs, WindowTabs(current: currentSelection) { choose($0) })
        .onChange(of: anySheetOpen, initial: true) { _, open in
            windows.sheetsChanged(anyOpen: open)
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

#Preview {
    ContentView()
        .environment(AppModel.preview)
}
