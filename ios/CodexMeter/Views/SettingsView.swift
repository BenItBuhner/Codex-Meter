import CodexMeterCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var confirmingSignOut = false

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
                Toggle("Usage pace estimates", isOn: $model.settings.usagePaceEnabled)
                if model.settings.usagePaceEnabled {
                    Picker("Sensitivity", selection: $model.settings.usagePaceSensitivity) {
                        ForEach(UsagePace.Sensitivity.allCases) { sensitivity in
                            Text(sensitivity.title).tag(sensitivity)
                        }
                    }
                }
            } header: {
                Text("Usage pace")
            } footer: {
                Text("Projects how long the current average consumption rate would take to exhaust the window. Orange highlights mean you are on track to run out before the scheduled reset.")
            }
            .disabled(model.mode == .signedOut)

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
