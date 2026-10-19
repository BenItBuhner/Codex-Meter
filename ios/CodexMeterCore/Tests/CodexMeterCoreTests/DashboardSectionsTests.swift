import XCTest
@testable import CodexMeterCore

final class DashboardSectionsTests: XCTestCase {
    func testStableLimitKeysAndDefaultOrder() {
        let first = UsageLimit(
            id: "Model,One",
            name: "First",
            primary: UsageWindow(usedPercent: 1, windowSeconds: 18_000),
            secondary: nil
        )
        let duplicate = UsageLimit(
            id: "model,one",
            name: "Duplicate",
            primary: UsageWindow(usedPercent: 2, windowSeconds: 18_000),
            secondary: nil
        )
        let fallback = UsageLimit(
            id: "",
            meteredFeature: "codex_spark",
            primary: nil,
            secondary: UsageWindow(usedPercent: 3, windowSeconds: 604_800)
        )

        XCTAssertEqual(DashboardSections.limitKey(first), "limit:model_one")
        XCTAssertEqual(DashboardSections.limitKey(fallback), "limit:codex_spark")
        XCTAssertTrue(DashboardSections.isLimitKey("limit:anything"))
        XCTAssertFalse(DashboardSections.isLimitKey(DashboardSections.fiveHour))
        XCTAssertEqual(
            DashboardSections.defaultOrder(additionalLimits: [first, duplicate, fallback]),
            [
                DashboardSections.fiveHour,
                DashboardSections.weekly,
                DashboardSections.monthly,
                "limit:model_one",
                "limit:codex_spark",
                DashboardSections.usageCredits,
                DashboardSections.usageHistory,
                DashboardSections.resetCredits
            ]
        )
    }

    func testSavedOrderMergesNewKeysAndDropsUnavailableKeys() {
        let available = [
            DashboardSections.fiveHour,
            DashboardSections.weekly,
            DashboardSections.monthly,
            "limit:spark",
            DashboardSections.usageCredits,
            DashboardSections.usageHistory,
            DashboardSections.resetCredits
        ]
        XCTAssertEqual(
            DashboardSections.resolveOrder(
                saved: [
                    DashboardSections.usageHistory,
                    DashboardSections.fiveHour,
                    "missing",
                    DashboardSections.usageHistory
                ],
                available: available
            ),
            [
                DashboardSections.usageHistory,
                DashboardSections.fiveHour,
                DashboardSections.weekly,
                DashboardSections.monthly,
                "limit:spark",
                DashboardSections.usageCredits,
                DashboardSections.resetCredits
            ]
        )
        XCTAssertEqual(
            DashboardSections.resolveOrder(saved: [], available: available),
            available
        )
    }

    func testHiddenSectionCSVRoundTrip() {
        var hidden = DashboardSections.settingHidden(
            " limit:one,limit:one ",
            key: "limit:two",
            hidden: true
        )
        XCTAssertEqual(hidden, "limit:one,limit:two")
        XCTAssertTrue(DashboardSections.isHidden(hidden, key: "limit:two"))

        hidden = DashboardSections.settingHidden(hidden, key: "limit:one", hidden: false)
        XCTAssertEqual(hidden, "limit:two")
        XCTAssertEqual(
            DashboardSections.resolveOrder(
                savedCSV: "weekly,five_hour",
                available: ["five_hour", "weekly"]
            ),
            ["weekly", "five_hour"]
        )
    }
}
