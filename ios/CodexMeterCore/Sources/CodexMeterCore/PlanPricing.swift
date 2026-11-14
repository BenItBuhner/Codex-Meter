import Foundation

/// Value estimates for Codex subscription allowances: a month of the plan's allowance spent on
/// one model at API list prices. Mirrors the Android `PlanPricing` Codex table; every figure's
/// source URL and data date are below and in docs/pricing-sources.md. Where sources disagree the
/// newest dated figure wins. A plan with no published or estimated figure has an unknown value
/// (`hasValue == false`) instead of a guess. Weekly figures divide the month into four
/// allowance windows, and the 5-hour figure uses a rough one-sixth-of-weekly burst heuristic.
/// Every number here is an estimate, not a billing statement.
public struct PlanPricing: Sendable, Equatable {
    /// Rough share of a weekly allowance available inside one 5-hour burst window.
    private static let fiveHourShareOfWeekly = 1.0 / 6.0

    /// BenchLeader, "What an AI coding subscription is worth" (page data as of 2026-11-14).
    public static let benchLeaderUrl = "https://www.benchleader.com/coding-plans"
    /// OpenAI's plan page: Pro at $100, $200 and $500, weekly limits only (read 2026-11-14).
    public static let openAIPricingUrl = "https://learn.chatgpt.com/docs/pricing"

    public let planKey: String
    public let planLabel: String
    public let monthlyPriceUsd: Double
    /// The headline estimate; 0 when the value is unknown (see `hasValue`).
    public let monthlyValueUsd: Double
    /// One line under the card saying where the figure comes from.
    public let basis: String

    private init(
        planKey: String,
        planLabel: String,
        monthlyPriceUsd: Double,
        monthlyValueUsd: Double,
        basis: String
    ) {
        self.planKey = planKey
        self.planLabel = planLabel
        self.monthlyPriceUsd = monthlyPriceUsd
        self.monthlyValueUsd = monthlyValueUsd
        self.basis = basis
    }

    /// Whether there is a dollar figure to show; false for plans whose value is unknown.
    public var hasValue: Bool {
        monthlyValueUsd > 0
    }

    /// Returns pricing for a raw plan type, or `nil` for plans with no allowance to price.
    /// "prolite" (the former Pro 5x) is Pro 100 and "pro" (the former Pro 20x) is Pro 200.
    public static func forPlan(_ planType: String?) -> PlanPricing? {
        guard let planType else { return nil }
        let normalized = planType
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "")
        switch normalized {
        case "plus":
            // BenchLeader, GPT-6.1 Sol at full use, data 2026-11-09.
            return PlanPricing(planKey: "plus", planLabel: "Plus", monthlyPriceUsd: 20, monthlyValueUsd: 299,
                               basis: "Estimate: GPT-6.1 Sol at full use, from BenchLeader (data Nov 9, 2026). Not a billing statement.")
        case "prolite", "pro5x", "pro100":
            // BenchLeader: "No usable figure" (2026-11-14).
            return PlanPricing(planKey: "pro100", planLabel: "Pro 100", monthlyPriceUsd: 100, monthlyValueUsd: 0,
                               basis: "No published estimate for Pro 100 yet (checked Nov 14, 2026). Not a billing statement.")
        case "pro", "pro20x", "pro200":
            // BenchLeader, GPT-6 Astra at full use, data 2026-09-21, on the former Pro 20x tier.
            return PlanPricing(planKey: "pro200", planLabel: "Pro 200", monthlyPriceUsd: 200, monthlyValueUsd: 5_604,
                               basis: "Estimate: GPT-6 Astra at full use, from BenchLeader (data Sep 21, 2026). Not a billing statement.")
        case "pro500":
            // BenchLeader: "No usable figure" (2026-11-14).
            return PlanPricing(planKey: "pro500", planLabel: "Pro 500", monthlyPriceUsd: 500, monthlyValueUsd: 0,
                               basis: "No published estimate for Pro 500 yet (checked Nov 14, 2026). Not a billing statement.")
        default:
            return nil
        }
    }

    /// Estimated usage value covered by one week of the allowance (month / 4).
    public var weeklyValueUsd: Double {
        monthlyValueUsd / 4
    }

    /// Rough usage value available inside a single 5-hour burst window.
    public var fiveHourValueUsd: Double {
        weeklyValueUsd * Self.fiveHourShareOfWeekly
    }

    /// Estimated allowance value for a usage-history window kind.
    public func windowValueUsd(for kind: UsageHistoryKind) -> Double {
        switch kind {
        case .monthly: monthlyValueUsd
        case .weekly: weeklyValueUsd
        case .fiveHour: fiveHourValueUsd
        }
    }

    /// Estimated dollars burned for a used percentage of the given window kind.
    public func estimatedValueUsd(for kind: UsageHistoryKind, usedPercent: Double) -> Double {
        let clamped = min(100, max(0, usedPercent))
        return windowValueUsd(for: kind) * clamped / 100
    }

    /// How many times the subscription price the estimated monthly value represents.
    public var valueMultiplier: Double {
        guard monthlyPriceUsd > 0 else { return 0 }
        return monthlyValueUsd / monthlyPriceUsd
    }

    /// Compact USD formatting: $3,500 · $29 · $4.20 · $0.35.
    public static func formatUsd(_ value: Double) -> String {
        let amount = max(0, value)
        if amount >= 10 {
            let formatter = NumberFormatter()
            formatter.numberStyle = .currency
            formatter.currencyCode = "USD"
            formatter.locale = Locale(identifier: "en_US")
            formatter.maximumFractionDigits = 0
            formatter.minimumFractionDigits = 0
            return formatter.string(from: NSNumber(value: amount.rounded())) ?? "$\(Int(amount.rounded()))"
        }
        return String(format: "$%.2f", amount)
    }
}
