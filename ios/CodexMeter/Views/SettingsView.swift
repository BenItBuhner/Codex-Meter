import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var confirmingSignOut = false
    @State private var exportIncludeAuth = false
    @State private var importIncludeAuth = false
    @State private var confirmingAuthExport = false
    @State private var confirmingAuthImport = false
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var exportDocument: TransferFileDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var transferError: String?

    var body: some View {
        @Bindable var model = model

        Form {
            Section("Account") {
                if model.mode == .live {
                    LabeledContent("ChatGPT account", value: model.accountEmail.isEmpty ? "Connected" : model.accountEmail)
                    if !model.accountPlan.isEmpty {
                        LabeledContent("Plan", value: model.accountPlan)
                    }
                    Button("Sign out", role: .destructive) {
                        confirmingSignOut = true
                    }
                } else if model.mode == .demo {
                    LabeledContent("Mode", value: "Demo")
                    Button("Leave Demo", role: .destructive) {
                        Task {
                            await model.leaveDemo()
                            dismiss()
                        }
                    }
                } else {
                    Button("Sign in with ChatGPT") {
                        dismiss()
                        model.isShowingSignIn = true
                    }
                }
            }

            Section("Appearance") {
                Picker("Appearance", selection: $model.settings.appearance) {
                    ForEach(AppAppearance.allCases) { appearance in
                        Text(appearance.title).tag(appearance)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section {
                Toggle("Refresh on launch", isOn: $model.settings.refreshOnLaunch)
                Picker("Preferred interval", selection: $model.settings.refreshMinutes) {
                    ForEach([5, 10, 15, 30, 60, 120], id: \.self) { minutes in
                        Text(minutes == 120 ? "2 hours" : "\(minutes) minutes").tag(minutes)
                    }
                }
            } header: {
                Text("Refresh")
            } footer: {
                Text("iOS decides when background work runs. This interval is an earliest preference, not a guaranteed schedule.")
            }

            Section {
                Toggle("Allow notifications", isOn: $model.settings.notificationsEnabled)
                    .onChange(of: model.settings.notificationsEnabled) { _, enabled in
                        Task { await model.notificationsChanged(enabled: enabled) }
                    }
                LabeledContent("System permission", value: model.notificationPermissionState.title)

                Picker("Low usage alerts", selection: $model.settings.alertMetric) {
                    ForEach(AlertMetric.allCases) { metric in
                        Text(metric.title).tag(metric)
                    }
                }
                Picker("Low when remaining reaches", selection: $model.settings.alertThreshold) {
                    Text("Always").tag(100)
                    ForEach([10, 25, 50, 75], id: \.self) { value in
                        Text("\(value)% or lower").tag(value)
                    }
                }

                Toggle("New reset-credit alerts", isOn: $model.settings.creditIncreaseAlertsEnabled)
                Toggle("Unexpected refill alerts", isOn: $model.settings.unexpectedRefillAlertsEnabled)
                Toggle("Reset-credit expiry reminders", isOn: $model.settings.creditExpiryRemindersEnabled)

                if model.settings.creditExpiryRemindersEnabled {
                    ForEach(AppSettings.allowedCreditExpiryLeadMinutes, id: \.self) { minutes in
                        Toggle(isOn: leadTimeBinding(minutes, model: model)) {
                            Text(Self.leadTimeTitle(minutes))
                        }
                    }
                }

                Button("Send test notification") {
                    Task { await model.sendTestNotification() }
                }
                Button("Open notification settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        openURL(url)
                    }
                }
            } header: {
                Text("Notifications")
            } footer: {
                if model.mode == .signedOut {
                    Text("Sign in or open demo mode to configure usage alerts.")
                } else if model.settings.creditExpiryRemindersEnabled {
                    Text("Expiry reminders fire at each selected lead time before an available credit expires. Choose at least one lead time.")
                } else {
                    Text("Includes low usage, scheduled resets, optional surprise refills, and reset-credit inventory changes.")
                }
            }
            .disabled(model.mode == .signedOut)

            Section {
                LabeledContent(
                    "Live Activities",
                    value: model.isLiveMonitorActive
                        ? "Running"
                        : (LiveActivityCoordinator.shared.isSupported ? "Off" : "Disabled")
                )
                if model.isLiveMonitorActive {
                    Button("Stop usage monitor") {
                        Task { await model.stopLiveMonitor(dismissed: true) }
                    }
                } else {
                    Button("Start usage monitor") {
                        Task { await model.startLiveMonitor() }
                    }
                    .disabled(model.mode == .signedOut || model.usage == nil)
                }
                Toggle("Auto-start when low", isOn: $model.settings.liveMonitorAutoStartEnabled)
                if model.settings.liveMonitorAutoStartEnabled {
                    Picker("Auto-start windows", selection: $model.settings.liveMonitorAutoStartMetric) {
                        ForEach(AlertMetric.allCases) { metric in
                            Text(metric.title).tag(metric)
                        }
                    }
                    Picker("Auto-start at remaining", selection: $model.settings.liveMonitorAutoStartThreshold) {
                        ForEach([10, 25, 50, 75, 100], id: \.self) { value in
                            Text(value == 100 ? "Always when data exists" : "\(value)% or lower").tag(value)
                        }
                    }
                }
                if let liveMonitorMessage = model.liveMonitorMessage {
                    Text(liveMonitorMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Usage monitor")
            } footer: {
                Text("Shows five-hour and weekly remaining on the Lock Screen and Dynamic Island until the next reset, you stop it, or you sign out. iOS cannot guarantee exact background timing like Android alarms.")
            }
            .disabled(model.mode == .signedOut)

            Section {
                Toggle("Include ChatGPT credentials", isOn: $exportIncludeAuth)
                Button {
                    if exportIncludeAuth {
                        confirmingAuthExport = true
                    } else {
                        Task { await prepareExport(includeAuth: false) }
                    }
                } label: {
                    if isExporting {
                        ProgressView()
                    } else {
                        Text("Export settings…")
                    }
                }
                .disabled(isExporting)

                Toggle("Import ChatGPT credentials", isOn: $importIncludeAuth)
                Button {
                    if importIncludeAuth {
                        confirmingAuthImport = true
                    } else {
                        showImporter = true
                    }
                } label: {
                    if isImporting {
                        ProgressView()
                    } else {
                        Text("Import settings…")
                    }
                }
                .disabled(isImporting)

                if let transferError {
                    Text(transferError)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
                if let status = model.transferStatusMessage {
                    Text(status)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Transfer")
            } footer: {
                Text("Exports a JSON file with app and notification preferences. Including credentials embeds live OAuth tokens — only store that file on devices you trust.")
            }

            Section {
                NavigationLink("About Codex Meter") {
                    AboutView()
                }
                NavigationLink("Privacy policy") {
                    PrivacyPolicyView()
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
        .onChange(of: model.settings) { _, settings in
            model.save(settings: settings)
        }
        .task {
            await model.refreshNotificationPermissionState()
        }
        .confirmationDialog(
            "Sign out of ChatGPT?",
            isPresented: $confirmingSignOut,
            titleVisibility: .visible
        ) {
            Button("Sign out", role: .destructive) {
                Task {
                    await model.signOut()
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Credentials and cached account data will be removed from this device and its widgets.")
        }
        .confirmationDialog(
            "Export credentials?",
            isPresented: $confirmingAuthExport,
            titleVisibility: .visible
        ) {
            Button("Export with credentials", role: .destructive) {
                Task { await prepareExport(includeAuth: true) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(SettingsTransfer.securityWarning)
        }
        .confirmationDialog(
            "Import credentials?",
            isPresented: $confirmingAuthImport,
            titleVisibility: .visible
        ) {
            Button("Import including credentials", role: .destructive) {
                showImporter = true
            }
            Button("Cancel", role: .cancel) {
                importIncludeAuth = false
            }
        } message: {
            Text(SettingsTransfer.securityWarning + " Imported tokens replace any account currently signed in on this device.")
        }
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .json,
            defaultFilename: SettingsTransfer.defaultFileName()
        ) { result in
            if case .failure(let error) = result {
                transferError = error.localizedDescription
            } else {
                model.transferStatusMessage = "Settings exported."
            }
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.json, .data],
            allowsMultipleSelection: false
        ) { result in
            Task { await handleImport(result) }
        }
    }

    private func prepareExport(includeAuth: Bool) async {
        isExporting = true
        transferError = nil
        model.transferStatusMessage = nil
        defer { isExporting = false }
        do {
            let data = try await model.exportTransferData(
                options: SettingsTransfer.ExportOptions(
                    includeAppSettings: true,
                    includeNotifications: true,
                    includeAuthentication: includeAuth
                )
            )
            exportDocument = TransferFileDocument(data: data)
            showExporter = true
        } catch {
            transferError = error.localizedDescription
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) async {
        isImporting = true
        transferError = nil
        model.transferStatusMessage = nil
        defer { isImporting = false }
        do {
            let urls = try result.get()
            guard let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed { url.stopAccessingSecurityScopedResource() }
            }
            let data = try Data(contentsOf: url)
            try await model.importTransferData(
                data,
                options: SettingsTransfer.ImportOptions(
                    importAppSettings: true,
                    importNotifications: true,
                    importAuthentication: importIncludeAuth
                )
            )
        } catch {
            transferError = error.localizedDescription
        }
    }

    private func leadTimeBinding(_ minutes: Int, model: AppModel) -> Binding<Bool> {
        Binding(
            get: { model.settings.creditExpiryLeadMinutes.contains(minutes) },
            set: { isOn in
                var settings = model.settings
                let currentlyOn = settings.creditExpiryLeadMinutes.contains(minutes)
                if isOn != currentlyOn {
                    settings.toggleCreditExpiryLeadMinutes(minutes)
                }
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

private struct TransferFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
