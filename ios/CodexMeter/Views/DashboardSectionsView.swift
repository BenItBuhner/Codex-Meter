import CodexMeterCore
import SwiftUI

struct DashboardSectionsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var sections: [DashboardSectionItem] {
        var available: [String: DashboardSectionItem] = [:]
        let usage = model.usage

        if model.settings.showFiveHour, let window = usage?.fiveHour {
            available[DashboardSections.fiveHour] = DashboardSectionItem(
                key: DashboardSections.fiveHour,
                kind: .meter(
                    title: "5-hour",
                    systemImage: "clock",
                    window: window,
                    accent: .mint,
                    pace: model.usagePace(for: window, kind: .fiveHour)
                )
            )
        }
        if model.settings.showWeekly, let window = usage?.weekly {
            available[DashboardSections.weekly] = DashboardSectionItem(
                key: DashboardSections.weekly,
                kind: .meter(
                    title: "Weekly",
                    systemImage: "calendar",
                    window: window,
                    accent: .indigo,
                    pace: model.usagePace(for: window, kind: .weekly)
                )
            )
        }
        if model.settings.showMonthly, let window = usage?.monthly {
            available[DashboardSections.monthly] = DashboardSectionItem(
                key: DashboardSections.monthly,
                kind: .meter(
                    title: "Monthly",
                    systemImage: "calendar.badge.clock",
                    window: window,
                    accent: .blue,
                    pace: model.usagePace(for: window, kind: .monthly)
                )
            )
        }
        if model.settings.showAdditionalLimits {
            for limit in usage?.additionalLimits ?? [] {
                let key = DashboardSections.limitKey(limit)
                guard !model.settings.dashboardHiddenSections.contains(key),
                      limit.primary != nil || limit.secondary != nil else {
                    continue
                }
                available[key] = DashboardSectionItem(key: key, kind: .additional(limit))
            }
        }
        if model.settings.showUsageCredits,
           let credits = usage?.usageCredits,
           credits.shouldDisplay {
            available[DashboardSections.usageCredits] = DashboardSectionItem(
                key: DashboardSections.usageCredits,
                kind: .usageCredits(credits)
            )
        }
        if model.settings.showSpendControl,
           let control = usage?.spendControl,
           let limit = control.individualLimit {
            available[DashboardSections.spendControl] = DashboardSectionItem(
                key: DashboardSections.spendControl,
                kind: .spendControl(limit, reached: control.reached)
            )
        }
        if model.settings.showUsageHistory,
           usage?.fiveHour != nil || usage?.weekly != nil || usage?.monthly != nil {
            available[DashboardSections.usageHistory] = DashboardSectionItem(
                key: DashboardSections.usageHistory,
                kind: .usageHistory
            )
        }
        let resetCount = model.credits?.availableCount ?? usage?.resetCreditsAvailable ?? 0
        if model.settings.showResetCredits, resetCount > 0 {
            available[DashboardSections.resetCredits] = DashboardSectionItem(
                key: DashboardSections.resetCredits,
                kind: .resetCredits
            )
        }

        let defaultOrder = DashboardSections.defaultOrder(
            additionalLimits: usage?.additionalLimits ?? []
        )
        let availableKeys = defaultOrder.filter { available[$0] != nil }
        return DashboardSections.resolveOrder(
            saved: model.settings.dashboardOrder,
            available: availableKeys
        ).compactMap { available[$0] }
    }

    private var rows: [DashboardSectionRow] {
        guard horizontalSizeClass == .regular else {
            return sections.map { DashboardSectionRow(items: [$0]) }
        }
        var result: [DashboardSectionRow] = []
        var index = 0
        while index < sections.count {
            let current = sections[index]
            if current.isSingleMeter,
               sections.indices.contains(index + 1),
               sections[index + 1].isSingleMeter {
                result.append(
                    DashboardSectionRow(items: [current, sections[index + 1]])
                )
                index += 2
            } else {
                result.append(DashboardSectionRow(items: [current]))
                index += 1
            }
        }
        return result
    }

    var body: some View {
        if rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label("No dashboard items are available", systemImage: "rectangle.slash")
                    .font(.headline)
                Text("Refresh usage or choose cards in Edit dashboard.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                NavigationLink("Edit dashboard") {
                    DashboardEditView()
                }
                .buttonStyle(.bordered)
            }
            .padding(AppChrome.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface()
        } else {
            LazyVStack(spacing: AppChrome.sectionSpacing) {
                ForEach(rows) { row in
                    HStack(alignment: .top, spacing: AppChrome.sectionSpacing) {
                        ForEach(row.items) { section in
                            sectionView(section)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func sectionView(_ section: DashboardSectionItem) -> some View {
        switch section.kind {
        case let .meter(title, systemImage, window, accent, pace):
            UsageMeterCard(
                title: LocalizedStringKey(title),
                systemImage: systemImage,
                window: window,
                accent: accent,
                fetchedAt: model.usage?.fetchedAt ?? .now,
                pace: pace
            )
        case let .additional(limit):
            AdditionalLimitCards(
                limit: limit,
                fetchedAt: model.usage?.fetchedAt ?? .now
            )
        case let .usageCredits(credits):
            UsageCreditsCard(credits: credits)
        case let .spendControl(limit, reached):
            SpendControlCard(
                limit: limit,
                reached: reached,
                fetchedAt: model.usage?.fetchedAt ?? .now
            )
        case .usageHistory:
            UsageHistoryDashboardCard()
        case .resetCredits:
            ResetCreditsDashboardCard()
        }
    }
}

private struct DashboardSectionRow: Identifiable {
    let items: [DashboardSectionItem]

    var id: String {
        items.map(\.key).joined(separator: "|")
    }
}

private struct DashboardSectionItem: Identifiable {
    enum Kind {
        case meter(
            title: String,
            systemImage: String,
            window: UsageWindow,
            accent: Color,
            pace: UsagePaceAssessment
        )
        case additional(UsageLimit)
        case usageCredits(UsageCredits)
        case spendControl(SpendControlLimit, reached: Bool)
        case usageHistory
        case resetCredits
    }

    let key: String
    let kind: Kind

    var id: String { key }

    var isSingleMeter: Bool {
        if case .meter = kind {
            return true
        }
        return false
    }
}

struct AdditionalLimitCards: View {
    @Environment(AppModel.self) private var model
    let limit: UsageLimit
    let fetchedAt: Date

    var body: some View {
        VStack(spacing: AppChrome.sectionSpacing) {
            if let primary = limit.primary {
                card(for: primary, accent: .teal)
            }
            if let secondary = limit.secondary {
                card(for: secondary, accent: .purple)
            }
        }
    }

    private func card(for window: UsageWindow, accent: Color) -> some View {
        UsageMeterCard(
            title: LocalizedStringKey(
                "\(Self.limitTitle(limit)) · \(Self.cadenceLabel(window))"
            ),
            systemImage: window.windowSeconds >= 86_400
                ? "calendar.badge.clock" : "clock.badge",
            window: window,
            accent: accent,
            fetchedAt: fetchedAt,
            pace: model.usagePace(for: window, observedAt: fetchedAt)
        )
    }

    private static func cadenceLabel(_ window: UsageWindow) -> String {
        let seconds = window.windowSeconds
        if (432_000 ... 777_600).contains(seconds) {
            return "Weekly"
        }
        if (864_000 ... 3_888_000).contains(seconds) {
            return "Monthly"
        }
        if (10_800 ... 28_800).contains(seconds) {
            return "\(max(1, Int((Double(seconds) / 3_600).rounded())))-hour"
        }
        if seconds.isMultiple(of: 86_400) {
            return "\(seconds / 86_400)-day"
        }
        if seconds.isMultiple(of: 3_600) {
            return "\(seconds / 3_600)-hour"
        }
        return "Usage"
    }

    private static func limitTitle(_ limit: UsageLimit) -> String {
        if limit.limitReached {
            return "\(limit.displayName) (limit reached)"
        }
        if !limit.allowed {
            return "\(limit.displayName) (unavailable)"
        }
        return limit.displayName
    }
}

private struct UsageCreditsCard: View {
    let credits: UsageCredits
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .title2) private var symbolSize: CGFloat = 30

    private var balance: String {
        if credits.unlimited {
            return "Unlimited"
        }
        guard let raw = credits.balance else {
            return credits.hasCredits ? "Credits available" : "No purchased credits"
        }
        if let number = Double(raw.replacingOccurrences(of: ",", with: "")),
           number.isFinite {
            return number.formatted(
                .number.precision(.fractionLength(0 ... 2))
            ) + " credits"
        }
        return raw
    }

    private var summary: String {
        if credits.unlimited {
            return "Usage-credit balance is not capped"
        }
        return "Purchased Codex usage-credit balance"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Usage credits")
                .font(.headline.bold())

            dynamicTypeSize.rowLayout(spacing: 16) {
                Image(systemName: "creditcard.fill")
                    .font(.system(size: symbolSize))
                    .foregroundStyle(.tint)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(balance)
                        .font(.title3.bold())
                        .numericTextTransition()
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if !dynamicTypeSize.isAccessibilitySize {
                    Spacer(minLength: 0)
                }
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
        .cardSurface()
        .accessibilityElement(children: .combine)
    }
}

private struct SpendControlCard: View {
    let limit: SpendControlLimit
    let reached: Bool
    let fetchedAt: Date

    private var accent: Color {
        reached ? .orange : .accentColor
    }

    private var usageLine: String {
        UsageFormat.spendControlUsage(limit) ?? "\(limit.remainingPercent)% of credits left"
    }

    private var resetAt: Date? {
        guard limit.showsResetCountdown else { return nil }
        return limit.effectiveResetDate(relativeTo: fetchedAt)
    }

    private var accessibilityValue: String {
        var parts = [
            "\(limit.remainingPercent) percent remaining",
            "\(limit.usedPercent) percent used"
        ]
        if let usage = UsageFormat.spendControlUsage(limit) {
            parts.append(usage)
        }
        if reached {
            parts.append("limit reached")
        }
        return parts.joined(separator: ", ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Label("Monthly credit limit", systemImage: "creditcard.and.123")
                    .font(.headline.bold())
                Spacer(minLength: 0)
                Text("\(limit.remainingPercent)% left")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }

            SpendControlBar(fraction: Double(limit.remainingPercent) / 100, accent: accent)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Monthly credit limit")
                .accessibilityValue(accessibilityValue)

            VStack(alignment: .leading, spacing: 3) {
                Text(usageLine)
                    .font(.title3.bold())
                    .contentTransition(.numericText())
                if let remaining = UsageFormat.spendControlRemaining(limit) {
                    Text(remaining)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .contentTransition(.numericText())
                }
            }

            if let resetAt {
                HStack(spacing: 6) {
                    Text("Resets in")
                        .foregroundStyle(.secondary)
                    Text(resetAt, style: .relative)
                        .fontWeight(.semibold)
                        .monospacedDigit()
                    Text("·")
                        .foregroundStyle(.secondary)
                    Text(resetAt, format: .dateTime.month(.abbreviated).day().hour().minute())
                        .foregroundStyle(.secondary)
                }
                .font(.caption)
                .accessibilityElement(children: .combine)
            }

            if reached {
                Label("Workspace credit limit reached", systemImage: "exclamationmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.orange)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }
}

/// Fills the remaining share, like the meter rings, so every gauge in the app reads the same way.
private struct SpendControlBar: View {
    let fraction: Double
    let accent: Color

    private static let height: CGFloat = 10

    var body: some View {
        GeometryReader { proxy in
            let clamped = CGFloat(min(1, max(0, fraction)))
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(accent.opacity(0.16))
                if clamped > 0 {
                    Capsule()
                        .fill(accent)
                        .frame(width: max(Self.height, proxy.size.width * clamped))
                }
            }
        }
        .frame(height: Self.height)
        .animation(.snappy, value: fraction)
    }
}

private struct ResetCreditsDashboardCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .title2) private var symbolSize: CGFloat = 30

    private var count: Int {
        model.credits?.availableCount ?? model.usage?.resetCreditsAvailable ?? 0
    }

    private var nextExpiry: Date? {
        model.credits?.credits
            .filter(\.isAvailable)
            .compactMap(\.expiresAt)
            .filter { $0 > .now }
            .min()
    }

    private var summary: String {
        if nextExpiry != nil {
            return "Next credit expiry"
        }
        if count > 0 {
            return "Expiration details unavailable"
        }
        return "Earn credits from ChatGPT Codex"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Reset credits")
                .font(.headline.bold())

            dynamicTypeSize.rowLayout(spacing: 16) {
                Image(systemName: "arrow.counterclockwise.circle.fill")
                    .font(.system(size: symbolSize))
                    .foregroundStyle(.tint)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(count == 1 ? "1 reset available" : "\(count) resets available")
                        .font(.title3.bold())
                        .numericTextTransition()
                    if let nextExpiry {
                        Text("Next expires \(nextExpiry, style: .relative)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text(summary)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                if !dynamicTypeSize.isAccessibilitySize {
                    Spacer(minLength: 0)
                }
            }

            Button(count > 0 ? "Use 1 reset" : "No resets available") {
                model.isShowingReset = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .disabled(count == 0)
        }
        .padding(AppChrome.cardPadding)
        .cardSurface()
    }
}

/// Claude Code, Cursor and added providers' cards, in the order and visibility chosen in that provider's own
/// Edit dashboard.
struct ProviderDashboardCards: View {
    let provider: UsageProvider
    let usage: UsageSnapshot
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private static let windowKeys: Set<String> = [
        DashboardSections.fiveHour,
        DashboardSections.weekly,
        DashboardSections.monthly
    ]

    private var keys: [String] {
        DashboardSections.visibleProviderSections(
            snapshot: usage,
            savedOrder: model.settings.savedDashboardOrder(for: provider),
            hidden: model.settings.hiddenDashboardSections(for: provider)
        )
    }

    /// On wide layouts two usage windows in a row share a line, like the Codex dashboard.
    private var rows: [[String]] {
        let sectionKeys = keys
        guard horizontalSizeClass == .regular else { return sectionKeys.map { [$0] } }
        var result: [[String]] = []
        var index = 0
        while index < sectionKeys.count {
            let current = sectionKeys[index]
            if Self.windowKeys.contains(current),
               sectionKeys.indices.contains(index + 1),
               Self.windowKeys.contains(sectionKeys[index + 1]) {
                result.append([current, sectionKeys[index + 1]])
                index += 2
            } else {
                result.append([current])
                index += 1
            }
        }
        return result
    }

    var body: some View {
        let cardRows = rows
        if cardRows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label("All \(provider.displayName) cards are hidden", systemImage: "rectangle.slash")
                    .font(.headline)
                Text("Choose cards in Edit dashboard.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                NavigationLink("Edit dashboard") {
                    DashboardEditView(provider: provider)
                }
                .buttonStyle(.bordered)
            }
            .padding(AppChrome.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface()
        } else {
            VStack(spacing: AppChrome.sectionSpacing) {
                ForEach(cardRows, id: \.self) { row in
                    HStack(alignment: .top, spacing: AppChrome.sectionSpacing) {
                        ForEach(row, id: \.self) { key in
                            card(for: key)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func card(for key: String) -> some View {
        switch key {
        case DashboardSections.fiveHour:
            UsageMeterCard(
                // Added providers' short window isn't always five hours.
                title: provider.isBuiltIn
                    ? "5-hour"
                    : LocalizedStringKey(ProviderUsageParsers.durationName(usage.fiveHour?.windowSeconds ?? 0)),
                systemImage: "clock",
                window: usage.fiveHour,
                accent: .orange,
                fetchedAt: usage.fetchedAt
            )
        case DashboardSections.weekly:
            UsageMeterCard(
                title: provider == .cursor ? "Included usage · Weekly" : "Weekly",
                systemImage: "calendar",
                window: usage.weekly,
                accent: provider == .cursor ? .indigo : .brown,
                fetchedAt: usage.fetchedAt
            )
        case DashboardSections.monthly:
            UsageMeterCard(
                title: provider == .cursor ? "Included usage · Monthly" : "Monthly",
                systemImage: "calendar.badge.clock",
                window: usage.monthly,
                accent: .indigo,
                fetchedAt: usage.fetchedAt
            )
        default:
            if let limit = usage.additionalLimits.first(where: { DashboardSections.limitKey($0) == key }) {
                if provider == .cursor {
                    CursorLimitCard(limit: limit, fetchedAt: usage.fetchedAt)
                } else {
                    AdditionalLimitCards(limit: limit, fetchedAt: usage.fetchedAt)
                }
            }
        }
    }
}

/// A Cursor pool or spend cap. The name alone is the title: every Cursor limit shares the
/// included usage's cycle, so a cadence suffix would only repeat it, and long titles truncate.
private struct CursorLimitCard: View {
    let limit: UsageLimit
    let fetchedAt: Date

    private var isSpend: Bool {
        limit.id == CursorUsageParser.limitIDPrefix + "on_demand"
    }

    var body: some View {
        if let window = limit.primary ?? limit.secondary {
            UsageMeterCard(
                title: LocalizedStringKey(limit.displayName),
                systemImage: isSpend ? "dollarsign.circle" : "square.stack.3d.up",
                window: window,
                accent: isSpend ? .purple : .teal,
                fetchedAt: fetchedAt
            )
        }
    }
}
