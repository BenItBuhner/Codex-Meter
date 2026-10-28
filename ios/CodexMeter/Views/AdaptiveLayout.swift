import CodexMeterCore
import SwiftUI

// iPad layout: the dashboards measure the width their cards get and lay them out in one, two
// or three columns (`DashboardGrid`). Because the count follows the measured width rather than
// the device, the same code covers iPhone, iPad full screen, Split View, Slide Over and
// Stage Manager windows of any size, and re-flows live while a window is resized.

private struct DashboardColumnsKey: EnvironmentKey {
    static let defaultValue = 1
}

extension EnvironmentValues {
    /// UI tests only: the simulated iPhone Duo draws the toolbar and tabs in its status rail
    /// (as iOS 27.1 lays them out vertically), so dashboards leave the top toolbar empty.
    @Entry var drawsBarsInSimulatedRail = false

    /// Cards per row for the dashboard being laid out. One unless a dashboard measured more.
    var dashboardColumns: Int {
        get { self[DashboardColumnsKey.self] }
        set { self[DashboardColumnsKey.self] = newValue }
    }
}

extension View {
    /// Measures this dashboard column's width and tells the cards inside how many fit per row.
    func dashboardColumnsFromWidth() -> some View {
        modifier(DashboardColumnsReader())
    }
}

private struct DashboardColumnsReader: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .environment(
                \.dashboardColumns,
                DashboardGrid.columns(
                    forWidth: Double(width),
                    accessibilityText: dynamicTypeSize.isAccessibilitySize
                )
            )
            .onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.width
            } action: { newWidth in
                width = newWidth
            }
    }
}

/// Lays dashboard cards out in rows of `dashboardColumns`, in their saved order. Wide cards
/// (the usage history chart) take a row of their own. Cards in a row share one height, so a
/// row of rings lines up like a grid instead of a ragged list.
struct DashboardCardGrid<Item: Identifiable, Cell: View>: View {
    @Environment(\.dashboardColumns) private var columns

    let items: [Item]
    var isWide: (Item) -> Bool = { _ in false }
    @ViewBuilder let cell: (Item) -> Cell

    private struct Row: Identifiable {
        let indexes: [Int]
        let id: [Item.ID]
    }

    private var rows: [Row] {
        DashboardGrid.rows(isWide: items.map(isWide), columns: columns).map { indexes in
            Row(indexes: indexes, id: indexes.map { items[$0].id })
        }
    }

    var body: some View {
        let rows = self.rows
        // A short last row keeps the column width of the full rows above it, so a card never
        // grows to twice the width of its neighbours just because it is alone.
        let hasFullRow = rows.contains { $0.indexes.count == columns }
        VStack(spacing: AppChrome.sectionSpacing) {
            ForEach(rows) { row in
                HStack(alignment: .top, spacing: AppChrome.sectionSpacing) {
                    ForEach(row.indexes, id: \.self) { index in
                        cell(items[index])
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    }
                    if hasFullRow, columns > 1, row.indexes.count < columns,
                       let first = row.indexes.first, !isWide(items[first]) {
                        ForEach(row.indexes.count..<columns, id: \.self) { _ in
                            Color.clear
                                .frame(maxWidth: .infinity, maxHeight: 0)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// One window of an additional model limit (for example "GPT-5.3-Codex-Spark · Weekly").
/// Each window is its own card so it can sit in a column beside the others on iPad.
struct AdditionalLimitWindow: Identifiable {
    let limit: UsageLimit
    let window: UsageWindow
    let accent: Color
    let id: String

    /// The limit's primary window (teal) and secondary window (purple), whichever it reports.
    static func windows(of limit: UsageLimit, keyPrefix: String? = nil) -> [AdditionalLimitWindow] {
        let key = keyPrefix ?? DashboardSections.limitKey(limit)
        var result: [AdditionalLimitWindow] = []
        if let primary = limit.primary {
            result.append(AdditionalLimitWindow(limit: limit, window: primary, accent: .teal, id: key + "#primary"))
        }
        if let secondary = limit.secondary {
            result.append(AdditionalLimitWindow(limit: limit, window: secondary, accent: .purple, id: key + "#secondary"))
        }
        return result
    }
}

extension View {
    /// The iPad pointer highlight for a plain-styled row. A no-op where hover effects don't
    /// exist (the Mac app compiles these views too).
    func pointerHighlight(cornerRadius: CGFloat = 10) -> some View {
#if os(iOS)
        contentShape(.hoverEffect, RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .hoverEffect(.highlight)
#else
        self
#endif
    }
}
