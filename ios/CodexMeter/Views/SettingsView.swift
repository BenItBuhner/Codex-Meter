import CodexMeterCore
import SwiftUI
import UniformTypeIdentifiers

/// Settings, in four groups: Usage (providers and their accounts, dashboard, reset credits), Alerts &
/// live usage, App, and About. The same groups and names as Android and Windows.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var model = model

        Form {
            SettingsDemoSection()

            Section {
                NavigationLink {
                    ProvidersSettingsView()
                } label: {
                    LabeledContent {
                        Text(providersSummary)
                    } label: {
                        Label("Providers", systemImage: "square.grid.2x2")
                    }
                }
                .accessibilityIdentifier("settings-providers")
                NavigationLink {
                    DashboardSettingsView()
                } label: {
                    Label("Dashboard", systemImage: "rectangle.grid.1x2")
                }
                .accessibilityIdentifier("settings-dashboard")
                NavigationLink {
                    ResetCreditsSettingsView()
                } label: {
                    Label("Reset credits", systemImage: "arrow.counterclockwise.circle")
                }
                .accessibilityIdentifier("settings-reset-credits")
            } header: {
                Text("Usage")
            }

            Section {
                NavigationLink {
                    NotificationsSettingsView()
                } label: {
                    LabeledContent {
                        Text(model.settings.notificationsEnabled ? "On" : "Off")
                    } label: {
                        Label("Notifications", systemImage: "bell.badge")
                    }
                }
                .accessibilityIdentifier("settings-notifications")
                #if os(iOS)
                NavigationLink {
                    LiveActivitySettingsView()
                } label: {
                    Label("Live Activity", systemImage: "platter.filled.bottom.iphone")
                }
                .accessibilityIdentifier("settings-live-activity")
                #endif
            } header: {
                Text("Alerts & live usage")
            }

            Section {
                Picker("Appearance", selection: $model.settings.appearance) {
                    ForEach(AppAppearance.allCases) { appearance in
                        Text(appearance.title).tag(appearance)
                    }
                }
                .pickerStyle(.segmented)
                #if os(iOS)
                Picker("Haptics", selection: $model.settings.haptics) {
                    ForEach(HapticsLevel.allCases) { level in
                        Text(level.title).tag(level)
                    }
                }
                #endif
                NavigationLink {
                    PrivacyDataSettingsView()
                } label: {
                    Label("Privacy & data", systemImage: "hand.raised")
                }
                .accessibilityIdentifier("settings-privacy-data")
            } header: {
                Text("App")
            }

            Section {
                NavigationLink("About Codex Meter") {
                    AboutView()
                }
                NavigationLink("What's new in \(WhatsNew.release)") {
                    WhatsNewView()
                }
                .accessibilityIdentifier("settings-whats-new")
            } header: {
                Text("About")
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        .savesSettings()
    }

    /// Settings → Providers' summary: how many tabs are on and how many accounts are connected.
    private var providersSummary: String {
        let connected = model.connectedProviders.count
        return "\(model.enabledProviders.count) on · "
            + (connected == 0 ? "none signed in" : "\(connected) signed in")
    }
}

private struct SavesSettings: ViewModifier {
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        content.onChange(of: model.settings) { _, settings in
            model.save(settings: settings)
        }
    }
}

extension View {
    /// Every settings page saves its own edits, so a pushed page never depends on its parent.
    func savesSettings() -> some View {
        modifier(SavesSettings())
    }
}

/// Usage > Dashboard: the selected provider's layout, the Priority tab, usage estimates and
/// refresh.
struct DashboardSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        // Edit dashboard follows the selected tab; each provider keeps its own layout.
        let dashboardProvider = model.selectedProvider

        Form {
            Section {
                NavigationLink {
                    DashboardEditView(provider: dashboardProvider)
                } label: {
                    LabeledContent {
                        Text(dashboardProvider.displayName)
                    } label: {
                        Label("Edit dashboard", systemImage: "slider.horizontal.3")
                    }
                }
            } footer: {
                Text("Choose and order \(dashboardProvider.displayName)'s cards. Each provider keeps its own layout; switch tabs to edit another one.")
            }

            Section {
                Toggle("Priority tab", isOn: $model.settings.showPriority)
                    .accessibilityIdentifier("settings-priority-tab")
                Toggle("Model slowdown warnings", isOn: $model.settings.slowdownWarningsEnabled)
                    .disabled(!model.settings.showPriority)
            } footer: {
                Text("Priority comes first and lists only what needs you across every connected service. Model slowdown warnings follow the newest Codex, Claude and Grok models' public OpenRouter speed, not your own requests.")
            }

            Section {
                Toggle("Usage estimates", isOn: $model.settings.usagePaceEnabled)
                Picker("Fast-usage warnings", selection: $model.settings.usagePaceSensitivity) {
                    ForEach(UsagePaceSensitivity.allCases, id: \.self) { sensitivity in
                        Text(sensitivity.title).tag(sensitivity)
                    }
                }
                .disabled(!model.settings.usagePaceEnabled)
            } footer: {
                Text("Estimates how long each allowance may last from its usage and local history. Fast-usage warnings turn a meter orange; Off keeps the estimate without the warning.")
            }

            Section {
                Toggle("Refresh on launch", isOn: $model.settings.refreshOnLaunch)
                Picker("Mode", selection: $model.settings.refreshMode) {
                    Text("Automatic").tag(RefreshMode.automatic)
                    Text("Manual").tag(RefreshMode.manual)
                }
                if model.settings.refreshMode == .manual {
                    Picker("Preferred interval", selection: $model.settings.refreshMinutes) {
                        ForEach(AppSettings.allowedRefreshMinutes, id: \.self) { minutes in
                            Text(minutes == 120 ? "2 hours" : "\(minutes) minutes").tag(minutes)
                        }
                    }
                }
            } header: {
                Text("Refresh")
            } footer: {
                Text(model.settings.refreshMode == .automatic
                    ? "Automatic adapts on device to remaining usage, reset timing, recent attention, quiet hours, fast usage and failures. The system still decides when background work runs."
                    : "The system decides when background work runs. This interval is an earliest preference, not a guaranteed schedule.")
            }
        }
        .navigationTitle("Dashboard")
        .navigationBarTitleDisplayMode(.inline)
        .savesSettings()
    }
}

/// Usage > Reset credits: one section per provider that has resets.
struct ResetCreditsSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingLowUsageAutoUse = false

    var body: some View {
        @Bindable var model = model

        Form {
            Section {
                LabeledContent("Your resets", value: codexInventory)
                Toggle("Use before they expire", isOn: $model.settings.creditExpiryAutoApplyEnabled)
                if model.settings.creditExpiryAutoApplyEnabled {
                    Picker("When to use it", selection: $model.settings.creditExpiryAutoApplyLeadMinutes) {
                        ForEach(CreditExpiryAutoApply.leadMinuteOptions, id: \.self) { minutes in
                            Text("\(CreditExpiryAutoApply.leadLabel(minutes)) before").tag(minutes)
                        }
                    }
                }
                Toggle("Use when running low", isOn: lowUsageAutoUseBinding)
                if model.settings.lowUsageAutoUseEnabled {
                    Picker("Use when remaining reaches", selection: $model.settings.lowUsageAutoUseThreshold) {
                        ForEach(LowUsageAutoUse.thresholdOptions, id: \.self) { value in
                            Text(value == 0 ? "0% (limit reached)" : "\(value)%").tag(value)
                        }
                    }
                }
            } header: {
                Text("Codex")
            } footer: {
                Text("Banked resets are used automatically so they are not lost. Running-low use watches the 5-hour and weekly limits, fires once per window, and skips a limit that refills on its own within 15 minutes. It runs on the next refresh, so allow some slack.")
            }
            .disabled(model.mode == .signedOut)
            .alert("Use resets automatically?", isPresented: $confirmingLowUsageAutoUse) {
                Button("Cancel", role: .cancel) {}
                Button("Turn on") { model.settings.lowUsageAutoUseEnabled = true }
            } message: {
                Text("Codex Meter will spend one banked reset when your remaining usage reaches the level you choose. Used resets cannot be undone.")
            }

            if model.enabledProviders.contains(.claudeCode) || model.claude.phase != .signedOut {
                ClaudeResetCreditsSettingsSection()
            }
        }
        .navigationTitle("Reset credits")
        .navigationBarTitleDisplayMode(.inline)
        .savesSettings()
    }

    private var codexInventory: String {
        let count = model.credits?.availableCount ?? model.usage?.resetCreditsAvailable ?? 0
        return count == 1 ? "1 reset" : "\(count) resets"
    }

    /// Turning the running-low rule on asks first, because it spends credits on its own.
    private var lowUsageAutoUseBinding: Binding<Bool> {
        Binding(
            get: { model.settings.lowUsageAutoUseEnabled },
            set: { enabled in
                if enabled {
                    confirmingLowUsageAutoUse = true
                } else {
                    model.settings.lowUsageAutoUseEnabled = false
                }
            }
        )
    }
}

/// Alerts & live usage > Notifications.
struct NotificationsSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        @Bindable var model = model

        Form {
            Section {
                Toggle("Allow notifications", isOn: $model.settings.notificationsEnabled)
                    .onChange(of: model.settings.notificationsEnabled) { _, enabled in
                        Task { await model.notificationsChanged(enabled: enabled) }
                    }
                LabeledContent("System permission", value: model.notificationPermissionState.title)
            }

            Section {
                Picker("Alert for", selection: $model.settings.alertMetric) {
                    ForEach(AlertMetric.allCases) { metric in
                        Text(metric.title).tag(metric)
                    }
                }
                Picker("Alert when remaining reaches", selection: $model.settings.alertThreshold) {
                    Text("Always").tag(100)
                    ForEach([10, 25, 50, 75], id: \.self) { value in
                        Text("\(value)% or lower").tag(value)
                    }
                }
            } header: {
                Text("Low allowance")
            } footer: {
                Text("The one low-allowance level for every alert, and for starting live usage on its own.")
            }

            Section {
                Toggle("Surprise limit refills", isOn: $model.settings.unexpectedRefillAlertsEnabled)
                Toggle("New reset credits", isOn: $model.settings.creditIncreaseAlertsEnabled)
                Toggle("Expiring reset credits", isOn: $model.settings.creditExpiryRemindersEnabled)
                if model.settings.creditExpiryRemindersEnabled {
                    ForEach(AppSettings.allowedCreditExpiryLeadMinutes, id: \.self) { minutes in
                        Toggle(isOn: leadTimeBinding(minutes)) {
                            Text(Self.leadTimeTitle(minutes))
                        }
                    }
                }
                Toggle("New models", isOn: $model.settings.notifyNewModels)
            } footer: {
                if model.settings.creditExpiryRemindersEnabled {
                    Text("Expiry reminders fire at each selected time before an available credit expires. At least one stays on.")
                }
            }

            Section {
                Button("Send test notification") {
                    Task { await model.sendTestNotification() }
                }
                Button("Open notification settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        openURL(url)
                    }
                }
            } footer: {
                if model.mode == .signedOut {
                    Text("Sign in or open demo mode to set up usage alerts.")
                }
            }
        }
        .disabled(model.mode == .signedOut)
        .navigationTitle("Notifications")
        .navigationBarTitleDisplayMode(.inline)
        .savesSettings()
        .task {
            await model.refreshNotificationPermissionState()
        }
    }

    private func leadTimeBinding(_ minutes: Int) -> Binding<Bool> {
        Binding(
            get: { model.settings.creditExpiryLeadMinutes.contains(minutes) },
            set: { isOn in
                var settings = model.settings
                let currentlyOn = settings.creditExpiryLeadMinutes.contains(minutes)
                if isOn != currentlyOn {
                    settings.toggleCreditExpiryLeadMinutes(minutes)
                }
                // Keep at least the default when the user clears every lead time.
                if settings.creditExpiryLeadMinutes.isEmpty {
                    settings.creditExpiryLeadMinutes = AppSettings.defaultCreditExpiryLeadMinutes
                }
                model.settings = settings
            }
        )
    }

    private static func leadTimeTitle(_ minutes: Int) -> String {
        switch minutes {
        case 60: return "Remind 1 hour before"
        case 360: return "Remind 6 hours before"
        case 720: return "Remind 12 hours before"
        case 1_440: return "Remind 1 day before"
        case 2_880: return "Remind 2 days before"
        case 10_080: return "Remind 1 week before"
        default:
            if minutes % 1_440 == 0 {
                let days = minutes / 1_440
                return "Remind \(days) day\(days == 1 ? "" : "s") before"
            }
            if minutes % 60 == 0 {
                let hours = minutes / 60
                return "Remind \(hours) hour\(hours == 1 ? "" : "s") before"
            }
            return "Remind \(minutes) minutes before"
        }
    }
}

#if os(iOS)
/// Alerts & live usage > Live Activity: the tightest limit on the Lock Screen and in the
/// Dynamic Island. Auto-start uses the one Low allowance level from Notifications.
struct LiveActivitySettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var isRunning = LiveActivityController.shared.isRunning

    var body: some View {
        @Bindable var model = model
        let state = model.liveUsageState()

        Form {
            Section {
                if let state {
                    LabeledContent("Shows", value: "\(state.title), \(state.remainingPercent)% left")
                }
                Button(isRunning ? "Stop Live Activity" : "Start Live Activity") {
                    Task {
                        if isRunning {
                            await LiveActivityController.shared.stop(dismissing: state)
                        } else if let state {
                            await LiveActivityController.shared.start(state)
                        }
                        isRunning = LiveActivityController.shared.isRunning
                    }
                }
                .disabled(!isRunning && (state == nil || !LiveActivityController.shared.isEnabledBySystem))
                .accessibilityIdentifier("live-activity-toggle")
            } footer: {
                if !LiveActivityController.shared.isEnabledBySystem {
                    Text("Live Activities are off for Codex Meter in the Settings app.")
                } else {
                    Text("Follows whichever connected limit is closest to running out. It updates when Codex Meter refreshes, and says when it last did.")
                }
            }

            Section {
                Toggle("When allowance is low", isOn: $model.settings.liveActivityWhenLow)
                Toggle("When usage is fast", isOn: $model.settings.liveActivityWhenFast)
            } header: {
                Text("Start automatically")
            } footer: {
                Text(model.settings.alertThreshold == 100
                    ? "Low uses the level in Notifications, which is set to Always."
                    : "Low means \(model.settings.alertThreshold)% or less left, the level set in Notifications. Once you stop it, it stays off until that limit resets.")
            }
        }
        .navigationTitle("Live Activity")
        .navigationBarTitleDisplayMode(.inline)
        .savesSettings()
    }
}
#endif

/// App > Privacy & data: usage history, settings export and import, diagnostics, and the
/// privacy notes, on one page.
struct PrivacyDataSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var isExportingSettings = false
    @State private var isImportingSettings = false
    @State private var transferMessage: String?

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    UsageHistoryView()
                } label: {
                    Label("View usage history", systemImage: "chart.xyaxis.line")
                }
            } footer: {
                Text("Usage samples stay on this device.")
            }

            Section {
                Button {
                    isExportingSettings = true
                } label: {
                    Label("Export settings", systemImage: "square.and.arrow.up")
                }
                Button {
                    isImportingSettings = true
                } label: {
                    Label("Import settings", systemImage: "square.and.arrow.down")
                }
            } footer: {
                Text("Exports include dashboard order, visibility and hidden model-specific sections, but never credentials or usage data.")
            }

            if model.diagnosticsUnlocked {
                Section {
                    NavigationLink("Diagnostic logs") {
                        DiagnosticsView()
                    }
                } footer: {
                    Text("Opt-in tracing writes sanitized logs on this device. Credentials, emails, JWTs and OAuth query parameters are redacted.")
                }
            }

            Section {
                NavigationLink("Privacy policy") {
                    PrivacyPolicyView()
                }
            } footer: {
                Text("Sign-ins stay in this device's Keychain and go only to each provider. Codex Meter has no server and no analytics.")
            }
        }
        .navigationTitle("Privacy & data")
        .navigationBarTitleDisplayMode(.inline)
        .savesSettings()
        .fileExporter(
            isPresented: $isExportingSettings,
            document: SettingsTransferDocument(settings: model.settings),
            contentType: .json,
            defaultFilename: "CodexMeter-Settings"
        ) { result in
            switch result {
            case .success:
                transferMessage = "Settings exported."
            case let .failure(error):
                transferMessage = "Settings could not be exported: \(error.localizedDescription)"
            }
        }
        .fileImporter(
            isPresented: $isImportingSettings,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false
        ) { result in
            do {
                let urls = try result.get()
                guard let url = urls.first else {
                    throw CocoaError(.fileReadNoSuchFile)
                }
                let isScoped = url.startAccessingSecurityScopedResource()
                defer {
                    if isScoped {
                        url.stopAccessingSecurityScopedResource()
                    }
                }
                let imported = try SettingsTransferDocument.decode(
                    Data(contentsOf: url, options: [.mappedIfSafe, .uncached])
                )
                model.save(settings: imported)
                transferMessage = "Settings imported."
            } catch {
                transferMessage = "Settings could not be imported: \(error.localizedDescription)"
            }
        }
        .alert(
            "Settings transfer",
            isPresented: Binding(
                get: { transferMessage != nil },
                set: { if !$0 { transferMessage = nil } }
            )
        ) {
            Button("OK") { transferMessage = nil }
        } message: {
            Text(transferMessage ?? "")
        }
    }
}
