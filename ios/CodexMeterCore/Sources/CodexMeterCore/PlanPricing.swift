import Foundation

/// Value estimates for subscription allowances, per provider, so the Estimated value card only
/// ever describes the plan the user is actually on. Mirrors Android's `PlanPricing`.
///
/// Every figure is "monthly API value at full use": what a whole month of the plan's allowance
/// would cost at API list prices if it all went to one model. Each one carries its source URL
/// and data date, and docs/pricing-sources.md keeps the alternatives. Where sources disagree the
/// newest dated figure wins. A plan with no published or estimated figure keeps its card but
/// shows the value as unknown (`hasValue == false`) instead of a guess.
///
/// Cursor reports its own figure: `planUsage.limit` is the usage the plan includes per cycle at
/// API prices (`UsageSnapshot.includedValueUsd`), so no estimate is needed.
///
/// Weekly figures divide the month into four allowance windows, and the 5-hour figure uses a
/// rough one-sixth-of-weekly burst heuristic. Every number here is an estimate, not a billing
/// statement.
public struct PlanPricing: Sendable, Equatable {
    /// Rough share of a weekly allowance available inside one 5-hour burst window.
    private static let fiveHourShareOfWeekly = 1.0 / 6.0

    /// BenchLeader, "What an AI coding subscription is worth" (page data as of 2026-11-14).
    public static let benchLeaderUrl = "https://www.benchleader.com/coding-plans"
    /// Adam Holter, "Subscription tier list: how much API usage $1 of plan buys" (2026-10-29).
    public static let holterUrl = "https://x.com/AdamHoltererer/status/2115827734223974762"
    /// OpenAI's plan page: Pro at $100, $200 and $500, weekly limits only (read 2026-11-14).
    public static let openAIPricingUrl = "https://learn.chatgpt.com/docs/pricing"

    static let cursorBasis = "Cursor's own figure for the usage your plan includes at API prices; not a billing statement."

    /// One sourced figure: a month of the plan's allowance spent on one model at API prices.
    public struct Estimate: Sendable, Equatable {
        public let model: String
        public let monthlyValueUsd: Double
        public let source: String
        public let sourceUrl: String
        /// ISO date the figure's data is from.
        public let dataDate: String
    }

    /// One label and value row of the Estimated value card.
    public struct Row: Sendable, Equatable {
        public let label: String
        public let value: String
    }

    public let planKey: String
    public let planLabel: String
    public let monthlyPriceUsd: Double
    /// The headline figure; 0 when the value is unknown (see `hasValue`).
    public let monthlyValueUsd: Double
    /// One line under the card saying where the figures come from.
    public let basis: String
    /// Whether `monthlyValueUsd` is the provider's own number rather than an estimate.
    public let reported: Bool
    /// Per-model figures, headline first; empty for reported or unknown plans.
    public let byModel: [Estimate]

    private init(
        planKey: String,
        planLabel: String,
        monthlyPriceUsd: Double,
        monthlyValueUsd: Double,
        basis: String,
        reported: Bool = false,
        byModel: [Estimate] = []
    ) {
        self.planKey = planKey
        self.planLabel = planLabel
        self.monthlyPriceUsd = monthlyPriceUsd
        self.monthlyValueUsd = monthlyValueUsd
        self.basis = basis
        self.reported = reported
        self.byModel = byModel
    }

    private static func benchLeader(_ model: String, _ value: Double, _ date: String) -> Estimate {
        Estimate(model: model, monthlyValueUsd: value, source: "BenchLeader", sourceUrl: benchLeaderUrl, dataDate: date)
    }

    /// Holter gives dollars of API usage per $1 of plan; this is that ratio times the price.
    private static func holter(_ model: String, _ perDollar: Double, _ price: Double) -> Estimate {
        Estimate(model: model, monthlyValueUsd: perDollar * price, source: "Adam Holter", sourceUrl: holterUrl,
                 dataDate: "2026-10-29")
    }

    /// A plan priced from sourced estimates; the first one is the headline.
    private static func estimated(_ key: String, _ label: String, _ price: Double, _ estimates: [Estimate]) -> PlanPricing {
        let head = estimates[0]
        let basis = "Estimate: \(head.model) at full use, from \(head.source) (data \(displayDate(head.dataDate)))."
            + " Model rows assume all usage on one model. Not a billing statement."
        return PlanPricing(planKey: key, planLabel: label, monthlyPriceUsd: price,
                           monthlyValueUsd: head.monthlyValueUsd, basis: basis, byModel: estimates)
    }

    /// A plan with no published or estimated figure: the card says so instead of guessing.
    private static func unknown(_ key: String, _ label: String, _ price: Double, _ why: String) -> PlanPricing {
        PlanPricing(planKey: key, planLabel: label, monthlyPriceUsd: price, monthlyValueUsd: 0,
                    basis: why + " Not a billing statement.")
    }

    /// Whether there is a dollar figure to show; false for plans whose value is unknown.
    public var hasValue: Bool {
        monthlyValueUsd > 0
    }

    /// Pricing for the plan a provider's snapshot reports, or `nil` when there is no plan to
    /// describe. Only Codex plans map to the Codex table: Claude's "Pro" is not ChatGPT Pro.
    public static func forProvider(_ provider: UsageProvider, snapshot: UsageSnapshot?) -> PlanPricing? {
        guard let snapshot else { return nil }
        switch provider {
        case .codex: return forPlan(snapshot.planType)
        case .claudeCode: return forClaudePlan(snapshot.planType)
        case .cursor: return forCursor(snapshot)
        default: return nil
        }
    }

    /// Codex (ChatGPT) pricing for a raw plan type, or `nil` for plans with no allowance to
    /// price (Free, Go, Business, Enterprise). "prolite" (the former Pro 5x) is Pro 100 and
    /// "pro" (the former Pro 20x) is Pro 200.
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
            // BenchLeader 2026-11-09 is the newest dated Plus figure (GPT-6.1 Sol). Holter's
            // 2026-10-29 ratios are newer for Astra, Luna and Terra; GPT-6 Sol is BenchLeader's.
            return estimated("plus", "Plus", 20, [
                benchLeader("GPT-6.1 Sol", 299, "2026-11-09"),
                holter("GPT-6 Astra", 32, 20),
                holter("GPT-6 Luna", 19, 20),
                holter("GPT-6.1 Terra", 18, 20),
                benchLeader("GPT-6 Sol", 323, "2026-09-24"),
            ])
        case "prolite", "pro5x", "pro100":
            // BenchLeader: "No usable figure" (2026-11-14); OpenAI doubled the allowance on
            // 2026-10-29, so no older figure carries over either.
            return unknown("pro100", "Pro 100", 100, "No published estimate for Pro 100 yet (checked Nov 14, 2026).")
        case "pro", "pro20x", "pro200":
            // BenchLeader 2026-09-21, measured on the former Pro 20x tier, now Pro 200.
            return estimated("pro200", "Pro 200", 200, [benchLeader("GPT-6 Astra", 5_604, "2026-09-21")])
        case "pro500":
            // BenchLeader: "No usable figure" (2026-11-14). Pro 500 adds Ultrafast.
            return unknown("pro500", "Pro 500", 500, "No published estimate for Pro 500 yet (checked Nov 14, 2026).")
        default:
            return nil
        }
    }

    /// Claude plan badge ("Pro", "Max", "Ultra", or a saved "Max 5x"/"Max 20x") to pricing.
    /// Free has no allowance worth pricing; Team and Enterprise are priced per seat.
    public static func forClaudePlan(_ planLabel: String?) -> PlanPricing? {
        guard let planLabel else { return nil }
        let normalized = planLabel
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "claude", with: "")
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
        switch normalized {
        case "pro":
            return estimated("claude_pro", "Claude Pro", 20, [
                benchLeader("Opus 5.5", 1_200, "2026-11-07"),
                benchLeader("Sonnet 5.5", 1_203, "2026-09-27"),
                benchLeader("Haiku 5.5", 877, "2026-09-27"),
            ])
        case "max", "max5x":
            return estimated("claude_max", "Claude Max", 100, [
                benchLeader("Opus 5.5", 7_224, "2026-10-03"),
                benchLeader("Sonnet 5.5", 7_224, "2026-10-03"),
                benchLeader("Haiku 5.5", 5_263, "2026-10-03"),
            ])
        case "ultra", "max20x":
            // Holter 2026-10-29 (on Max 20x, now Ultra) is newer than BenchLeader's 2026-09-27
            // Ultra rows ($12,426 Opus and Sonnet, $9,054 Haiku).
            return estimated("claude_ultra", "Claude Ultra", 200, [
                holter("Opus 5.5", 66, 200),
                holter("Sonnet 5.5", 66, 200),
                holter("Haiku 5.5", 47, 200),
                holter("Fable 5.1", 6.4, 200),
            ])
        default:
            return nil
        }
    }

    /// Cursor pricing from the included usage Cursor reports. Current plans refresh that pool
    /// weekly and legacy plans monthly; the month is four weeks here, as for every provider.
    public static func forCursor(_ snapshot: UsageSnapshot?) -> PlanPricing? {
        guard let snapshot, let included = snapshot.includedValueUsd else { return nil }
        let weekly = snapshot.weekly != nil && snapshot.monthly == nil
        let label = snapshot.planType.trimmingCharacters(in: .whitespacesAndNewlines)
        return PlanPricing(
            planKey: "cursor_" + label.lowercased().replacingOccurrences(of: " ", with: "_"),
            planLabel: label.isEmpty ? "Cursor" : "Cursor \(label)",
            monthlyPriceUsd: cursorMonthlyPrice(label),
            monthlyValueUsd: weekly ? included * 4 : included,
            basis: cursorBasis,
            reported: true
        )
    }

    /// List prices of Cursor's individual plans; 0 when unknown (the multiple is then hidden).
    static func cursorMonthlyPrice(_ planLabel: String) -> Double {
        switch planLabel.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "plus": 8
        case "pro": 20
        case "premium": 30
        case "pro+": 60
        case "super": 100
        case "ultra": 200
        default: 0
        }
    }

    /// "2026-11-07" to "Nov 7, 2026".
    static func displayDate(_ iso: String) -> String {
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        let parts = iso.split(separator: "-")
        guard parts.count == 3, let month = Int(parts[1]), (1...12).contains(month), let day = Int(parts[2]) else {
            return iso
        }
        return "\(months[month - 1]) \(day), \(parts[0])"
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

    /// Card title: "Claude Pro · $20/month", or just the plan when its price is unknown.
    public var title: String {
        monthlyPriceUsd > 0 ? "\(planLabel) · \(Self.formatUsd(monthlyPriceUsd))/month" : planLabel
    }

    /// Label and value rows for the Estimated value card, only for the windows this snapshot
    /// has: no 5-hour row for a plan without a 5-hour limit, no multiple without a price.
    public func summaryRows(for snapshot: UsageSnapshot?) -> [Row] {
        guard hasValue else {
            return [Row(label: "Est. included usage", value: "Unknown")]
        }
        var rows: [Row] = []
        let weeklyCycle = snapshot?.weekly != nil && snapshot?.monthly == nil
        if reported && weeklyCycle {
            rows.append(Row(label: "Included usage", value: Self.formatUsd(weeklyValueUsd) + "/week"))
        } else {
            rows.append(Row(label: reported ? "Included usage" : "Est. included usage",
                            value: (reported ? "" : "≈ ") + Self.formatUsd(monthlyValueUsd) + "/month"))
            if snapshot?.weekly != nil {
                rows.append(Row(label: "Weekly allowance", value: "≈ " + Self.formatUsd(weeklyValueUsd)))
            }
        }
        if snapshot?.fiveHour != nil {
            rows.append(Row(label: "5-hour allowance", value: "≈ " + Self.formatUsd(fiveHourValueUsd)))
        }
        if monthlyPriceUsd > 0 {
            rows.append(Row(label: "Vs. subscription price",
                            value: "≈ \(Int(valueMultiplier.rounded()))x the monthly cost"))
        }
        if let snapshot, let longWindow = snapshot.longWindow {
            let monthly = snapshot.longWindowIsMonthly
            let kind: UsageHistoryKind = monthly ? .monthly : .weekly
            rows.append(Row(label: "Value used this \(monthly ? "month" : "week")",
                            value: "≈ " + Self.formatUsd(estimatedValueUsd(for: kind, usedPercent: Double(longWindow.usedPercent)))))
            rows.append(Row(label: "\(monthly ? "Monthly" : "Weekly") value remaining",
                            value: "≈ " + Self.formatUsd(estimatedValueUsd(for: kind, usedPercent: Double(longWindow.remainingPercent)))))
        }
        if byModel.count > 1 {
            for estimate in byModel {
                rows.append(Row(label: "All on \(estimate.model)",
                                value: "≈ " + Self.formatUsd(estimate.monthlyValueUsd) + "/month"))
            }
        }
        return rows
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
