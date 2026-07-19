import SwiftUI

struct AboutView: View {
    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "gauge.with.dots.needle.67percent")
                        .font(.system(size: 52))
                        .foregroundStyle(.tint)
                        .symbolRenderingMode(.hierarchical)
                        .accessibilityHidden(true)
                    Text("Codex Meter")
                        .font(.title2.bold())
                    Text("Version \(version)")
                        .foregroundStyle(.secondary)
                    Text("An unofficial native client for viewing Codex allowance and earned reset credits.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
            }

            Section("Source and credits") {
                Link("BenItBuhner/Codex-Meter", destination: URL(string: "https://github.com/BenItBuhner/Codex-Meter")!)
                Link("GitHub Releases (what’s new)", destination: URL(string: "https://github.com/BenItBuhner/Codex-Meter/releases")!)
                LabeledContent("Original project", value: "Bennett")
                LabeledContent("License", value: "MIT")
                LabeledContent("iOS marketing version", value: version)
                NavigationLink("Open-source notices") {
                    OpenSourceNoticesView()
                }
                Link("OpenAI", destination: URL(string: "https://openai.com")!)
            }

            Section {
                Text("Release notes and Android APKs are published on GitHub. This iOS app is not updated via in-app APK install; use TestFlight or the App Store when available.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Important") {
                Text("Codex Meter is not affiliated with or endorsed by OpenAI. ChatGPT and Codex are trademarks of their respective owner. Account routes used by this app may change without notice.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("About")
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
                Text("Codex Meter has no analytics, advertising, account system, or relay server. Authentication and Codex account requests go directly to OpenAI only when needed for the feature you selected.")
                Text("Credentials")
                    .font(.title3.bold())
                Text("OAuth tokens are stored in Apple Keychain on this device. Widgets never receive tokens or account identifiers.")
                Text("Local storage")
                    .font(.title3.bold())
                Text("The last successful usage response is cached for offline display. Signing out removes credentials, cached account data, background requests, and scheduled notifications.")
                Text("Demo mode")
                    .font(.title3.bold())
                Text("Demo mode is entirely local and does not contact OpenAI.")
                Text("Last updated July 12, 2026")
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
