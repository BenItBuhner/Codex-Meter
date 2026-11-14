import SwiftUI

/// Settings → Updates: the same options as Android's Updates page and the Windows and Linux apps.
struct MacUpdatesSettingsView: View {
    @State private var updater = MacUpdater.shared
    @State private var confirmingAlpha = false
    @State private var confirmingReturn = false

    var body: some View {
        Form {
            Section {
                LabeledContent {
                    actionButton
                } label: {
                    Text("Codex Meter updates")
                    Text(updater.statusText)
                        .accessibilityIdentifier("updates-status")
                }
                if updater.status == .downloading || updater.status == .installing {
                    ProgressView(value: updater.status == .installing ? nil : updater.progress)
                        .accessibilityIdentifier("updates-progress")
                }
                if let release = updater.available ?? updater.latest {
                    LabeledContent("Release notes") {
                        Link("What's new in \(release.version)", destination: release.pageURL)
                    }
                    if !release.notes.isEmpty {
                        Text(release.notes.split(separator: "\n").prefix(6).joined(separator: "\n"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(6)
                    }
                }
                if let reason = updater.swapBlockedReason, updater.available != nil {
                    Text(reason)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Updates")
            } footer: {
                Text("The download is checked against the release's SHA-256 checksums before anything is replaced. Codex Meter then quits, swaps itself in place and reopens.")
            }

            Section {
                Picker("Update channel", selection: Binding(
                    get: { updater.channel },
                    set: { choice in
                        if choice == .alpha, updater.channel != .alpha { confirmingAlpha = true } else { updater.channel = choice }
                    }
                )) {
                    ForEach(MacUpdateChannel.allCases) { channel in
                        Text(channel.title).tag(channel)
                    }
                }
                .accessibilityIdentifier("updates-channel")
                Toggle("Check automatically", isOn: $updater.autoCheck)
                    .accessibilityIdentifier("updates-auto")
                Picker("Check frequency", selection: $updater.checkHours) {
                    ForEach(MacUpdater.frequencies, id: \.self) { hours in
                        Text(MacUpdater.frequencyTitle(hours)).tag(hours)
                    }
                }
                .disabled(!updater.autoCheck)
                Toggle("Notify when an update is available", isOn: $updater.notify)
            } footer: {
                Text("Alpha gets new features first, with less testing. Each check reads at most two small responses from GitHub.")
            }
        }
        .confirmationDialog("Switch to alpha updates?", isPresented: $confirmingAlpha) {
            Button("Use Alpha") { updater.channel = .alpha }
        } message: {
            Text("Alpha builds get new features first and may have rough edges. You can switch back to Stable at any time.")
        }
        .confirmationDialog("Return to stable?", isPresented: $confirmingReturn) {
            Button("Install Stable") { updater.downloadAndInstall() }
        } message: {
            Text("This installs an older stable version over your alpha build. Your settings and accounts stay.")
        }
    }

    @ViewBuilder
    private var actionButton: some View {
        switch updater.status {
        case .checking:
            Button("Checking…") {}.disabled(true)
        case .downloading:
            Button("Cancel") { updater.cancel() }
        case .installing:
            Button("Installing…") {}.disabled(true)
        case .available where updater.isReturnToStable:
            Button("Return to Stable") { confirmingReturn = true }
                .accessibilityIdentifier("updates-action")
        case .available:
            Button(updater.swapBlockedReason == nil ? "Download and Install" : "Download Disk Image") { updater.downloadAndInstall() }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("updates-action")
        default:
            Button("Check for Updates") { Task { await updater.check(manual: true) } }
                .accessibilityIdentifier("updates-action")
        }
    }
}

/// App menu → Check for Updates…: runs a check and opens Settings on the Updates tab.
struct MacCheckForUpdatesButton: View {
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Button("Check for Updates…") {
            MacSettingsTab.shared.selected = "updates"
            openSettings()
            Task { await MacUpdater.shared.check(manual: true) }
        }
    }
}
