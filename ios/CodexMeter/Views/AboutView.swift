import CodexMeterCore
import SwiftUI

struct AboutView: View {
    @Environment(AppModel.self) private var model
    @State private var versionTaps = 0
    @State private var unlockHint: String?
    @State private var showingDiagnostics = false
    @ScaledMetric(relativeTo: .largeTitle) private var symbolSize: CGFloat = 52

    private static let diagnosticTaps = 7

    static let tagline = "An unofficial native app for keeping an eye on your AI coding limits: Codex, Claude Code, Cursor and more, all in one place."
    static let disclaimer = "Codex Meter is not affiliated with or endorsed by OpenAI, Anthropic, Cursor or any other provider it shows. Product names are trademarks of their owners. Account routes used by this app may change without notice."

    struct Credit: Identifiable {
        let id: String
        let name: String
        let role: String
        let url: URL
    }

    /// The same people as Android's About page, plus the iPhone app's developer.
    static let credits = [
        Credit(id: "benit", name: "BenIt Buhner", role: "App creator and AI geek",
               url: URL(string: "https://github.com/BenItBuhner")!),
        Credit(id: "josh", name: "That Josh Guy", role: "App and Icon designer", url: URL(string: "https://tjg.gg")!),
        Credit(id: "robert", name: "Robertg761", role: "Live usage notifications and the Now Bar",
               url: URL(string: "https://github.com/Robertg761")!),
        Credit(id: "filip", name: "Filip Bukovina", role: "iPhone and iPad app", url: URL(string: "https://github.com/FBukovina")!)
    ]

    private var version: String {
        AppVersion.display
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "gauge.with.dots.needle.67percent")
                        .font(.system(size: symbolSize))
                        .foregroundStyle(.tint)
                        .symbolRenderingMode(.hierarchical)
                        .accessibilityHidden(true)
                    Text("Codex Meter")
                        .font(.title2.bold())
                    Text("Version \(version)")
                        .foregroundStyle(.secondary)
                    Text(Self.tagline)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    if let unlockHint {
                        Text(unlockHint)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .contentShape(Rectangle())
                .onTapGesture(perform: handleVersionTap)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Tap repeatedly to unlock diagnostics.")
            }

            Section {
                NavigationLink("What's new in \(WhatsNew.release)") {
                    WhatsNewView()
                }
                .accessibilityIdentifier("about-whats-new")
            }

            Section {
                ForEach(Self.credits) { person in
                    Link(destination: person.url) {
                        LabeledContent {
                            Image(systemName: "arrow.up.right")
                                .font(.footnote)
                                .foregroundStyle(.tertiary)
                        } label: {
                            Text(person.name)
                                .foregroundStyle(.primary)
                            Text(person.role)
                        }
                    }
                    .accessibilityIdentifier("about-credit-" + person.id)
                }
            } header: {
                Text("Credits").textCase(nil)
            }

            Section {
                Link("BenItBuhner/Codex-Meter", destination: URL(string: "https://github.com/BenItBuhner/Codex-Meter")!)
                LabeledContent("License", value: "MIT")
                NavigationLink("Open-source notices") {
                    OpenSourceNoticesView()
                }
            } header: {
                Text("Source").textCase(nil)
            }

            Section {
                Text(Self.disclaimer)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Important").textCase(nil)
            }
        }
        .navigationTitle("About")
        .navigationDestination(isPresented: $showingDiagnostics) {
            DiagnosticsView()
        }
    }

    private func handleVersionTap() {
        versionTaps += 1
        let remaining = Self.diagnosticTaps - versionTaps
        if remaining <= 0 {
            unlockHint = "Diagnostics unlocked."
            model.unlockDiagnostics()
            showingDiagnostics = true
            versionTaps = 0
        } else if remaining <= 3 {
            unlockHint = remaining == 1
                ? "1 more tap for diagnostics."
                : "\(remaining) more taps for diagnostics."
        }
    }
}

struct OpenSourceNoticesView: View {
    private let mitNotice = """
    MIT License

    Copyright (c) 2026 Bennett

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
    """

    var body: some View {
        ScrollView {
            Text(mitNotice)
                .font(.footnote.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: 760, alignment: .leading)
                .padding(22)
        }
        .navigationTitle("Open-source notices")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PrivacyPolicyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Privacy policy")
                    .font(.largeTitle.bold())
                Text("Codex Meter has no analytics, advertising, account system, or relay server. Sign-in and usage requests go directly to each provider you connect (such as OpenAI, Anthropic or Cursor), only when needed for the feature you selected.")
                Text("Public data")
                    .font(.title3.bold())
                Text("Priority reads the providers' official status pages and OpenRouter's public model list without any account data, so it can show outages, slowdowns and new models.")
                Text("Credentials")
                    .font(.title3.bold())
                Text("OAuth tokens are stored in Apple Keychain on this device. Widgets never receive tokens or account identifiers.")
                Text("Local storage")
                    .font(.title3.bold())
                Text("The last successful usage response and bounded allowance samples are stored locally for offline display, burn charts, and pace estimates. You can clear history separately; signing out removes credentials, cached account data, history, background requests, and scheduled notifications.")
                Text("Settings exports contain preferences and dashboard choices, never credentials or usage samples.")
                Text("Demo mode")
                    .font(.title3.bold())
                Text("Demo mode is entirely local and contacts no provider.")
                Text("Last updated November 25, 2026")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: 700, alignment: .leading)
            .padding(22)
        }
        .navigationTitle("Privacy")
        .navigationBarTitleDisplayMode(.inline)
    }
}
