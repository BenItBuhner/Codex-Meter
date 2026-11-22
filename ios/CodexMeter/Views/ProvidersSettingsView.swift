import CodexMeterCore
import SwiftUI

/// Settings → Providers: accounts and dashboard tabs in one list, as on Android. Each connected
/// (or shown) provider has its account line, a switch for its tab and a page of its own; Edit
/// reorders the tabs, and Add account connects another service.
struct ProvidersSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var editMode: EditMode = .inactive
    @State private var signingIn: UsageProvider?
    @State private var confirmingSignOut: UsageProvider?

    private var shown: [UsageProvider] {
        model.providerOrder.filter { model.enabledProviders.contains($0) || model.isSignedIn($0) }
    }

    var body: some View {
        let shown = shown
        List {
            Section {
                ForEach(shown) { provider in
                    NavigationLink {
                        ProviderSettingsView(provider: provider)
                    } label: {
                        row(provider)
                    }
                    .accessibilityIdentifier("provider-\(provider.rawValue)")
                }
                .onMove { source, destination in
                    model.moveProviders(shown, fromOffsets: source, toOffset: destination)
                }
            } header: {
                Text("On your dashboard")
            } footer: {
                Text("Switch one off to hide its tab. Tap a provider for its account. With two or more on, tabs at the bottom of the dashboard switch between them; at least one stays on.")
            }

            Section {
                Button {
                    DiagnosticLog.info("user", "add_account_opened")
                    model.isAddingAccount = true
                } label: {
                    Label {
                        Text("Add account")
                            .foregroundStyle(Color.primary)
                    } icon: {
                        Image(systemName: "plus.circle.fill")
                            .foregroundStyle(Color.green)
                    }
                }
                .accessibilityIdentifier("account-add")
            } footer: {
                Text("Codex, Claude Code, Cursor, Copilot, Gemini and more.")
            }
        }
        .environment(\.editMode, $editMode)
        #if os(iOS)
        .toolbar { EditButton() }
        #endif
        .navigationTitle("Providers")
        .navigationBarTitleDisplayMode(.inline)
        .providerAccountPresentations(signingIn: $signingIn, confirmingSignOut: $confirmingSignOut)
    }

    private func row(_ provider: UsageProvider) -> some View {
        HStack(spacing: 12) {
            ProviderMark(provider: provider)
                .frame(width: 22, height: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(provider.displayName)
                Text(model.isSignedIn(provider) ? model.accountSummary(for: provider) : "Not signed in")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)
            Toggle("Show \(provider.displayName) tab", isOn: binding(for: provider))
                .labelsHidden()
                .disabled(model.enabledProviders == [provider])
        }
    }

    private func binding(for provider: UsageProvider) -> Binding<Bool> {
        Binding(
            get: { model.enabledProviders.contains(provider) },
            set: { model.setProvider(provider, enabled: $0) }
        )
    }
}

/// Settings → Providers → one provider: its account, its tab and cards, and Sign out (or Sign in).
struct ProviderSettingsView: View {
    let provider: UsageProvider
    @Environment(AppModel.self) private var model
    @State private var signingIn: UsageProvider?
    @State private var confirmingSignOut: UsageProvider?

    var body: some View {
        let signedIn = model.isSignedIn(provider)
        Form {
            Section("Account") {
                if signedIn {
                    let email = model.accountEmail(for: provider)
                    LabeledContent("Signed in as", value: email.isEmpty ? model.accountSummary(for: provider) : email)
                    let plan = model.accountPlan(for: provider)
                    if !plan.isEmpty {
                        LabeledContent("Plan", value: plan)
                    }
                } else {
                    Button("Sign in to \(provider.displayName)") {
                        signingIn = provider
                    }
                    .accessibilityIdentifier("provider-sign-in")
                }
            }

            Section("Dashboard") {
                Toggle("Show on dashboard", isOn: Binding(
                    get: { model.enabledProviders.contains(provider) },
                    set: { model.setProvider(provider, enabled: $0) }
                ))
                .disabled(model.enabledProviders == [provider])
                NavigationLink("Arrange cards") {
                    DashboardEditView(provider: provider)
                }
            }

            if signedIn {
                Section {
                    Button("Sign out", role: .destructive) {
                        confirmingSignOut = provider
                    }
                    .accessibilityIdentifier("provider-sign-out")
                } footer: {
                    Text("Removes this account's sign-in and cached usage from this device. Other accounts stay signed in.")
                }
            }
        }
        .navigationTitle(provider.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .providerAccountPresentations(
            signingIn: $signingIn,
            confirmingSignOut: $confirmingSignOut,
            addsAccounts: false
        )
    }
}
