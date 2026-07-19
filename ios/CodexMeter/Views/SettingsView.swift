import CodexMeterCore
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var model = model
        List {
            Section {
                SettingsDestination(
                    title: "Account",
                    detail: accountSummary,
                    systemImage: "person.crop.circle"
                ) { AccountSettingsView() }
            }

            Section {
                SettingsDestination(title: "Appearance", detail: model.settings.appearance.title, systemImage: "circle.lefthalf.filled") {
                    AppearanceSettingsView()
                }
                SettingsDestination(title: "Refresh & Usage", detail: paceSummary, systemImage: "gauge.with.dots.needle.67percent") {
                    RefreshUsageSettingsView()
                }
                SettingsDestination(title: "Notifications", detail: model.settings.notificationsEnabled ? "On" : "Off", systemImage: "bell.badge") {
                    NotificationSettingsView()
                }
                SettingsDestination(title: "Live Activity", detail: model.isLiveMonitorActive ? "Running" : "Off", systemImage: "dot.radiowaves.left.and.right") {
                    LiveActivitySettingsView()
                }
            }

            Section {
                SettingsDestination(title: "Data & Privacy", detail: "Transfer and storage", systemImage: "lock.shield") {
                    DataPrivacySettingsView()
                }
                SettingsDestination(title: "About", detail: "Version and acknowledgements", systemImage: "info.circle") {
                    AboutView()
                }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        .onChange(of: model.settings) { _, settings in model.save(settings: settings) }
        .task { await model.refreshNotificationPermissionState() }
    }

    private var accountSummary: String {
        switch model.mode {
        case .live: model.accountEmail.isEmpty ? "Connected" : model.accountEmail
        case .demo: "Demo"
        case .signedOut: "Not connected"
        }
    }

    private var paceSummary: String {
        guard model.settings.usagePaceEnabled else { return "Pace estimates off" }
        return "Every \(model.settings.refreshMinutes) min · \(model.settings.usagePaceSensitivity.title)"
    }
}

private struct SettingsDestination<Destination: View>: View {
    let title: String
    let detail: String
    let systemImage: String
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink(destination: destination()) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            } icon: {
                Image(systemName: systemImage)
                    .foregroundStyle(.tint)
                    .frame(width: 24)
            }
        }
    }
}

private struct AccountSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingSignOut = false

    var body: some View {
        Form {
            Section {
                switch model.mode {
                case .live:
                    LabeledContent("ChatGPT account", value: model.accountEmail.isEmpty ? "Connected" : model.accountEmail)
                    if !model.accountPlan.isEmpty { LabeledContent("Plan", value: model.accountPlan) }
                    Button("Sign out", role: .destructive) { confirmingSignOut = true }
                case .demo:
                    LabeledContent("Mode", value: "Demo")
                    Button("Leave Demo", role: .destructive) {
                        Task { await model.leaveDemo(); dismiss() }
                    }
                case .signedOut:
                    Text("Connect your account to load live usage. Credentials are stored only in Keychain.")
                        .foregroundStyle(.secondary)
                    Button("Sign in with ChatGPT") {
                        model.isShowingSettings = false
                        model.isShowingSignIn = true
                    }
                    Button("Explore Demo") { Task { await model.enterDemo() } }
                }
            }
        }
        .navigationTitle("Account")
        .confirmationDialog("Sign out of ChatGPT?", isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await model.signOut(); dismiss() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Credentials, cached account data, widgets, watch data, and the Live Activity will be cleared.")
        }
    }
}

private struct AppearanceSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Picker("Appearance", selection: $model.settings.appearance) {
                    ForEach(AppAppearance.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
            } footer: {
                Text("System follows the device appearance and preserves increased-contrast settings.")
            }
        }
        .navigationTitle("Appearance")
    }
}

private struct RefreshUsageSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section("Refresh") {
                Toggle("Refresh on launch", isOn: $model.settings.refreshOnLaunch)
                Picker("Preferred interval", selection: $model.settings.refreshMinutes) {
                    ForEach(AppSettings.allowedRefreshMinutes, id: \.self) { minutes in
                        Text(minutes == 120 ? "2 hours" : "\(minutes) minutes").tag(minutes)
                    }
                }
            }

            Section {
                Toggle("Show usage pace estimates", isOn: $model.settings.usagePaceEnabled)
                if model.settings.usagePaceEnabled {
                    Picker("Accelerated-usage warnings", selection: $model.settings.usagePaceSensitivity) {
                        ForEach(UsagePaceSensitivity.allCases) { Text($0.title).tag($0) }
                    }
                }
            } header: {
                Text("Usage pace")
            } footer: {
                Text("Projects when allowance may run out from the full current-window average. Off keeps estimates visible but suppresses warning color and automatic Live Activity starts.")
            }
        }
        .navigationTitle("Refresh & Usage")
    }
}

private struct NotificationSettingsView: View {
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
                Button("Open System Settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
                }
            }

            Section("Usage") {
                Picker("Low usage alerts", selection: $model.settings.alertMetric) {
                    ForEach(AlertMetric.allCases) { Text($0.title).tag($0) }
                }
                Picker("Remaining threshold", selection: $model.settings.alertThreshold) {
                    Text("Always").tag(100)
                    ForEach([10, 25, 50, 75], id: \.self) { Text("\($0)% or lower").tag($0) }
                }
                Toggle("Unexpected refill alerts", isOn: $model.settings.unexpectedRefillAlertsEnabled)
            }

            Section("Reset credits") {
                Toggle("New credit alerts", isOn: $model.settings.creditIncreaseAlertsEnabled)
                Toggle("Expiry reminders", isOn: $model.settings.creditExpiryRemindersEnabled)
                if model.settings.creditExpiryRemindersEnabled {
                    ForEach(AppSettings.allowedCreditExpiryLeadMinutes, id: \.self) { minutes in
                        Toggle(leadTimeTitle(minutes), isOn: leadTimeBinding(minutes))
                    }
                }
            }

            Section { Button("Send test notification") { Task { await model.sendTestNotification() } } }
        }
        .disabled(model.mode == .signedOut)
        .navigationTitle("Notifications")
    }

    private func leadTimeBinding(_ minutes: Int) -> Binding<Bool> {
        Binding(
            get: { model.settings.creditExpiryLeadMinutes.contains(minutes) },
            set: { enabled in
                var settings = model.settings
                if enabled != settings.creditExpiryLeadMinutes.contains(minutes) {
                    settings.toggleCreditExpiryLeadMinutes(minutes)
                }
                if settings.creditExpiryLeadMinutes.isEmpty {
                    settings.creditExpiryLeadMinutes = AppSettings.defaultCreditExpiryLeadMinutes
                }
                model.settings = settings
            }
        )
    }
}

private struct LiveActivitySettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                LabeledContent("Status", value: model.isLiveMonitorActive ? "Running" : (LiveActivityCoordinator.shared.isSupported ? "Off" : "Disabled by system"))
                if model.isLiveMonitorActive {
                    Button("Stop Live Activity", role: .destructive) { Task { await model.stopLiveMonitor(dismissed: true) } }
                } else {
                    Button("Start Live Activity") { Task { await model.startLiveMonitor() } }
                        .disabled(model.mode == .signedOut || model.usage == nil)
                }
                Toggle("Auto-start on accelerated usage", isOn: $model.settings.liveMonitorAutoStartOnAcceleratedUsage)
                    .disabled(!model.settings.usagePaceEnabled || !model.settings.usagePaceSensitivity.warningsEnabled)
                if let message = model.liveMonitorMessage {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
            } footer: {
                Text("Shows focused usage on the Lock Screen, Dynamic Island, and Apple Watch Smart Stack. It updates after refresh and ends at reset or sign-out.")
            }
        }
        .navigationTitle("Live Activity")
    }
}

private struct DataPrivacySettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var exportDocument: TransferFileDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var transferError: String?

    var body: some View {
        Form {
            Section {
                Label("OAuth tokens stay in Keychain", systemImage: "key.fill")
                Label("Widgets and Apple Watch receive only sanitized usage", systemImage: "applewatch")
                Label("Requests go directly from this device to OpenAI", systemImage: "network")
            }

            Section {
                Button("Export settings…") { prepareExport() }
                Button("Import settings…") { showImporter = true }
                if let transferError { Text(transferError).font(.footnote).foregroundStyle(.red) }
                if let status = model.transferStatusMessage { Text(status).font(.footnote).foregroundStyle(.secondary) }
            } header: {
                Text("Settings transfer")
            } footer: {
                Text("The versioned JSON includes appearance, refresh, usage-warning, notification, and Live Activity preferences. It never includes tokens, identity, cached usage, or Keychain data.")
            }

            Section { NavigationLink("Privacy policy") { PrivacyPolicyView() } }
        }
        .navigationTitle("Data & Privacy")
        .fileExporter(isPresented: $showExporter, document: exportDocument, contentType: .json, defaultFilename: SettingsTransfer.defaultFileName()) { result in
            switch result {
            case .success: model.transferStatusMessage = "Settings exported."
            case .failure(let error): transferError = error.localizedDescription
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json, .data], allowsMultipleSelection: false) { result in
            Task { await importFile(result) }
        }
    }

    private func prepareExport() {
        transferError = nil
        model.transferStatusMessage = nil
        do {
            exportDocument = TransferFileDocument(data: try model.exportTransferData())
            showExporter = true
        } catch { transferError = error.localizedDescription }
    }

    private func importFile(_ result: Result<[URL], Error>) async {
        transferError = nil
        model.transferStatusMessage = nil
        do {
            guard let url = try result.get().first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            try await model.importTransferData(Data(contentsOf: url))
        } catch { transferError = error.localizedDescription }
    }
}

private func leadTimeTitle(_ minutes: Int) -> String {
    switch minutes {
    case 60: "1 hour before"
    case 360: "6 hours before"
    case 720: "12 hours before"
    case 1_440: "1 day before"
    case 2_880: "2 days before"
    case 10_080: "1 week before"
    default: "\(minutes) minutes before"
    }
}

private struct TransferFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
