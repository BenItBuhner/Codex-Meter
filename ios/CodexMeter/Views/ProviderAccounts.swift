import CodexMeterCore
import SwiftUI

/// The sign-in screen for one provider, for presenting from Settings or first-run setup.
struct ProviderSignInScreen: View {
    let provider: UsageProvider

    var body: some View {
        NavigationStack {
            ProviderSignInContent(provider: provider)
        }
    }
}

/// A provider's sign-in screen without its own navigation stack.
struct ProviderSignInContent: View {
    let provider: UsageProvider

    var body: some View {
        Group {
            switch provider {
            case .codex:
                SignInView()
            case .claudeCode:
                ClaudeSignInView()
            case .cursor:
                CursorSignInView()
            default:
                AddedProviderSignInView(provider: provider)
            }
        }
    }
}

extension View {
    /// Presents a provider's sign-in while `provider` is set, and closes it once that provider
    /// is signed in. An unfinished Cursor sign-in stops polling on dismiss.
    func providerSignInSheet(_ provider: Binding<UsageProvider?>) -> some View {
        modifier(ProviderSignInSheetModifier(provider: provider))
    }

    /// The sign-in sheet and sign-out confirmation behind `ProviderAccountsSection`. Attach it
    /// to the Form rather than to rows inside the list.
    func providerAccountPresentations(
        signingIn: Binding<UsageProvider?>,
        confirmingSignOut: Binding<UsageProvider?>
    ) -> some View {
        modifier(ProviderAccountPresentations(
            signingIn: signingIn,
            confirmingSignOut: confirmingSignOut
        ))
    }
}

private struct ProviderSignInSheetModifier: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var provider: UsageProvider?

    func body(content: Content) -> some View {
        content
            .sheet(item: $provider, onDismiss: cancelUnfinishedSignIns) { provider in
                ProviderSignInScreen(provider: provider)
                    .interactiveDismissDisabled(isAuthenticating(provider))
            }
            .onChange(of: model.mode) { _, _ in closeIfSignedIn() }
            .onChange(of: model.claude.phase) { _, _ in closeIfSignedIn() }
            .onChange(of: model.cursor.phase) { _, _ in closeIfSignedIn() }
            .onChange(of: model.connectedProviders) { _, _ in closeIfSignedIn() }
    }

    private func isAuthenticating(_ provider: UsageProvider) -> Bool {
        switch provider {
        case .codex: model.isAuthenticating
        case .claudeCode: model.claude.isAuthenticating
        case .cursor: false
        default: model.addedProviders[provider]?.signInStep == .checking
        }
    }

    private func closeIfSignedIn() {
        if let provider, model.isSignedIn(provider) {
            self.provider = nil
        }
    }

    private func cancelUnfinishedSignIns() {
        if model.cursor.phase == .signedOut {
            model.cursor.cancelSignIn()
        }
        for added in model.addedProviders.values where added.phase == .signedOut {
            added.cancelSignIn()
        }
    }
}

private struct ProviderAccountPresentations: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var signingIn: UsageProvider?
    @Binding var confirmingSignOut: UsageProvider?

    func body(content: Content) -> some View {
        @Bindable var model = model
        content
            .providerSignInSheet($signingIn)
            .sheet(isPresented: $model.isAddingAccount) {
                AddAccountFlow()
            }
            .confirmationDialog(
                confirmingSignOut.map { "Sign out of \($0.accountName)?" } ?? "Sign out?",
                isPresented: Binding(
                    get: { confirmingSignOut != nil },
                    set: { if !$0 { confirmingSignOut = nil } }
                ),
                titleVisibility: .visible,
                presenting: confirmingSignOut
            ) { provider in
                Button("Sign out", role: .destructive) {
                    Task { await model.signOut(provider: provider) }
                }
                Button("Cancel", role: .cancel) {}
            } message: { provider in
                Text("Removes the \(provider.accountName) sign-in and cached \(provider.displayName) usage from this device. Your other accounts are not affected.")
            }
    }
}

/// Settings → Accounts: only the accounts that are connected, in tab order, then one
/// "Add account" row that opens the searchable provider picker. Tapping a connected row asks
/// to sign out. Mirrors Android's Accounts section.
struct ProviderAccountsSection: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Binding var signingIn: UsageProvider?
    @Binding var confirmingSignOut: UsageProvider?

    var body: some View {
        Section {
            if model.mode == .demo {
                LabeledContent("Mode", value: "Demo")
                Button("Leave Demo", role: .destructive) {
                    Task {
                        await model.leaveDemo()
                        dismiss()
                    }
                }
            } else {
                ForEach(model.connectedProviders) { provider in
                    accountRow(provider)
                }
                addAccountRow
            }
        } header: {
            Text("Accounts")
        }
    }

    private var addAccountRow: some View {
        Button {
            DiagnosticLog.info("user", "add_account_opened")
            model.isAddingAccount = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "plus.circle.fill")
                    .font(.title3)
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 22, height: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Add account")
                        .foregroundStyle(Color.primary)
                    if model.connectedProviders.isEmpty {
                        Text("Codex, Claude Code, Cursor, Copilot and more")
                            .font(.footnote)
                            .foregroundStyle(Color.secondary)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(.tertiaryLabel))
            }
        }
        .accessibilityIdentifier("account-add")
    }

    private func accountRow(_ provider: UsageProvider) -> some View {
        Button {
            confirmingSignOut = provider
        } label: {
            HStack(spacing: 12) {
                ProviderMark(provider: provider)
                    .frame(width: 22, height: 22)
                    .foregroundStyle(Color.primary)
                VStack(alignment: .leading, spacing: 2) {
                    // Concrete colors: a List button tints hierarchical styles with the accent.
                    Text(provider.displayName)
                        .foregroundStyle(Color.primary)
                    Text(subtitle(provider))
                        .font(.footnote)
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: 8)
                Text("Sign out")
                    .font(.subheadline)
                    .foregroundStyle(Color.red)
            }
        }
        .accessibilityIdentifier("account-\(provider.rawValue)")
    }

    private func subtitle(_ provider: UsageProvider) -> String {
        let email = model.accountEmail(for: provider)
        let plan = model.accountPlan(for: provider)
        let identity = email.isEmpty ? "Signed in" : email
        return plan.isEmpty ? identity : "\(identity) · \(plan)"
    }
}

/// The Add account sheet: pick a provider, then its sign-in opens on top of the picker. The
/// sheet closes once that provider is connected; the new account gets a tab.
struct AddAccountFlow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var chosen: UsageProvider?

    var body: some View {
        NavigationStack {
            AddAccountView { chosen = $0 }
                .navigationDestination(item: $chosen) { provider in
                    ProviderSignInContent(provider: provider)
                }
        }
        .onChange(of: model.connectedProviders) { _, connected in
            if let chosen, connected.contains(chosen) {
                dismiss()
            }
        }
        .onChange(of: chosen) { old, new in
            // Backing out of an unfinished sign-in stops its polling.
            if let old, new == nil, !model.isSignedIn(old) {
                model.addedProviders[old]?.cancelSignIn()
                if old == .cursor { model.cursor.cancelSignIn() }
            }
        }
    }
}
