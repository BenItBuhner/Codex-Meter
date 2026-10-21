import CodexMeterCore
import SwiftUI

/// Edit dashboard for one provider. Each provider keeps its own card order and visibility, and
/// the list shows only the cards that provider reports.
struct DashboardEditView: View {
    var provider: UsageProvider = .codex
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var items: [DashboardEditItem] = []
    @State private var editMode: EditMode = .active

    var body: some View {
        List {
            Section {
                ForEach(items) { item in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.title)
                                .foregroundStyle(
                                    model.settings.isDashboardSectionVisible(item.key, for: provider)
                                        ? .primary : .secondary
                                )
                            Text(item.summary)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        Toggle(
                            "Show \(item.title)",
                            isOn: visibilityBinding(for: item.key)
                        )
                        .labelsHidden()
                    }
                    .accessibilityElement(children: .contain)
                }
                .onMove(perform: move)
            } header: {
                Text("\(provider.displayName) cards")
            } footer: {
                if provider == .codex {
                    Text("Drag the handles to arrange cards and use the switches to hide cards you do not need. Model-specific limits appear automatically when OpenAI reports them.")
                } else {
                    Text("Drag the handles to arrange your \(provider.displayName) cards and use the switches to hide cards you do not need. Each provider keeps its own layout.")
                }
            }

            Section {
                Label(infoNote, systemImage: "info.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
        .environment(\.editMode, $editMode)
        .navigationTitle("Edit dashboard")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        .task {
            rebuildItems()
        }
    }

    private var infoNote: String {
        if provider == .codex {
            return "Changes save immediately. Usage-credit and reset-credit cards still hide automatically when their inventory is empty or unknown, and the monthly credit limit appears only for accounts under workspace spend controls. Usage history stays off the dashboard until OpenAI reports a usage window."
        }
        return "Changes save immediately. Cards appear only while \(provider.vendorName) reports data for them, and new ones show up here automatically."
    }

    private func visibilityBinding(for key: String) -> Binding<Bool> {
        Binding(
            get: { model.settings.isDashboardSectionVisible(key, for: provider) },
            set: { visible in
                var settings = model.settings
                settings.setDashboardSectionVisible(key, visible: visible, for: provider)
                model.save(settings: settings)
            }
        )
    }

    private func move(from source: IndexSet, to destination: Int) {
        items.move(fromOffsets: source, toOffset: destination)
        var settings = model.settings
        settings.setDashboardOrder(items.map(\.key), for: provider)
        model.save(settings: settings)
    }

    private func rebuildItems() {
        guard provider == .codex else {
            rebuildProviderItems()
            return
        }
        let limits = model.usage?.additionalLimits ?? []
        var availableItems: [String: DashboardEditItem] = [
            DashboardSections.fiveHour: DashboardEditItem(
                key: DashboardSections.fiveHour,
                title: "5-hour limit",
                summary: "Rolling 5-hour Codex window"
            ),
            DashboardSections.weekly: DashboardEditItem(
                key: DashboardSections.weekly,
                title: "Weekly limit",
                summary: "Rolling 7-day Codex window"
            ),
            DashboardSections.monthly: DashboardEditItem(
                key: DashboardSections.monthly,
                title: "Monthly limit",
                summary: "Free-tier ~30-day Codex window"
            ),
            DashboardSections.usageCredits: DashboardEditItem(
                key: DashboardSections.usageCredits,
                title: "Usage-credit balance",
                summary: "Hidden automatically at a zero or negative balance"
            ),
            DashboardSections.spendControl: DashboardEditItem(
                key: DashboardSections.spendControl,
                title: "Monthly credit limit",
                summary: "Workspace spend limit · shown only when OpenAI reports one"
            ),
            DashboardSections.usageHistory: DashboardEditItem(
                key: DashboardSections.usageHistory,
                title: "Usage history",
                summary: "Local burn charts for your usage windows"
            ),
            DashboardSections.resetCredits: DashboardEditItem(
                key: DashboardSections.resetCredits,
                title: "Reset credits",
                summary: "Hidden automatically when none are available"
            )
        ]
        for limit in limits {
            let key = DashboardSections.limitKey(limit)
            availableItems[key] = DashboardEditItem(
                key: key,
                title: limit.displayName,
                summary: "Model-specific limit · detected automatically"
            )
        }
        let defaults = DashboardSections.defaultOrder(additionalLimits: limits)
        items = DashboardSections.resolveOrder(
            saved: model.settings.dashboardOrder,
            available: defaults
        ).compactMap { availableItems[$0] }
    }
}

extension DashboardEditView {
    /// Claude Code, Cursor, and later providers: only the cards that provider reports, or its
    /// usual cards before any data. These tabs keep no on-device history, so there is no
    /// usage-history card to arrange.
    private func rebuildProviderItems() {
        let usage: UsageSnapshot? = switch provider {
        case .codex: model.usage
        case .claudeCode: model.claude.usage
        case .cursor: model.cursor.usage
        }
        let available = DashboardSections.providerSections(
            snapshot: usage,
            emptyDefaults: provider.usualDashboardSections,
            includeHistory: false
        )
        let limits = usage?.additionalLimits ?? []
        let isCursor = provider == .cursor
        let name = provider.displayName
        items = DashboardSections.resolveOrder(
            saved: model.settings.savedDashboardOrder(for: provider),
            available: available
        ).map { (key: String) -> DashboardEditItem in
            switch key {
            case DashboardSections.fiveHour:
                DashboardEditItem(key: key, title: "5-hour limit", summary: "Rolling 5-hour \(name) window")
            case DashboardSections.weekly:
                isCursor
                    ? DashboardEditItem(key: key, title: "Included usage", summary: "Your plan's included usage, refreshed weekly")
                    : DashboardEditItem(key: key, title: "Weekly limit", summary: "Rolling 7-day \(name) window")
            case DashboardSections.monthly:
                isCursor
                    ? DashboardEditItem(key: key, title: "Included usage", summary: "Your plan's included usage for the billing month")
                    : DashboardEditItem(key: key, title: "Monthly limit", summary: "Monthly \(name) window")
            default:
                DashboardEditItem(
                    key: key,
                    title: limits.first(where: { DashboardSections.limitKey($0) == key })?.displayName
                        ?? "Additional limit",
                    summary: "Reported by \(provider.vendorName) for your plan"
                )
            }
        }
    }
}

private struct DashboardEditItem: Identifiable {
    let key: String
    let title: String
    let summary: String

    var id: String { key }
}
