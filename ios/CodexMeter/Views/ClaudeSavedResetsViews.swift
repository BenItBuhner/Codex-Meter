import CodexMeterCore
import SwiftUI

/// Claude Code's saved usage-limit resets on its dashboard: what is banked, what each kind
/// refills, and a button to spend one.
struct ClaudeSavedResetsCard: View {
    @Environment(AppModel.self) private var model
    @State private var confirming: ClaudeSavedResets.Choice?

    var body: some View {
        let claude = model.claude
        let now = Date.now
        let choices = claude.savedResets?.choices(at: now) ?? []
        VStack(alignment: .leading, spacing: 14) {
            Text("Reset credits")
                .font(.headline.bold())
            Text(claude.savedResets?.availableTitle(at: now) ?? "No resets available")
                .font(.title3.bold())
            if choices.isEmpty {
                Text("Anthropic sometimes gives Claude Code saved resets. They show up here when it does.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(choices) { choice in
                ClaudeSavedResetRow(choice: choice) { confirming = choice }
            }
            if let message = claude.resetMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("claude-reset-message")
            }
            Button {
                Task { await claude.checkWithAnthropic() }
            } label: {
                if claude.isCheckingResets {
                    ProgressView()
                } else {
                    Text("Check with Anthropic")
                }
            }
            .buttonStyle(.bordered)
            .disabled(claude.isCheckingResets || claude.isClaimingReset)
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .cardSurface()
        .accessibilityIdentifier("claude-saved-resets-card")
        .confirmationDialog(
            "Use a \(confirming?.title ?? "reset")?",
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            titleVisibility: .visible,
            presenting: confirming
        ) { choice in
            Button("Use 1 reset") {
                Task { await claude.useSavedReset(grantID: choice.grantID) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { choice in
            Text("\(choice.refills) A used reset can't be undone.")
        }
    }
}

private struct ClaudeSavedResetRow: View {
    let choice: ClaudeSavedResets.Choice
    let use: () -> Void
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(choice.countedTitle)
                    .font(.subheadline.bold())
                Spacer(minLength: 8)
                Button("Use", action: use)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(!choice.usableNow || model.claude.isClaimingReset)
            }
            Text(choice.refills)
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let expiry = choice.nextExpiry, expiry > .now {
                Text("Expires \(expiry, style: .relative)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if !choice.held.isEmpty {
                Text(choice.held)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// Settings > Reset credits > Claude Code. The same rules as the Codex reset credits.
struct ClaudeResetCreditsSettingsSection: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingLowUsage = false

    var body: some View {
        @Bindable var model = model
        let claude = model.claude
        Section {
            LabeledContent("Your resets", value: claude.savedResets?.availableTitle(at: .now) ?? "No resets available")
            Toggle("Use before they expire", isOn: $model.settings.claudeResetExpiryAutoUseEnabled)
            if model.settings.claudeResetExpiryAutoUseEnabled {
                Picker("When to use it", selection: $model.settings.claudeResetExpiryLeadMinutes) {
                    ForEach(CreditExpiryAutoApply.leadMinuteOptions, id: \.self) { minutes in
                        Text("\(CreditExpiryAutoApply.leadLabel(minutes)) before").tag(minutes)
                    }
                }
            }
            Toggle("Use when running low", isOn: lowUsageBinding)
            if model.settings.claudeResetLowUsageAutoUseEnabled {
                Picker("Use when remaining reaches", selection: $model.settings.claudeResetLowUsageThreshold) {
                    ForEach(LowUsageAutoUse.thresholdOptions, id: \.self) { value in
                        Text(value == 0 ? "0% (limit reached)" : "\(value)%").tag(value)
                    }
                }
            }
            Button {
                Task { await claude.checkWithAnthropic() }
            } label: {
                LabeledContent("Check with Anthropic") {
                    if claude.isCheckingResets { ProgressView() }
                }
            }
            .disabled(claude.isCheckingResets)
            if let message = claude.resetMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Claude Code")
        } footer: {
            Text("Saved resets refill a Claude Code limit you've hit. Anthropic spends nothing when no limit is in the way. Running-low use watches the 5-hour and weekly limits and fires once per window.")
        }
        .disabled(claude.phase == .signedOut)
        .alert("Use resets automatically?", isPresented: $confirmingLowUsage) {
            Button("Cancel", role: .cancel) {}
            Button("Turn on") { model.settings.claudeResetLowUsageAutoUseEnabled = true }
        } message: {
            Text("Codex Meter will spend one saved Claude Code reset when your remaining usage reaches the level you choose. Used resets can't be undone.")
        }
    }

    private var lowUsageBinding: Binding<Bool> {
        Binding(
            get: { model.settings.claudeResetLowUsageAutoUseEnabled },
            set: { enabled in
                if enabled {
                    confirmingLowUsage = true
                } else {
                    model.settings.claudeResetLowUsageAutoUseEnabled = false
                }
            }
        )
    }
}
