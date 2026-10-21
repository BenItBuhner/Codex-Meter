import CodexMeterCore
import SwiftUI

/// The sign-in screen for one provider, for presenting from Settings or first-run setup.
struct ProviderSignInScreen: View {
    let provider: UsageProvider

    var body: some View {
        NavigationStack {
            switch provider {
            case .codex:
                SignInView()
            case .claudeCode:
                ClaudeSignInView()
            case .cursor:
                CursorSignInView()
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
    }

    private func isAuthenticating(_ provider: UsageProvider) -> Bool {
        switch provider {
        case .codex: model.isAuthenticating
        case .claudeCode: model.claude.isAuthenticating
        case .cursor: false
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
    }
}

private struct ProviderAccountPresentations: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var signingIn: UsageProvider?
    @Binding var confirmingSignOut: UsageProvider?

    func body(content: Content) -> some View {
        content
            .providerSignInSheet($signingIn)
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

/// Settings → Accounts: one compact row per provider that has a tab. Signed-out rows start
/// that provider's sign-in; signed-in rows ask to sign out.
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
                ForEach(model.enabledProviders) { provider in
                    accountRow(provider)
                }
            }
        } header: {
            Text("Accounts")
        }
    }

    private func accountRow(_ provider: UsageProvider) -> some View {
        let signedIn = model.isSignedIn(provider)
        return Button {
            if signedIn {
                confirmingSignOut = provider
            } else {
                signingIn = provider
            }
        } label: {
            HStack(spacing: 12) {
                ProviderMark(provider: provider)
                    .frame(width: 22, height: 22)
                    .foregroundStyle(Color.primary)
                VStack(alignment: .leading, spacing: 2) {
                    // Concrete colors: a List button tints hierarchical styles with the accent.
                    Text(provider.displayName)
                        .foregroundStyle(Color.primary)
                    Text(subtitle(provider, signedIn: signedIn))
                        .font(.footnote)
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: 8)
                Text(signedIn ? "Sign out" : "Sign in")
                    .font(.subheadline)
                    .foregroundStyle(signedIn ? Color.red : Color.accentColor)
            }
        }
        .accessibilityIdentifier("account-\(provider.rawValue)")
    }

    private func subtitle(_ provider: UsageProvider, signedIn: Bool) -> String {
        guard signedIn else { return "Not signed in" }
        let email = model.accountEmail(for: provider)
        let plan = model.accountPlan(for: provider)
        let identity = email.isEmpty ? "Signed in" : email
        return plan.isEmpty ? identity : "\(identity) · \(plan)"
    }
}
