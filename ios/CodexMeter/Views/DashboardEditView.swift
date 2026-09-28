import CodexMeterCore
import SwiftUI

struct DashboardEditView: View {
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
                                    model.settings.isDashboardSectionVisible(item.key)
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
                Text("Dashboard cards")
            } footer: {
                Text("Drag the handles to arrange cards and use the switches to hide cards you do not need. Model-specific limits appear automatically when OpenAI reports them.")
            }

            Section {
                Label(
                    "Changes save immediately. Usage-credit and reset-credit cards still hide automatically when their inventory is empty or unknown. Usage history stays off the dashboard until OpenAI reports a usage window.",
                    systemImage: "info.circle"
                )
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

    private func visibilityBinding(for key: String) -> Binding<Bool> {
        Binding(
            get: { model.settings.isDashboardSectionVisible(key) },
            set: { visible in
                var settings = model.settings
                settings.setDashboardSectionVisible(key, visible: visible)
                model.save(settings: settings)
            }
        )
    }

    private func move(from source: IndexSet, to destination: Int) {
        items.move(fromOffsets: source, toOffset: destination)
        var settings = model.settings
        settings.setDashboardOrder(items.map(\.key))
        model.save(settings: settings)
    }

    private func rebuildItems() {
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

private struct DashboardEditItem: Identifiable {
    let key: String
    let title: String
    let summary: String

    var id: String { key }
}
