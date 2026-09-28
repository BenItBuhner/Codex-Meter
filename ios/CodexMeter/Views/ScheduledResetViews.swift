import CodexMeterCore
import SwiftUI

/// Arms a scheduled reset. Presented as its own sheet from the dashboard card and pushed
/// from the reset screen; `dismiss` handles both.
struct ScheduledResetView: View {
    enum Presentation {
        case sheet
        case pushed
    }

    private enum Mode: Hashable {
        case fiveHour
        case weekly
        case dateTime
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var presentation: Presentation = .sheet

    @State private var mode: Mode = .fiveHour
    @State private var thresholdChoice = 5
    @State private var customThreshold = 15
    @State private var fireAt = ScheduledResetPolicy.defaultFireDate(now: .now)
    @State private var conditionEnabled = false
    @State private var conditionChoice = 10
    @State private var customCondition = 15
    @State private var confirming = false

    private var trigger: ScheduledResetTrigger {
        switch mode {
        case .fiveHour:
            .threshold(window: .fiveHour, remainingPercent: resolved(thresholdChoice, custom: customThreshold))
        case .weekly:
            .threshold(window: .weekly, remainingPercent: resolved(thresholdChoice, custom: customThreshold))
        case .dateTime:
            .dateTime(
                fireAt: fireAt,
                onlyIfRemainingAtMost: conditionEnabled ? resolved(conditionChoice, custom: customCondition) : nil
            )
        }
    }

    private var watchedWindow: UsageWindow? {
        guard let usage = model.usage else { return nil }
        switch mode {
        case .fiveHour: return usage.fiveHour
        case .weekly: return usage.weekly
        case .dateTime: return nil
        }
    }

    private var notificationsAllowed: Bool {
        switch model.notificationPermissionState {
        case .authorized, .provisional, .ephemeral: true
        case .notDetermined, .denied: false
        }
    }

    var body: some View {
        Form {
            Section {
                Picker("Run the reset", selection: $mode) {
                    Text("5-hour limit").tag(Mode.fiveHour)
                    if model.usage?.weekly != nil {
                        Text("Weekly limit").tag(Mode.weekly)
                    }
                    Text("Date and time").tag(Mode.dateTime)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("scheduledReset.mode")

                switch mode {
                case .fiveHour, .weekly:
                    ThresholdPicker(
                        title: "Remaining reaches",
                        choice: $thresholdChoice,
                        custom: $customThreshold
                    )
                    if let watchedWindow {
                        LabeledContent("Right now", value: "\(watchedWindow.remainingPercent)% remaining")
                    }
                case .dateTime:
                    DatePicker(
                        "Date and time",
                        selection: $fireAt,
                        in: Date.now...,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                    Toggle("Only if remaining is at or below", isOn: $conditionEnabled)
                    if conditionEnabled {
                        ThresholdPicker(
                            title: "Remaining at or below",
                            choice: $conditionChoice,
                            custom: $customCondition
                        )
                    }
                }
            } header: {
                Text(ScheduledResetCopy.featureName)
            } footer: {
                Text(timingFootnote)
            }

            Section {
                Button {
                    confirming = true
                } label: {
                    Label("Schedule reset", systemImage: "calendar.badge.clock")
                }
                .disabled(model.availableResetCredits == 0)
                .accessibilityIdentifier("scheduledReset.arm")
            } footer: {
                Text(creditsFootnote)
            }
        }
        .navigationTitle(ScheduledResetCopy.featureName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if presentation == .sheet {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .alert(confirmationTitle, isPresented: $confirming) {
            Button("Cancel", role: .cancel) {}
            Button("Schedule reset", role: .destructive) {
                let trigger = trigger
                Task {
                    await model.armScheduledReset(trigger)
                }
                dismiss()
            }
        } message: {
            Text(confirmationMessage)
        }
        .task {
            await model.refreshNotificationPermissionState()
        }
    }

    private var confirmationTitle: String {
        model.scheduledReset == nil ? "Schedule a Codex reset?" : "Replace the scheduled reset?"
    }

    private var confirmationMessage: String {
        let now = Date.now
        var sentences: [String] = []
        if let existing = model.scheduledReset {
            sentences.append(
                "This replaces the current schedule (\(ScheduledResetCopy.triggerSummary(existing.trigger, now: now)))."
            )
        }
        sentences.append(ScheduledResetCopy.confirmation(trigger, now: now))
        if model.scheduledResetCreditsExpireBefore(trigger) {
            sentences.append("Every available reset credit expires before then, so this would be skipped.")
        }
        if model.previewScheduledResetDecision(trigger, now: now) == .fire {
            sentences.append("It is already due, so the reset runs right away.")
        }
        sentences.append("It runs once, without asking again, and cannot be undone.")
        return sentences.joined(separator: " ")
    }

    private var timingFootnote: String {
        let background = "iOS decides when background refreshes run, so an unattended reset can happen later than that moment. Usage is fetched again right before a credit is spent."
        switch mode {
        case .fiveHour, .weekly:
            return "Codex Meter checks this limit whenever it refreshes, in the foreground and in the background. \(background)"
        case .dateTime:
            if notificationsAllowed {
                return "At that time iOS shows a notification: its Use reset now action runs the reset without opening the app, and opening the app runs it too. \(background)"
            }
            return "Notifications are not allowed, so nothing can prompt you at that time: the reset runs the next time Codex Meter refreshes after it. \(background)"
        }
    }

    private var creditsFootnote: String {
        let count = model.availableResetCredits
        guard count > 0 else {
            return "No reset credit is available to schedule."
        }
        return "Uses 1 of your \(count) reset \(count == 1 ? "credit" : "credits") through the same reset as Use reset, then the schedule ends. Codex Meter notifies you when it runs, is skipped, or fails."
    }

    private func resolved(_ choice: Int, custom: Int) -> Int {
        ScheduledResetPolicy.clamp(choice == ThresholdPicker.customTag ? custom : choice)
    }
}

private struct ThresholdPicker: View {
    static let customTag = -1

    let title: String
    @Binding var choice: Int
    @Binding var custom: Int

    var body: some View {
        Picker(title, selection: $choice) {
            ForEach(ScheduledResetPolicy.thresholdPresets, id: \.self) { percent in
                Text("\(percent)%").tag(percent)
            }
            Text("Custom").tag(Self.customTag)
        }
        if choice == Self.customTag {
            Stepper("Custom: \(custom)%", value: $custom, in: ScheduledResetPolicy.thresholdRange)
        }
    }
}

/// The armed schedule and its last outcome, shared by the dashboard card and the reset screen.
struct ScheduledResetStatusView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let schedule = model.scheduledReset {
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: "calendar.badge.clock")
                            .foregroundStyle(.tint)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(ScheduledResetCopy.armedLine(schedule.trigger, now: context.date))
                                .font(.subheadline.weight(.semibold))
                                .accessibilityIdentifier("scheduledReset.armedLine")
                            Text(armedHint(for: schedule))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                }
            }

            if let outcome = model.scheduledResetOutcome {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Image(systemName: outcomeSymbol(outcome))
                        .foregroundStyle(outcomeTint(outcome))
                        .accessibilityHidden(true)
                    Text(ScheduledResetCopy.outcomeMessage(outcome))
                        .font(.subheadline)
                        .accessibilityIdentifier("scheduledReset.outcome")
                    Spacer(minLength: 0)
                    Button {
                        model.dismissScheduledResetOutcome()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.secondary)
                            .padding(6)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Dismiss scheduled reset result")
                    .accessibilityIdentifier("scheduledReset.dismissOutcome")
                }
            }
        }
    }

    private func armedHint(for schedule: ScheduledReset) -> String {
        if model.availableResetCredits == 0 {
            return "No reset credit is available, so this will be skipped unless one arrives."
        }
        switch schedule.trigger {
        case .threshold:
            return "Checked at each refresh. iOS decides when background refreshes run."
        case .dateTime:
            return "A notification runs it at that time. iOS decides when background refreshes run."
        }
    }

    private func outcomeSymbol(_ outcome: ScheduledResetOutcome) -> String {
        switch outcome.kind {
        case .fired: "checkmark.circle.fill"
        case .skipped: "minus.circle.fill"
        case .failed: "exclamationmark.triangle.fill"
        }
    }

    private func outcomeTint(_ outcome: ScheduledResetOutcome) -> Color {
        switch outcome.kind {
        case .fired: .green
        case .skipped, .failed: .orange
        }
    }
}
