import CodexMeterCore
import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model

        // Stock tab bar with each provider's mark. Only providers turned on in Settings get a
        // tab, and with just one on the bar is hidden. On iPad the same tabs become a sidebar
        // (or the floating top tab bar, which the sidebar button toggles), so every provider
        // stays one tap away instead of collapsing into a More tab.
        TabView(selection: Binding(
            get: { model.selectedProvider },
            set: { model.select(provider: $0) }
        )) {
            ForEach(model.enabledProviders) { provider in
                Tab(provider.displayName, image: provider.markImageName, value: provider) {
                    NavigationStack {
                        DashboardView(provider: provider)
                    }
                    .toolbar(model.enabledProviders.count > 1 ? .visible : .hidden, for: .tabBar)
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
