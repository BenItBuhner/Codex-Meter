import CodexMeterCore
import XCTest

/// The per-provider value estimates, mirroring Android's `testPlanPricingPerProvider`.
final class PlanPricingProviderTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 2_000_000_000)

    private func claudePro() -> UsageSnapshot {
        UsageSnapshot(
            planType: "Pro",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(usedPercent: 0, windowSeconds: 5 * 3_600, resetAt: now.addingTimeInterval(3 * 3_600)),
            weekly: UsageWindow(usedPercent: 40, windowSeconds: 7 * 86_400, resetAt: now.addingTimeInterval(3 * 86_400)),
            fetchedAt: now
        )
    }

    private func value(_ rows: [PlanPricing.Row], _ label: String) -> String? {
        rows.first { $0.label == label }?.value
    }

    func testClaudePlansUseClaudeFigures() throws {
        let snapshot = claudePro()
        let claude = try XCTUnwrap(PlanPricing.forProvider(.claudeCode, snapshot: snapshot))
        XCTAssertEqual(claude.planLabel, "Claude Pro")
        XCTAssertEqual(claude.monthlyPriceUsd, 20)
        XCTAssertEqual(claude.monthlyValueUsd, 1_200)
        XCTAssertEqual(claude.title, "Claude Pro · $20/month")
        XCTAssertEqual(Int(claude.valueMultiplier.rounded()), 60)
        XCTAssertFalse(claude.reported)

        let rows = claude.summaryRows(for: snapshot)
        XCTAssertEqual(value(rows, "Value used this week"), "≈ $120")
        XCTAssertEqual(value(rows, "Weekly value remaining"), "≈ $180")
        XCTAssertEqual(value(rows, "All on Haiku 5.5"), "≈ $877/month")
        XCTAssertNotNil(value(rows, "5-hour allowance"))
        XCTAssertTrue(claude.basis.contains("BenchLeader"))
        XCTAssertTrue(claude.basis.contains("Nov 7, 2026"))

        XCTAssertEqual(PlanPricing.forClaudePlan("Max")?.monthlyValueUsd, 7_224)
        XCTAssertEqual(PlanPricing.forClaudePlan("Ultra")?.monthlyValueUsd, 13_200)
        XCTAssertEqual(PlanPricing.forClaudePlan("Max 20x")?.planLabel, "Claude Ultra")
        XCTAssertEqual(PlanPricing.forClaudePlan("Max 5x")?.planLabel, "Claude Max")
        XCTAssertNotNil(PlanPricing.forClaudePlan("Claude Pro"))
        XCTAssertNil(PlanPricing.forClaudePlan("Team"))
        XCTAssertNil(PlanPricing.forClaudePlan("Free"))
    }

    func testOnlyCodexPlansUseTheCodexTable() {
        XCTAssertNil(PlanPricing.forProvider(.copilot, snapshot: claudePro()))
        XCTAssertNil(PlanPricing.forProvider(.claudeCode, snapshot: nil))
        let codexPro = UsageSnapshot(planType: "pro", allowed: true, limitReached: false,
                                     fiveHour: nil, weekly: nil, fetchedAt: now)
        XCTAssertEqual(PlanPricing.forProvider(.codex, snapshot: codexPro)?.planLabel, "Pro 200")
        // Claude's "Pro" is not ChatGPT Pro.
        XCTAssertEqual(PlanPricing.forProvider(.claudeCode, snapshot: claudePro())?.planLabel, "Claude Pro")
    }

    func testUnknownPlansSayUnknown() throws {
        let pro100 = try XCTUnwrap(PlanPricing.forPlan("prolite"))
        let rows = pro100.summaryRows(for: nil)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(value(rows, "Est. included usage"), "Unknown")
        XCTAssertEqual(pro100.title, "Pro 100 · $100/month")
    }

    func testCursorReportsItsOwnIncludedUsage() throws {
        let start = Int64(now.timeIntervalSince1970 * 1_000)
        let weeklyEnd = start + 7 * 86_400_000
        let weeklyBody = """
        {"billingCycleStart":"\(start)","billingCycleEnd":"\(weeklyEnd)",\
        "planUsage":{"includedSpend":1250,"limit":5000,"totalPercentUsed":25}}
        """
        let weekly = try CursorUsageParser.parse(weeklyBody, planLabel: "Premium", fetchedAt: now)
        XCTAssertEqual(weekly.includedValueUsd, 50)
        let restored = try JSONDecoder().decode(UsageSnapshot.self, from: JSONEncoder().encode(weekly))
        XCTAssertEqual(restored.includedValueUsd, 50)

        let cursor = try XCTUnwrap(PlanPricing.forProvider(.cursor, snapshot: weekly))
        XCTAssertTrue(cursor.reported)
        XCTAssertEqual(cursor.title, "Cursor Premium · $30/month")
        let rows = cursor.summaryRows(for: weekly)
        XCTAssertEqual(value(rows, "Included usage"), "$50/week")
        XCTAssertNil(value(rows, "5-hour allowance"))
        XCTAssertEqual(value(rows, "Value used this week"), "≈ $13")
        XCTAssertEqual(cursor.basis, "Cursor's own figure for the usage your plan includes at API prices; not a billing statement.")

        let monthlyEnd = start + 30 * 86_400_000
        let monthlyBody = """
        {"billingCycleStart":"\(start)","billingCycleEnd":"\(monthlyEnd)",\
        "planUsage":{"limit":2000,"totalPercentUsed":10}}
        """
        let legacy = try CursorUsageParser.parse(monthlyBody, planLabel: "Pro", fetchedAt: now)
        XCTAssertEqual(PlanPricing.forProvider(.cursor, snapshot: legacy)?.monthlyValueUsd, 20)

        let noFigure = try CursorUsageParser.parse(#"{"planUsage":{"totalPercentUsed":10}}"#, planLabel: "Pro", fetchedAt: now)
        XCTAssertNil(noFigure.includedValueUsd)
        XCTAssertNil(PlanPricing.forProvider(.cursor, snapshot: noFigure))
    }
}
