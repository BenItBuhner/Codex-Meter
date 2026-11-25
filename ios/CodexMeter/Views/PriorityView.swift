import CodexMeterCore
import SwiftUI

/// Priority, the same page as Android's (and its Liquid Glass layout) in the Apple design
/// language: only what needs you, across every connected service. Outages and slowdowns, recent
/// activity, limits to watch, used-up limits, saved resets you can use, what's coming up and new
/// models, each shown only while it has something, in the order chosen in Edit. Recent activity
/// and new models can be dismissed. Turned off in Settings › Appearance › Priority tab.
struct PriorityView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.openURL) private var openURL
    @State private var isEditing = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let report = model.priorityReport(now: context.date)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: AppChrome.sectionSpacing) {
                    if model.mode == .demo {
                        StatusBanner(message: "Demo data — no network requests", systemImage: "sparkles", tint: .blue)
                    }
                    ForEach(report.sections) { section in
                        PrioritySectionCard(section: section, note: note(for: section)) { item in
                            open(item)
                        } dismiss: { items in
                            withAnimation(.snappy) { model.dismissPriorityItems(items) }
                        }
                    }
                    if report.isEmpty {
                        emptyCard(report)
                    }
                }
                .frame(maxWidth: AppChrome.readingMaxWidth)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 16)
                .padding(.vertical, 18)
                .animation(.snappy, value: report.sections.map(\.id))
            }
            .sheet(isPresented: $isEditing) {
                PriorityEditSheet(sizes: report.sizes)
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Priority")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Edit") {
                    isEditing = true
                }
                .accessibilityIdentifier("priority-edit")
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
            await model.refreshProviderStatus()
        }
    }

    private func note(for section: PrioritySection) -> String? {
        switch section.key {
        case .models: ModelDiscovery.note(section.items)
        case .alerts where model.settings.slowdownWarningsEnabled && section.items.contains { $0.link == nil }:
            "Slowdowns use public OpenRouter figures for each maker's newest models, not the speed of your own subscription."
        default: nil
        }
    }

    /// A status row opens the provider's status page; the rest open the provider's tab.
    private func open(_ item: PriorityItem) {
        if let link = item.link {
            openURL(link)
        } else {
            model.select(provider: item.provider)
        }
    }

    private func emptyCard(_ report: PriorityReport) -> some View {
        Label(report.emptyLine, systemImage: report.filtered ? "eye.slash" : "checkmark.seal")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .padding(AppChrome.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface()
            .accessibilityIdentifier("priority-empty")
    }
}

extension PrioritySectionKey {
    var systemImage: String {
        switch self {
        case .alerts: "exclamationmark.triangle"
        case .news: "bell.badge"
        case .watch: "gauge.with.dots.needle.33percent"
        case .usedUp: "hourglass.bottomhalf.filled"
        case .ready: "arrow.counterclockwise.circle"
        case .comingUp: "calendar.badge.clock"
        case .models: "sparkles"
        }
    }

    var tint: Color {
        switch self {
        case .alerts, .watch: .orange
        case .usedUp: .red
        case .news, .comingUp: .blue
        case .ready: .green
        case .models: .purple
        }
    }

    /// Stable test identifiers, the same ones the screenshot tests used before the redesign.
    var accessibilityID: String {
        switch self {
        case .alerts: "priority-alerts"
        case .news: "priority-news"
        case .watch: "priority-watch"
        case .usedUp: "priority-used-up"
        case .ready: "priority-ready"
        case .comingUp: "priority-coming-up"
        case .models: "priority-new-models"
        }
    }
}

private struct PrioritySectionCard: View {
    let section: PrioritySection
    let note: String?
    let open: (PriorityItem) -> Void
    let dismiss: ([PriorityItem]) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Label(section.title, systemImage: section.key.systemImage)
                    .font(.headline.bold())
                    .foregroundStyle(section.key.tint)
                    .textCase(nil)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                if section.offersDismissAll {
                    Button("Dismiss all") {
                        dismiss(section.items.filter(\.isDismissible))
                    }
                    .font(.subheadline)
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier(section.key.accessibilityID + "-dismiss-all")
                }
            }
            ForEach(section.items) { item in
                PriorityRow(item: item, open: { open(item) }, dismiss: item.isDismissible ? { dismiss([item]) } : nil)
                    .transition(.asymmetric(insertion: .opacity, removal: .move(edge: .leading).combined(with: .opacity)))
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
        .accessibilityIdentifier(section.key.accessibilityID)
    }
}

private struct PriorityRow: View {
    let item: PriorityItem
    let open: () -> Void
    let dismiss: (() -> Void)?
    @State private var dragOffset: CGFloat = 0

    var body: some View {
        Button(action: open) {
            HStack(alignment: .top, spacing: 12) {
                ProviderMark(provider: item.provider)
                    .frame(width: 22, height: 22)
                    .foregroundStyle(.primary)
                    .padding(.top, 1)
                VStack(alignment: .leading, spacing: 4) {
                    if let eyebrow = item.eyebrow {
                        Text(eyebrow)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    Text(item.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    if !item.detail.isEmpty {
                        Text(item.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if let left = item.remainingPercent, left > 0 {
                        ProgressView(value: Double(left), total: 100)
                            .tint(left <= 10 ? .red : left <= 25 ? .orange : .accentColor)
                            .accessibilityHidden(true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if item.link != nil {
                    Image(systemName: "arrow.up.right.square")
                        .font(.footnote)
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .offset(x: dragOffset)
        .simultaneousGesture(swipeToDismiss, including: dismiss == nil ? .none : .all)
        .contextMenu {
            if let dismiss {
                Button("Dismiss", systemImage: "xmark", action: dismiss)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(item.link != nil ? "Opens the official status page" : "Opens \(item.provider.displayName)")
        .accessibilityActions {
            if let dismiss {
                Button("Dismiss", action: dismiss)
            }
        }
    }

    /// A row in a card is not a List row, so swipe to dismiss is drawn here: drag it to the left
    /// past a third of the way and it goes.
    private var swipeToDismiss: some Gesture {
        DragGesture(minimumDistance: 24)
            .onChanged { value in
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                dragOffset = min(0, value.translation.width)
            }
            .onEnded { value in
                if value.translation.width < -120 || value.predictedEndTranslation.width < -220 {
                    withAnimation(.snappy) { dragOffset = -600 }
                    dismiss?()
                } else {
                    withAnimation(.snappy) { dragOffset = 0 }
                }
            }
    }
}

/// Edit: switch sections on or off and drag them into order, as on Android.
struct PriorityEditSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let sizes: [PrioritySectionKey: Int]
    @State private var order: [PrioritySectionKey] = []
    @State private var hidden: Set<PrioritySectionKey> = []

    static let header = "Choose what Priority shows, and drag the handles to change the order."
    static let footer = "A section shows only while it has something in it."

    static func countLine(_ count: Int) -> String {
        switch count {
        case 0: "Nothing right now"
        case 1: "1 item now"
        default: "\(count) items now"
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(order) { key in
                        Toggle(isOn: binding(key)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Label(key.label, systemImage: key.systemImage)
                                Text(Self.countLine(sizes[key] ?? 0))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityIdentifier("priority-edit-" + key.rawValue)
                    }
                    .onMove { from, to in
                        order.move(fromOffsets: from, toOffset: to)
                        save()
                    }
                } header: {
                    Text(Self.header).textCase(nil)
                } footer: {
                    Text(Self.footer)
                }
                Section {
                    Button("Reset to default order") {
                        order = PrioritySectionKey.allCases
                        hidden = []
                        save()
                    }
                    .disabled(order == PrioritySectionKey.allCases && hidden.isEmpty)
                }
            }
            #if os(iOS)
            .environment(\.editMode, .constant(.active))
            #endif
            .navigationTitle("Edit Priority")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("priority-edit-done")
                }
            }
        }
        .onAppear {
            order = model.settings.prioritySectionOrder
            hidden = model.settings.priorityHidden
        }
    }

    private func binding(_ key: PrioritySectionKey) -> Binding<Bool> {
        Binding {
            !hidden.contains(key)
        } set: { shown in
            if shown { hidden.remove(key) } else { hidden.insert(key) }
            save()
        }
    }

    private func save() {
        model.setPriorityLayout(order: order, hidden: hidden)
    }
}
