import CodexMeterCore
import SwiftUI

/// Settings → Providers: which providers get a dashboard tab, and in what order. Rows are
/// always in edit mode so the reorder handles show; each row's switch hides or shows its tab.
struct ProvidersSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            Section {
                ForEach(model.providerOrder) { provider in
                    Toggle(isOn: binding(for: provider)) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(provider.displayName)
                                Text("Signs in with \(provider.accountName)")
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
                    model.moveProviders(fromOffsets: source, toOffset: destination)
                }
            } footer: {
                Text("Drag to set the order of the tabs. With two or more providers on, tabs at the bottom of the dashboard switch between them. With one on, the tabs are hidden. At least one provider stays on.")
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Providers")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func binding(for provider: UsageProvider) -> Binding<Bool> {
        Binding(
            get: { model.enabledProviders.contains(provider) },
            set: { model.setProvider(provider, enabled: $0) }
        )
    }
}
