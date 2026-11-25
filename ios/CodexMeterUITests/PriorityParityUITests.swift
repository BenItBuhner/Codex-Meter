import XCTest

/// Priority, What's new and About as Android has them: the sections in Android's order, Edit
/// mode that hides and restores a section, dismissing recent activity, What's new closing with a
/// swipe, and the credits.
@MainActor
final class PriorityParityUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func openPriority() -> XCUIApplication {
        let app = UITestSupport.launch(arguments: ["-ui-testing-demo", "-ui-testing-reset-settings"])
        XCTAssertTrue(app.buttons["Priority"].firstMatch.waitForExistence(timeout: 10))
        UITestSupport.tap(app.buttons["Priority"].firstMatch)
        XCTAssertTrue(app.navigationBars["Priority"].waitForExistence(timeout: 5))
        return app
    }

    private func section(_ id: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    func testDemoPriorityShowsAndroidSectionsInOrder() throws {
        let app = openPriority()
        let alerts = section("priority-alerts", in: app)
        let news = section("priority-news", in: app)
        XCTAssertTrue(alerts.waitForExistence(timeout: 8), "the demo outage and slowdown")
        XCTAssertTrue(news.waitForExistence(timeout: 5), "the demo recent activity")
        XCTAssertTrue(app.staticTexts["Outages and slowdowns"].exists)
        XCTAssertTrue(app.staticTexts["Recent activity"].exists)
        XCTAssertLessThan(alerts.frame.minY, news.frame.minY, "Outages come first, as on Android")
        XCTAssertFalse(app.staticTexts["priority-headline"].exists, "Liquid Glass has no headline")
        UITestSupport.scrollDashboard(untilVisible: section("priority-new-models", in: app), in: app)
        XCTAssertTrue(app.staticTexts["New models"].exists)
    }

    func testDismissAllClearsRecentActivity() throws {
        let app = openPriority()
        let news = section("priority-news", in: app)
        XCTAssertTrue(news.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Monthly refilled early"].exists)
        UITestSupport.tap(app.buttons["priority-news-dismiss-all"])
        XCTAssertTrue(news.waitForNonExistence(timeout: 5), "dismissed rows leave, and the empty section with them")
        XCTAssertTrue(section("priority-alerts", in: app).exists, "other sections stay")
    }

    func testEditHidesAndRestoresASection() throws {
        let app = openPriority()
        XCTAssertTrue(section("priority-alerts", in: app).waitForExistence(timeout: 8))
        UITestSupport.tap(app.buttons["priority-edit"])
        XCTAssertTrue(app.navigationBars["Edit Priority"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Choose what Priority shows, and drag the handles to change the order."].exists)
        XCTAssertTrue(app.staticTexts["A section shows only while it has something in it."].exists)
        let toggle = app.switches["priority-edit-alerts"].firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "1")
        // The switch's frame is the whole row; its knob is at the trailing edge.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        let off = expectation(for: NSPredicate(format: "value == '0'"), evaluatedWith: toggle)
        wait(for: [off], timeout: 5)
        UITestSupport.tap(app.buttons["priority-edit-done"])
        XCTAssertTrue(section("priority-alerts", in: app).waitForNonExistence(timeout: 5))
        XCTAssertTrue(section("priority-news", in: app).exists)

        UITestSupport.tap(app.buttons["priority-edit"])
        XCTAssertTrue(app.navigationBars["Edit Priority"].waitForExistence(timeout: 5))
        UITestSupport.tap(app.buttons["Reset to default order"])
        UITestSupport.tap(app.buttons["priority-edit-done"])
        XCTAssertTrue(section("priority-alerts", in: app).waitForExistence(timeout: 5), "Reset shows it again")
    }

    func testWhatsNewClosesWithASwipeDown() throws {
        let app = UITestSupport.launch(arguments: ["-ui-testing-whats-new"])
        let title = app.staticTexts["whats-new-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 8))
        title.swipeDown(velocity: .fast)
        XCTAssertTrue(app.buttons["whats-new-continue"].waitForNonExistence(timeout: 5))
    }

    func testAboutCreditsMatchAndroid() throws {
        let app = UITestSupport.launch(arguments: ["-ui-testing-demo", "-ui-testing-reset-settings"])
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 10))
        UITestSupport.tap(app.buttons["Settings"].firstMatch)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.scrollForm(untilExists: app.staticTexts["About Codex Meter"], in: app)
        if !UITestSupport.tap(app.buttons["About Codex Meter"]) {
            UITestSupport.tap(app.staticTexts["About Codex Meter"])
        }
        XCTAssertTrue(app.navigationBars["About"].waitForExistence(timeout: 5))
        for id in ["benit", "josh", "robert", "filip"] {
            let row = app.descendants(matching: .any)["about-credit-" + id].firstMatch
            UITestSupport.scrollForm(untilExists: row, in: app)
            XCTAssertTrue(row.exists, id)
        }
        XCTAssertTrue(app.staticTexts["Live usage notifications and the Now Bar"].exists)
        XCTAssertFalse(app.links["OpenAI"].exists)
    }
}
