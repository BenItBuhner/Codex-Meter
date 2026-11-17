import CodexMeterCore
import SwiftUI

/// Priority, like the Android tab and the desktop Priority page: only what needs you, across
/// every connected service. Limits to watch, slowdowns, used-up limits with their reset times,
/// what's coming up, and new models from the makers you use, each shown only when it has
/// something. Turned off in Settings › Appearance › Priority tab.
struct PriorityView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let report = model.priorityReport(now: context.date)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: AppChrome.sectionSpacing) {
                    Text(report.headline)
                        .font(.title3.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("priority-headline")
                    if model.mode == .demo {
                        StatusBanner(message: "Demo data — no network requests", systemImage: "sparkles", tint: .blue)
                    }
                    section("Limits to watch", systemImage: "gauge.with.dots.needle.33percent", tint: .orange,
                            items: report.watch, id: "priority-watch")
                    section("Slowdowns", systemImage: "tortoise", tint: .orange,
                            items: report.slowdowns, id: "priority-slowdowns",
                            note: "Public OpenRouter figures for each maker's newest models, not the speed of your own subscription.")
                    section("Used up", systemImage: "hourglass.bottomhalf.filled", tint: .red,
                            items: report.usedUp, id: "priority-used-up")
                    section("Coming up", systemImage: "calendar.badge.clock", tint: .blue,
                            items: report.comingUp, id: "priority-coming-up")
                    section("New models", systemImage: "sparkles", tint: .purple,
                            items: report.newModels, id: "priority-new-models",
                            note: newModelsNote(report))
                    if report.isEmpty {
                        Label("Every connected limit is on track. Priority lists limits running low or used up, resets and credits coming up, slowdowns, and new models from the makers you use.",
                              systemImage: "checkmark.seal")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(AppChrome.cardPadding)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .cardSurface()
                    }
                }
                .frame(maxWidth: AppChrome.readingMaxWidth)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 16)
                .padding(.vertical, 18)
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Priority")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    Task { await model.refreshAllForPriority() }
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                Button {
                    model.isShowingSettings = true
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
        }
        .refreshable {
            await model.refreshAllForPriority()
        }
        .task {
            await model.startIfNeeded()
        }
    }

    private func newModelsNote(_ report: PriorityReport) -> String? {
        guard !report.newModels.isEmpty else { return nil }
        return ModelDiscovery.note(report.newModels)
    }

    @ViewBuilder
    private func section(
        _ title: String,
        systemImage: String,
        tint: Color,
        items: [PriorityItem],
        id: String,
        note: String? = nil
    ) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                Label(title, systemImage: systemImage)
                    .font(.headline.bold())
                    .foregroundStyle(tint)
                ForEach(items) { item in
                    PriorityRow(item: item)
                }
                if let note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(AppChrome.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(id)
        }
    }
}

private struct PriorityRow: View {
    @Environment(AppModel.self) private var model
    let item: PriorityItem

    var body: some View {
        Button {
            model.select(provider: item.provider)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                ProviderMark(provider: item.provider)
                    .frame(width: 22, height: 22)
                    .foregroundStyle(.primary)
                    .padding(.top, 1)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(item.detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if let left = item.remainingPercent, left > 0 {
                        ProgressView(value: Double(left), total: 100)
                            .tint(left <= 10 ? .red : left <= 25 ? .orange : .accentColor)
                            .accessibilityHidden(true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens \(item.provider.displayName)")
    }
}
