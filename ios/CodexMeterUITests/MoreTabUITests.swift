import XCTest

/// More on iPhone: with more tabs than the bar holds, the system's More tab is a pushed list
/// (UITabBarController's More navigation controller), never a sheet. A row pushes its provider with
/// Back to More, tapping More again pops back to the list, and More keeps its page across tabs.
/// Android's Liquid Glass design copies this behavior (MoreTabTest in android/glass).
@MainActor
final class MoreTabUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Six providers (and Priority when the demo has something for it): the last ones go under More.
    private func launch() -> XCUIApplication {
        let app = UITestSupport.launch(arguments: [
            "-ui-testing-demo",
            "-ui-testing-providers", "codex,claude_code,cursor,copilot,kimi,zai"
        ])
        XCTAssertTrue(moreTab(app).waitForExistence(timeout: 10), "six providers overflow the tab bar into More")
        return app
    }

    private func moreTab(_ app: XCUIApplication) -> XCUIElement {
        app.tabBars.buttons["More"].firstMatch
    }

    private func row(_ name: String, in app: XCUIApplication) -> XCUIElement {
        app.cells.containing(.staticText, identifier: name).firstMatch
    }

    private func openMore(_ app: XCUIApplication) {
        UITestSupport.tap(moreTab(app))
        XCTAssertTrue(app.navigationBars["More"].waitForExistence(timeout: 5),
                      "More is a page with its own bar, not a sheet")
    }

    /// Navigation bars on screen: one, or the provider's own NavigationStack stacked a second bar
    /// under More's.
    private func visibleBars(_ app: XCUIApplication) -> Int {
        let screen = app.windows.firstMatch.frame
        return app.navigationBars.allElementsBoundByIndex.filter {
            $0.exists && !$0.frame.isEmpty && screen.intersects($0.frame)
        }.count
    }

    private func back(from title: String, in app: XCUIApplication) {
        let bar = app.navigationBars[title]
        if bar.buttons["BackButton"].exists {
            UITestSupport.tap(bar.buttons["BackButton"])
        } else if bar.buttons["More"].exists {
            UITestSupport.tap(bar.buttons["More"])
        } else {
            UITestSupport.tap(bar.buttons.firstMatch)
        }
    }

    private func pushZai(_ app: XCUIApplication) {
        XCTAssertTrue(row("Z.ai", in: app).waitForExistence(timeout: 5), "Z.ai is listed under More")
        UITestSupport.tap(row("Z.ai", in: app))
        XCTAssertTrue(app.navigationBars["Z.ai"].waitForExistence(timeout: 5), "a row pushes its provider")
    }

    func testMoreIsAPushedListAndARowPushesItsProvider() throws {
        let app = launch()
        openMore(app)
        XCTAssertTrue(row("Kimi Code", in: app).waitForExistence(timeout: 5))
        pushZai(app)
        UITestSupport.settle(0.8)
        XCTAssertEqual(visibleBars(app), 1, "one navigation bar on a provider More pushed")
        XCTAssertTrue(moreTab(app).isSelected, "More stays the selected tab")
        back(from: "Z.ai", in: app)
        XCTAssertTrue(app.navigationBars["More"].waitForExistence(timeout: 5), "Back returns to More's list")
        XCTAssertTrue(row("Z.ai", in: app).exists)
    }

    func testBackReturnsToTheListEveryTime() throws {
        let app = launch()
        openMore(app)
        for _ in 0..<3 {
            pushZai(app)
            back(from: "Z.ai", in: app)
            XCTAssertTrue(app.navigationBars["More"].waitForExistence(timeout: 5))
        }
    }

    func testTappingMoreAgainPopsToTheList() throws {
        let app = launch()
        openMore(app)
        pushZai(app)
        UITestSupport.tap(moreTab(app))
        XCTAssertTrue(app.navigationBars["More"].waitForExistence(timeout: 5),
                      "tapping More while a provider is pushed pops back to the list")
        pushZai(app)
    }

    func testMoreKeepsItsPageAcrossTabs() throws {
        let app = launch()
        openMore(app)
        pushZai(app)
        UITestSupport.tap(app.tabBars.buttons["Codex"].firstMatch)
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))
        UITestSupport.tap(moreTab(app))
        XCTAssertTrue(app.navigationBars["Z.ai"].waitForExistence(timeout: 5),
                      "More shows the provider it had pushed")
    }
}
