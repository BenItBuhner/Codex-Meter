import CodexMeterCore
import SwiftUI

/// The Settings window (⌘,). General is the same settings page the iPhone app shows, laid out
/// as a grouped Mac form; Menu Bar holds the Mac-only options.
struct MacSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        TabView {
            Tab("General", systemImage: "gearshape") {
                NavigationStack {
                    SettingsView()
                }
                .formStyle(.grouped)
            }

            Tab("Menu Bar", systemImage: "menubar.rectangle") {
                MacMenuBarSettingsView()
                    .formStyle(.grouped)
            }
        }
        .frame(width: 600, height: 680)
        .preferredColorScheme(model.settings.appearance.colorScheme)
    }
}

struct MacMenuBarSettingsView: View {
    @Environment(AppModel.self) private var model
    @AppStorage(MacPreferences.showsMenuBarExtraKey) private var showsMenuBarExtra = true
    @AppStorage(MacPreferences.menuBarShowsPercentKey) private var showsPercent = true
    @AppStorage(MacPreferences.menuBarProviderKey) private var providerID = ""
    @State private var opensAtLogin = LaunchAtLogin.isEnabled
    @State private var loginItemError: String?

    var body: some View {
        Form {
            Section {
                Toggle("Show Codex Meter in the menu bar", isOn: $showsMenuBarExtra)
                    .accessibilityIdentifier("settings-show-menu-bar")
                Toggle("Show remaining percentage", isOn: $showsPercent)
                    .disabled(!showsMenuBarExtra)
                Picker("Meter follows", selection: $providerID) {
                    Text("Selected provider").tag("")
                    ForEach(model.enabledProviders) { provider in
                        Text(provider.displayName).tag(provider.rawValue)
                    }
                }
                .disabled(!showsMenuBarExtra)
            } header: {
                Text("Menu bar")
            } footer: {
                Text("The gauge shows whichever allowance is closest to running out. With the menu bar item on, closing the window keeps Codex Meter running so usage, alerts, and widgets stay current.")
            }

            Section {
                Toggle("Open at login", isOn: $opensAtLogin)
                    .onChange(of: opensAtLogin) { _, enabled in
                        do {
                            try LaunchAtLogin.set(enabled)
                            loginItemError = nil
                        } catch {
                            loginItemError = error.localizedDescription
                            opensAtLogin = LaunchAtLogin.isEnabled
                        }
                    }
                if LaunchAtLogin.requiresApproval {
                    Text("Allow Codex Meter in System Settings → General → Login Items.")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                if let loginItemError {
                    Text(loginItemError)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            } header: {
                Text("Startup")
            }

            Section {
                LabeledContent("Background refresh") {
                    Text(model.settings.refreshMode == .automatic ? "Automatic" : "Every \(model.settings.refreshMinutes) min or at the next reset")
                }
            } footer: {
                Text("On the Mac, Codex Meter refreshes on its own while it runs, at the interval in General → Refresh or when a limit resets, whichever is sooner. Claude Code and Cursor refresh when you open their dashboard or the menu bar panel.")
            }
        }
    }
}

/// The Usage menu: refresh, provider switching (⌘1–⌘9), and resets.
struct MacCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {}

        CommandMenu("Usage") {
            if !MacLaunchOptions.diagnosticDisables("commands") {
                usageItems
            }
        }
    }

    @ViewBuilder
    private var usageItems: some View {
        Button("Refresh") {
            Task { await model.macRefresh(model.selectedProvider) }
        }
        .keyboardShortcut("r")

        Button("Refresh All Providers") {
            Task { await model.macRefreshAll() }
        }
        .keyboardShortcut("r", modifiers: [.command, .shift])

        Divider()

        ForEach(Array(model.enabledProviders.prefix(9).enumerated()), id: \.element) { index, provider in
            Button(provider.displayName) {
                model.select(provider: provider)
            }
            .keyboardShortcut(KeyEquivalent(Character(String(index + 1))))
        }

        Divider()

        Button("Use a Reset Credit…") {
            model.select(provider: .codex)
            model.isShowingReset = true
        }
        .disabled(model.mode == .signedOut)

        if model.mode == .signedOut {
            Button("Explore Demo") {
                Task { await model.enterDemo() }
            }
        }
    }
}
