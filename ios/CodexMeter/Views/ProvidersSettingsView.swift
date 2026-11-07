import CodexMeterCore
import SwiftUI

/// Settings → Providers: which providers get a dashboard tab, and in what order. Rows are
/// always in edit mode so the reorder handles show; each row's switch hides or shows its tab.
/// Only tabs that are on and connected accounts are listed; other providers are added from
/// Settings → Accounts, as on Android.
struct ProvidersSettingsView: View {
    @Environment(AppModel.self) private var model

    private var shown: [UsageProvider] {
        model.providerOrder.filter { model.enabledProviders.contains($0) || model.isSignedIn($0) }
    }

    var body: some View {
        let shown = shown
        List {
            Section {
                ForEach(shown) { provider in
                    Toggle(isOn: binding(for: provider)) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(provider.displayName)
                                Text(subtitle(for: provider))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            ProviderMark(provider: provider)
                                .frame(width: 22, height: 22)
                        }
                    }
                    .disabled(model.enabledProviders == [provider])
                    .accessibilityIdentifier("provider-\(provider.rawValue)")
                }
                .onMove { source, destination in
                    model.moveProviders(shown, fromOffsets: source, toOffset: destination)
                }
            } footer: {
                Text("Drag to set the order of the tabs. With two or more providers on, tabs at the bottom of the dashboard switch between them. With one on, the tabs are hidden. At least one provider stays on. Add more from Settings → Accounts.")
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Providers")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func subtitle(for provider: UsageProvider) -> String {
        let email = model.accountEmail(for: provider)
        guard model.isSignedIn(provider), !email.isEmpty else {
            return "Signs in with \(provider.accountName)"
        }
        return "Signed in as \(email)"
    }

    private func binding(for provider: UsageProvider) -> Binding<Bool> {
        Binding(
            get: { model.enabledProviders.contains(provider) },
            set: { model.setProvider(provider, enabled: $0) }
        )
    }
}
