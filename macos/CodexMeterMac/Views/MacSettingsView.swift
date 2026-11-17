import AppKit
import CodexMeterCore
import SwiftUI

/// The Settings window (⌘,). General is the same settings page the iPhone app shows, laid out
/// as a grouped Mac form; Menu Bar holds the Mac-only options.
struct MacSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = MacSettingsTab.shared

    var body: some View {
        TabView(selection: $tab.selected) {
            Tab("General", systemImage: "gearshape", value: "general") {
                NavigationStack {
                    SettingsView()
                }
                .formStyle(.grouped)
            }

            Tab("Menu Bar", systemImage: "menubar.rectangle", value: "menu-bar") {
                MacMenuBarSettingsView()
                    .formStyle(.grouped)
            }

            Tab("Updates", systemImage: "arrow.down.circle", value: "updates") {
                MacUpdatesSettingsView()
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
    @AppStorage(MacPreferences.menuBarProviderKey) private var providerID = ""
    @AppStorage(MacPreferences.menuBarFollowsAppsKey) private var followsApps = true
    @AppStorage(MacPreferences.menuBarWindowKey) private var windowChoice = MacLiveWindow.auto.rawValue
    @AppStorage(MacPreferences.menuBarStyleKey) private var styleChoice = MacPreferences.defaultMenuBarStyle
    @State private var opensAtLogin = LaunchAtLogin.isEnabled
    @State private var loginItemError: String?

    var body: some View {
        Form {
            Section {
                Toggle("Show Codex Meter in the menu bar", isOn: $showsMenuBarExtra)
                    .accessibilityIdentifier("settings-show-menu-bar")
            } header: {
                Text("Menu bar")
            } footer: {
                Text("With the menu bar item on, closing the window keeps Codex Meter running so usage, alerts, and widgets stay current.")
            }

            Section {
                Picker("Show", selection: $styleChoice) {
                    ForEach(MacMenuBarStyle.allCases) { style in
                        Text(style.title).tag(style.rawValue)
                    }
                }
                .accessibilityIdentifier("settings-menu-bar-style")
                Picker("Provider", selection: $providerID) {
                    Text("Automatic").tag("")
                    ForEach(model.enabledProviders) { provider in
                        Text(provider.displayName).tag(provider.rawValue)
                    }
                }
                .accessibilityIdentifier("settings-menu-bar-provider")
                Toggle("While using AI apps", isOn: $followsApps)
                    .accessibilityIdentifier("settings-menu-bar-follows-apps")
                Picker("Percentage shown", selection: $windowChoice) {
                    ForEach(MacLiveWindow.allCases) { window in
                        Text(window.title).tag(window.rawValue)
                    }
                }
                .accessibilityIdentifier("settings-menu-bar-window")
            } header: {
                Text("Live usage")
            } footer: {
                Text("Like the live notification on Android. Automatic shows the first connected provider in the sidebar. While using AI apps shows a provider's usage while one of the apps below is in front. Auto shows whichever of the 5-hour and weekly limits has less left, and a used-up limit shows the time until it resets.")
            }
            .disabled(!showsMenuBarExtra)

            MacAIAppsSection()
                .disabled(!showsMenuBarExtra || !followsApps)

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

/// Settings → Menu Bar → AI apps: the apps that point the menu bar at a provider while they're in
/// front, as on Android. Add any app, point a built-in one at another provider, remove it, or
/// switch it off.
struct MacAIAppsSection: View {
    @AppStorage(MacPreferences.menuBarAppsKey) private var storedApps = ""
    @State private var editing: MacAppEdit?

    private var list: DesktopAppList { DesktopAppList(json: storedApps) }

    var body: some View {
        Section {
            ForEach(list.rules) { rule in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.app)
                        Text(description(rule))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        editing = MacAppEdit(original: rule.app, name: rule.app, provider: rule.provider)
                    } label: {
                        Image(systemName: "pencil")
                    }
                    .buttonStyle(.borderless)
                    .help("Edit \(rule.app)")
                    .accessibilityIdentifier("settings-app-edit-\(rule.key)")
                    Button {
                        change { $0.remove(rule.app) }
                    } label: {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.borderless)
                    .help("Remove \(rule.app)")
                    .accessibilityIdentifier("settings-app-remove-\(rule.key)")
                    Toggle("Use \(rule.app)", isOn: Binding(
                        get: { rule.isOn },
                        set: { isOn in change { $0.setOn(rule.app, isOn) } }
                    ))
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .accessibilityIdentifier("settings-app-toggle-\(rule.key)")
                }
                .accessibilityIdentifier("settings-app-\(rule.key)")
            }
            HStack {
                Button("Restore Defaults") {
                    change { $0.restoreDefaults() }
                }
                .disabled(!list.isCustomized)
                .accessibilityIdentifier("settings-apps-restore")
                Spacer()
                Button("Add App…") {
                    editing = MacAppEdit(original: nil, name: "", provider: .cursor)
                }
                .accessibilityIdentifier("settings-apps-add")
            }
        } header: {
            Text("AI apps")
        } footer: {
            Text("While one of these apps is active, the menu bar shows its provider. Apps are matched by name, like Grok Bot for Cursor.")
        }
        .sheet(item: $editing) { edit in
            MacAppEditSheet(edit: edit) { name, provider in
                change { $0.set(name, to: provider, replacing: edit.original) }
            }
        }
    }

    private func description(_ rule: DesktopAppRule) -> String {
        if !rule.isBuiltIn { return "\(rule.provider.displayName) · Added by you" }
        if let original = rule.originalProvider { return "\(rule.provider.displayName) · Changed from \(original.displayName)" }
        return rule.provider.displayName
    }

    private func change(_ update: (inout DesktopAppList) -> Void) {
        var list = list
        update(&list)
        storedApps = list.isCustomized ? list.json : ""
    }
}

/// The app being added (`original` nil) or edited in `MacAppEditSheet`.
struct MacAppEdit: Identifiable {
    let id = UUID()
    let original: String?
    var name: String
    var provider: UsageProvider
}

/// Add App… / Edit: an app name (typed, or picked from the apps running now) and its provider.
struct MacAppEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var provider: UsageProvider
    private let isNew: Bool
    private let save: (String, UsageProvider) -> Void

    init(edit: MacAppEdit, save: @escaping (String, UsageProvider) -> Void) {
        _name = State(initialValue: edit.name)
        _provider = State(initialValue: edit.provider)
        isNew = edit.original == nil
        self.save = save
    }

    private var runningApps: [String] {
        let names = NSWorkspace.shared.runningApplications
            .filter { $0.activationPolicy == .regular && $0.bundleIdentifier != Bundle.main.bundleIdentifier }
            .compactMap(\.localizedName)
        return Array(Set(names)).sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    TextField("App name", text: $name, prompt: Text("Grok Bot"))
                        .accessibilityIdentifier("settings-app-name")
                    Menu("Running Apps") {
                        ForEach(runningApps, id: \.self) { app in
                            Button(app) { name = app }
                        }
                    }
                    .fixedSize()
                }
                Picker("Shows usage for", selection: $provider) {
                    ForEach(UsageProvider.allCases, id: \.self) { provider in
                        Text(provider.displayName).tag(provider)
                    }
                }
                .accessibilityIdentifier("settings-app-provider")
            } header: {
                Text(isNew ? "Add an App" : "Edit App")
            } footer: {
                Text("While this app is active, the menu bar shows this provider's usage.")
            }
        }
        .formStyle(.grouped)
        .frame(width: 420)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    save(name, provider)
                    dismiss()
                }
                .disabled(DesktopAppList.key(name).isEmpty)
                .accessibilityIdentifier("settings-app-save")
            }
        }
    }
}

/// File → Add Account… (⌘N) and the Usage menu: refresh, provider switching (⌘1–⌘9), and resets.
struct MacCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(after: .appInfo) {
            MacCheckForUpdatesButton()
        }

        CommandGroup(replacing: .newItem) {
            Button("Add Account…") {
                NotificationCenter.default.post(name: .codexMeterAddAccount, object: nil)
            }
            .keyboardShortcut("n")
        }

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
