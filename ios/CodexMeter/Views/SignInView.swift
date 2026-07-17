import SwiftUI
import SafariServices

struct SignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showingBrowser = false
    @State private var didCopyCode = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                if let challenge = model.authChallenge {
                    codeStep(challenge: challenge)
                } else {
                    introStep
                }

                if let error = model.authenticationError {
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
        .navigationTitle("Connect account")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    model.cancelSignIn()
                    dismiss()
                }
            }
        }
        .sheet(isPresented: $showingBrowser) {
            if let url = model.authChallenge?.verificationURL {
                SafariView(url: url)
                    .ignoresSafeArea()
            }
        }
        .onChange(of: model.mode) { _, mode in
            if mode == .live {
                showingBrowser = false
                dismiss()
            }
        }
        .onChange(of: model.authChallenge?.userCode) { _, _ in
            didCopyCode = false
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
                Text("Sign in with ChatGPT")
                    .font(.title2.bold())
                Text("Codex Meter uses OpenAI's device-code flow. Your password is entered only on the OpenAI-controlled page and is never visible to this app.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Button("Get one-time code") {
                Task { await model.beginSignIn() }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.isAuthenticating)

            if model.isAuthenticating {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Preparing secure sign-in…")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity)
        .cardSurface()
    }

    private func codeStep(challenge: DeviceCodeChallenge) -> some View {
        VStack(spacing: 20) {
            Image(systemName: "person.badge.key.fill")
                .font(.system(size: 48))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Enter this one-time code")
                    .font(.title2.bold())
                Text(challenge.userCode)
                    .font(.system(.largeTitle, design: .monospaced, weight: .bold))
                    .textSelection(.enabled)
                    .accessibilityLabel("One-time code \(challenge.userCode)")
            }

            HStack(spacing: 12) {
                Button {
                    UIPasteboard.general.string = challenge.userCode
                    didCopyCode = true
                    UIAccessibility.post(
                        notification: .announcement,
                        argument: String(localized: "Code copied")
                    )
                } label: {
                    Label(
                        didCopyCode ? "Copied" : "Copy code",
                        systemImage: didCopyCode ? "checkmark" : "doc.on.doc"
                    )
                }
                .buttonStyle(.bordered)
                .animation(.snappy, value: didCopyCode)

                Button("Open ChatGPT sign-in") {
                    showingBrowser = true
                    model.continueSignIn()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }

            if model.isAuthenticating {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Waiting for secure sign-in…")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            } else {
                Text("After signing in on the OpenAI page, return here. This screen updates automatically.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity)
        .cardSurface()
    }
}

private struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.dismissButtonStyle = .close
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}
