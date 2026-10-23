import CodexMeterCore
import XCTest

final class DashboardGridTests: XCTestCase {
    func testColumnsFollowContentWidth() {
        // iPhone portrait, Slide Over and a one-third Split View pane.
        XCTAssertEqual(DashboardGrid.columns(forWidth: 343), 1)
        XCTAssertEqual(DashboardGrid.columns(forWidth: 320), 1)
        XCTAssertEqual(DashboardGrid.columns(forWidth: 599.5), 1)
        // iPad mini portrait (744 pt) and a half-screen 13-inch window.
        XCTAssertEqual(DashboardGrid.columns(forWidth: 688), 2)
        XCTAssertEqual(DashboardGrid.columns(forWidth: 600), 2)
        // 13-inch landscape, with and without the sidebar.
        XCTAssertEqual(DashboardGrid.columns(forWidth: 980), 3)
        XCTAssertEqual(DashboardGrid.columns(forWidth: 1_240), 3)
        XCTAssertEqual(DashboardGrid.columns(forWidth: .infinity), 1)
        XCTAssertEqual(DashboardGrid.columns(forWidth: .nan), 1)
    }

    func testAccessibilityTextKeepsCardsWider() {
        XCTAssertEqual(DashboardGrid.columns(forWidth: 700, accessibilityText: true), 1)
        XCTAssertEqual(DashboardGrid.columns(forWidth: 1_100, accessibilityText: true), 2)
    }

    func testSingleColumnIsOneCardPerRow() {
        XCTAssertEqual(
            DashboardGrid.rows(isWide: [false, false, true, false], columns: 1),
            [[0], [1], [2], [3]]
        )
    }

    func testCardsFillRowsInOrder() {
        XCTAssertEqual(
            DashboardGrid.rows(isWide: [false, false, false, false, false], columns: 2),
            [[0, 1], [2, 3], [4]]
        )
        XCTAssertEqual(
            DashboardGrid.rows(isWide: [false, false, false, false, false], columns: 3),
            [[0, 1, 2], [3, 4]]
        )
    }

    func testWideCardBreaksTheRow() {
        // 5-hour, Weekly, history chart, credits, resets.
        XCTAssertEqual(
            DashboardGrid.rows(isWide: [false, false, true, false, false], columns: 3),
            [[0, 1], [2], [3, 4]]
        )
        XCTAssertEqual(DashboardGrid.rows(isWide: [true, true], columns: 2), [[0], [1]])
        XCTAssertEqual(DashboardGrid.rows(isWide: [], columns: 3), [])
    }

    func testZeroColumnsActsLikeOne() {
        XCTAssertEqual(DashboardGrid.rows(isWide: [false, false], columns: 0), [[0], [1]])
    }
}
