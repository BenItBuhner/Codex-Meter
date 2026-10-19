import Foundation
import XCTest
@testable import CodexMeterCore

final class HistoryAnalyticsAndDiagnosticsTests: XCTestCase {
    func testHistorySectionsDefaultsAndOverrideCSV() {
        XCTAssertEqual(HistorySections.all.count, 7)
        XCTAssertFalse(HistorySections.defaultVisible(HistorySections.guide))
        XCTAssertTrue(HistorySections.defaultVisible(HistorySections.windowList))
        XCTAssertTrue(HistorySections.defaultVisible(HistorySections.valueEstimates))
        XCTAssertTrue(HistorySections.isVisible("", key: HistorySections.insightPace))
        XCTAssertFalse(HistorySections.isVisible(nil, key: HistorySections.guide))

        var overrides = HistorySections.setVisible("", key: HistorySections.guide, visible: true)
        XCTAssertTrue(HistorySections.isVisible(overrides, key: HistorySections.guide))
        overrides = HistorySections.setVisible(
            overrides,
            key: HistorySections.valueEstimates,
            visible: false
        )
        XCTAssertFalse(HistorySections.isVisible(overrides, key: HistorySections.valueEstimates))
        XCTAssertTrue(HistorySections.isVisible(overrides, key: HistorySections.insightPeak))

        overrides = HistorySections.setVisible(overrides, key: HistorySections.guide, visible: false)
        overrides = HistorySections.setVisible(
            overrides,
            key: HistorySections.valueEstimates,
            visible: true
        )
        XCTAssertEqual(
            HistorySections.setVisible("guide, guide ,", key: HistorySections.windowList, visible: true),
            "guide"
        )

        var minimal = ""
        for key in HistorySections.all {
            minimal = HistorySections.setVisible(minimal, key: key, visible: false)
            XCTAssertFalse(HistorySections.label(key).isEmpty)
        }
        for key in HistorySections.all {
            XCTAssertFalse(HistorySections.isVisible(minimal, key: key))
        }
    }

    func testPlanPricingAnchorsAndFormatting() {
        XCTAssertNil(PlanPricing.forPlan(nil))
        XCTAssertNil(PlanPricing.forPlan("free"))
        XCTAssertNil(PlanPricing.forPlan("go"))
        XCTAssertNil(PlanPricing.forPlan("enterprise"))

        let plus = try! XCTUnwrap(PlanPricing.forPlan("plus"))
        XCTAssertEqual(plus.monthlyPriceUsd, 20)
        XCTAssertEqual(plus.monthlyValueUsd, 700)
        XCTAssertEqual(plus.weeklyValueUsd, 175)
        XCTAssertEqual(plus.fiveHourValueUsd, 175.0 / 6.0, accuracy: 0.000_000_1)

        let pro5x = try! XCTUnwrap(PlanPricing.forPlan("pro_5x"))
        XCTAssertEqual(pro5x.monthlyValueUsd, 3_500)
        XCTAssertEqual(PlanPricing.forPlan("prolite")?.monthlyValueUsd, 3_500)

        let pro20x = try! XCTUnwrap(PlanPricing.forPlan("pro"))
        XCTAssertEqual(pro20x.monthlyPriceUsd, 200)
        XCTAssertEqual(pro20x.monthlyValueUsd, 14_000)
        XCTAssertEqual(pro20x.windowValueUsd(for: .monthly), 14_000)
        XCTAssertEqual(pro20x.estimatedValueUsd(for: .weekly, usedPercent: 50), 1_750)

        XCTAssertEqual(PlanPricing.formatUsd(3_500), "$3,500")
        XCTAssertEqual(PlanPricing.formatUsd(29.2), "$29")
        XCTAssertEqual(PlanPricing.formatUsd(4.2), "$4.20")
        XCTAssertEqual(PlanPricing.formatUsd(-3), "$0.00")
    }

    func testUsageStatsWindowBreakdownTypicalAndPeak() {
        let base = Date(timeIntervalSince1970: 2_000_000_000)
        var history = UsageHistory.empty(.fiveHour)
        let firstReset = base.addingTimeInterval(5 * 60 * 60)
        history = history.appending(
            window: UsageWindow(usedPercent: 10, windowSeconds: 18_000, resetAt: firstReset),
            observedAt: base
        )
        history = history.appending(
            window: UsageWindow(usedPercent: 70, windowSeconds: 18_000, resetAt: firstReset),
            observedAt: base.addingTimeInterval(30 * 60)
        )
        history = history.appending(
            window: UsageWindow(usedPercent: 90, windowSeconds: 18_000, resetAt: firstReset),
            observedAt: base.addingTimeInterval(4 * 60 * 60)
        )
        let secondReset = firstReset.addingTimeInterval(5 * 60 * 60)
        history = history.appending(
            window: UsageWindow(usedPercent: 20, windowSeconds: 18_000, resetAt: secondReset),
            observedAt: firstReset.addingTimeInterval(10 * 60)
        )

        let breakdown = UsageStats.windowBreakdown(history, maximumWindows: 5)
        XCTAssertEqual(breakdown.count, 2)
        let completed = try! XCTUnwrap(breakdown.first)
        XCTAssertTrue(completed.complete)
        XCTAssertEqual(completed.finalPercent, 90)
        XCTAssertGreaterThan(completed.peakBurnPercentPerHour, completed.averageBurnPercentPerHour)

        let typical = try! XCTUnwrap(UsageStats.typicalUsedPercent(in: history, atElapsedFraction: 0.6))
        XCTAssertGreaterThan(typical, 0)
        XCTAssertNil(UsageStats.typicalUsedPercent(in: .empty(.fiveHour), atElapsedFraction: 0.5))
        XCTAssertEqual(UsageStats.averageFinalPercent(history) ?? -1, 90, accuracy: 0.01)
        XCTAssertGreaterThan(UsageStats.peakBurnPercentPerHour(history), 0)
    }

    func testResetWindowToleranceDedupesLowUsageAnnouncements() {
        let reset = Date(timeIntervalSince1970: 2_000_000_000)
        XCTAssertTrue(
            UsageWindow.sameResetWindow(
                leftReset: reset,
                leftWindowSeconds: 18_000,
                rightReset: reset.addingTimeInterval(60),
                rightWindowSeconds: 18_000
            )
        )
        XCTAssertFalse(
            UsageWindow.sameResetWindow(
                leftReset: reset,
                leftWindowSeconds: 18_000,
                rightReset: reset.addingTimeInterval(20 * 60),
                rightWindowSeconds: 18_000
            )
        )
        XCTAssertFalse(
            UsageWindow.shouldAnnounceLowUsage(
                lastAnnouncedReset: reset,
                currentReset: reset.addingTimeInterval(1),
                windowSeconds: 18_000
            )
        )
        XCTAssertTrue(
            UsageWindow.shouldAnnounceLowUsage(
                lastAnnouncedReset: reset,
                currentReset: reset.addingTimeInterval(20 * 60),
                windowSeconds: 18_000
            )
        )
    }

    func testDiagnosticSanitizerRedactsCredentialsEmailsJWTsAndQueryParams() {
        let jwt = "abcdefghijklmnop.qrstuvwxyzABCDE.FGHIJKLMNOP"
        let input = "Authorization: Bearer top-secret "
            + "access_token=\"access-secret\" refresh_token=refresh-secret "
            + "Cookie: session=private-cookie email=user@example.com jwt=" + jwt + " "
            + "https://example.com/callback?code=oauth-code&state=oauth-state"
        let redacted = DiagnosticSanitizer.redact(input)
        XCTAssertFalse(redacted.contains("top-secret"))
        XCTAssertFalse(redacted.contains("access-secret"))
        XCTAssertFalse(redacted.contains("refresh-secret"))
        XCTAssertFalse(redacted.contains("private-cookie"))
        XCTAssertFalse(redacted.contains("user@example.com"))
        XCTAssertFalse(redacted.contains(jwt))
        XCTAssertFalse(redacted.contains("oauth-code"))
        XCTAssertFalse(redacted.contains("oauth-state"))
        XCTAssertTrue(redacted.contains("[REDACTED]"))
        XCTAssertEqual(
            DiagnosticSanitizer.safeURL(
                "https://example.com:8443/path/to/resource?token=secret#fragment"
            ),
            "https://example.com:8443/path/to/resource"
        )
        XCTAssertEqual(
            DiagnosticSanitizer.redact("The authorization server returned an error."),
            "The authorization server returned an error."
        )
    }
}
