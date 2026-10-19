import CodexMeterCore
import SwiftUI

/// Claude Code's manual sign-in: approve access on claude.ai, then paste the `CODE#STATE`
/// Claude shows on its callback page. The password is entered only on Anthropic's page.
struct ClaudeSignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var browserURL: URL?
    @State private var showingBrowser = false
    @FocusState private var codeFieldFocused: Bool

    var body: some View {
        @Bindable var claude = model.claude

        ScrollView {
            VStack(spacing: 24) {
                if let authorization = claude.authorization {
                    codeStep(authorization: authorization, code: $claude.pastedCode)
                } else {
                    introStep
                }

                if let error = claude.authenticationError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .bannerSurface(tint: .red)
                }
            }
            .frame(maxWidth: 520)
            .padding(28)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Connect Claude")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    model.claude.cancelSignIn()
                    dismiss()
                }
                .disabled(claude.isAuthenticating)
            }
        }
        .sheet(isPresented: $showingBrowser) {
            if let browserURL {
                SafariView(url: browserURL)
                    .ignoresSafeArea()
            }
        }
    }

    private var introStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "person.crop.circle.badge.checkmark")
                .font(.system(size: 54))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text(UsageProvider.claudeCode.signInLabel)
                    .font(.title2.bold())
                Text("Codex Meter uses the same sign-in as Claude Code. Approve access on claude.ai, then copy the code Claude shows and paste it here. Your password is never visible to this app.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Button("Open Claude sign-in") {
                browserURL = model.claude.beginSignIn()
                showingBrowser = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Text("Usage limits are available for Claude Pro, Max, Team, and Enterprise plans.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity)
        .cardSurface()
    }

    private func codeStep(
        authorization: ClaudeAuthorizationRequest,
        code: Binding<String>
    ) -> some View {
        let claude = model.claude

        return VStack(spacing: 20) {
            Image(systemName: "key.horizontal.fill")
                .font(.system(size: 44))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Paste the code from Claude")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("After you tap Authorize, Claude shows a code. Copy it, close the browser, and paste it below.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            TextField("Authentication code", text: code)
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($codeFieldFocused)
                .submitLabel(.go)
                .onSubmit {
                    Task { await model.claude.completeSignIn() }
                }
                .padding(12)
                .background(
                    Color(.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
                .accessibilityLabel("Authentication code")

            HStack(spacing: 12) {
                PasteButton(payloadType: String.self) { strings in
                    let pasted = strings.first ?? ""
                    Task { @MainActor in
                        claude.pastedCode = pasted.trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                }
                .buttonBorderShape(.capsule)
                .labelStyle(.titleAndIcon)

                Button {
                    codeFieldFocused = false
                    Task { await model.claude.completeSignIn() }
                } label: {
                    Text("Continue")
                        .frame(minWidth: 96)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(
                    claude.isAuthenticating
                        || claude.pastedCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )
            }

            if claude.isAuthenticating {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Finishing secure sign-in…")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            } else {
                Button("Open Claude sign-in again") {
                    browserURL = authorization.authorizeURL
                    showingBrowser = true
                }
                .font(.footnote)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity)
        .cardSurface()
    }
}
