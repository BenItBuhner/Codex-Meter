import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            DashboardView()
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
        .preferredColorScheme(model.settings.appearance.colorScheme)
        .onOpenURL { model.handle(url: $0) }
    }
}

#Preview {
    ContentView()
        .environment(AppModel.preview)
}
