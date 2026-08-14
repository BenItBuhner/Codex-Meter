import Foundation

/// Rough, community-researched value estimates for Codex subscription allowances.
///
/// Anchors: Pro 20x ($200/month) is commonly measured at roughly $14,000 of
/// equivalent API usage per month and Pro 5x ($100/month) at roughly $3,500.
/// Because OpenAI documents Pro 5x as 5x Plus usage and Pro 20x as 20x Plus,
/// Plus ($20/month) back-solves to roughly $700 of monthly usage value. Weekly
/// figures divide the month into four allowance windows, and the 5-hour figure
/// uses a rough one-sixth-of-weekly burst heuristic. Every number here is an
/// estimate, not a billing statement.
public struct PlanPricing: Sendable, Equatable {
    /// Rough share of a weekly allowance available inside one 5-hour burst window.
    private static let fiveHourShareOfWeekly = 1.0 / 6.0

    public let planKey: String
    public let planLabel: String
    public let monthlyPriceUsd: Double
    public let monthlyValueUsd: Double

    private init(
        planKey: String,
        planLabel: String,
        monthlyPriceUsd: Double,
        monthlyValueUsd: Double
    ) {
        self.planKey = planKey
        self.planLabel = planLabel
        self.monthlyPriceUsd = monthlyPriceUsd
        self.monthlyValueUsd = monthlyValueUsd
    }

    /// Returns pricing for a raw plan type, or `nil` when no researched estimate exists.
    public static func forPlan(_ planType: String?) -> PlanPricing? {
        guard let planType else { return nil }
        let normalized = planType
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
        switch normalized {
        case "plus":
            return PlanPricing(planKey: "plus", planLabel: "Plus", monthlyPriceUsd: 20, monthlyValueUsd: 700)
        case "prolite", "pro5x":
            return PlanPricing(planKey: "pro5x", planLabel: "Pro 5x", monthlyPriceUsd: 100, monthlyValueUsd: 3_500)
        case "pro", "pro20x":
            return PlanPricing(planKey: "pro20x", planLabel: "Pro 20x", monthlyPriceUsd: 200, monthlyValueUsd: 14_000)
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
