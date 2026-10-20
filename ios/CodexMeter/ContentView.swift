import CodexMeterCore
import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model

        // Stock tab bar with each provider's mark. Only providers turned on in Settings get a
        // tab, and with just one on the bar is hidden.
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
    }
}

#Preview {
    ContentView()
        .environment(AppModel.preview)
}
