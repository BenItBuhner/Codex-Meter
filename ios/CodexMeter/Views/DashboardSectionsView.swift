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
                    accent: .mint
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
                    accent: .indigo
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
                    accent: .orange
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
        case let .meter(title, systemImage, window, accent):
            UsageMeterCard(
                title: LocalizedStringKey(title),
                systemImage: systemImage,
                window: window,
                accent: accent,
                fetchedAt: model.usage?.fetchedAt ?? .now
            )
        case let .additional(limit):
            AdditionalLimitCards(
                limit: limit,
                fetchedAt: model.usage?.fetchedAt ?? .now
            )
        case let .usageCredits(credits):
            UsageCreditsCard(credits: credits)
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
        case meter(title: String, systemImage: String, window: UsageWindow, accent: Color)
        case additional(UsageLimit)
        case usageCredits(UsageCredits)
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

private struct AdditionalLimitCards: View {
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
            fetchedAt: fetchedAt
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

            HStack(spacing: 16) {
                Image(systemName: "creditcard.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(.tint)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(balance)
                        .font(.title3.bold())
                        .contentTransition(.numericText())
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, minHeight: 138, alignment: .leading)
        .cardSurface()
        .accessibilityElement(children: .combine)
    }
}

private struct ResetCreditsDashboardCard: View {
    @Environment(AppModel.self) private var model

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

            HStack(spacing: 16) {
                Image(systemName: "arrow.counterclockwise.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(.tint)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(count == 1 ? "1 reset available" : "\(count) resets available")
                        .font(.title3.bold())
                        .contentTransition(.numericText())
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
                Spacer(minLength: 0)
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
