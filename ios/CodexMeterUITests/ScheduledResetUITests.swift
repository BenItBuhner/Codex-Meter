import XCTest

@MainActor
final class ScheduledResetUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testArmingRequiresConfirmationAndShowsArmedStateUntilCancelled() throws {
        let app = launchDemo()
        openScheduleSheet(in: app)

        UITestSupport.tap(app.buttons["scheduledReset.arm"])
        let confirmation = app.alerts["Schedule a Codex reset?"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(
            confirmation.staticTexts.matching(
                NSPredicate(
                    format: "label CONTAINS %@",
                    "Codex Meter will use 1 reset credit when your 5-hour limit reaches 5% remaining."
                )
            ).firstMatch.exists
        )
        confirmation.buttons["Cancel"].tap()
        XCTAssertFalse(confirmation.exists)
        XCTAssertTrue(app.navigationBars["Scheduled reset"].exists)

        UITestSupport.tap(app.buttons["scheduledReset.arm"])
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons["Schedule reset"].tap()

        XCTAssertTrue(app.staticTexts["Scheduled · when 5-hour reaches 5%"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["resetCredits.cancelSchedule"].exists)
        XCTAssertTrue(app.staticTexts["2 resets available"].exists)

        UITestSupport.tap(app.buttons["resetCredits.cancelSchedule"])
        XCTAssertTrue(app.buttons["resetCredits.schedule"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Scheduled · when 5-hour reaches 5%"].exists)
    }

    func testThresholdScheduleFiresOnDemoRefreshAndConsumesOneCredit() throws {
        let app = launchDemo(burnStep: 30)
        openScheduleSheet(in: app)

        UITestSupport.tap(app.buttons["scheduledReset.arm"])
        let confirmation = app.alerts["Schedule a Codex reset?"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons["Schedule reset"].tap()
        XCTAssertTrue(app.staticTexts["Scheduled · when 5-hour reaches 5%"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["2 resets available"].exists)

        // 62% remaining → 32% → 2%: the second refresh crosses the 5% threshold.
        refresh(app)
        XCTAssertTrue(app.staticTexts["Scheduled · when 5-hour reaches 5%"].exists)
        XCTAssertTrue(app.staticTexts["2 resets available"].exists)
        refresh(app)

        XCTAssertTrue(app.staticTexts["1 reset available"].waitForExistence(timeout: 5))
        let outcome = app.staticTexts["scheduledReset.outcome"]
        XCTAssertTrue(outcome.waitForExistence(timeout: 5))
        XCTAssertTrue(
            outcome.label.hasPrefix("Scheduled reset used 1 credit — Your 5-hour limit reached 2% remaining."),
            outcome.label
        )
        XCTAssertFalse(app.staticTexts["Scheduled · when 5-hour reaches 5%"].exists)
        XCTAssertTrue(app.buttons["resetCredits.schedule"].exists)
    }

    private func launchDemo(burnStep: Int? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-reset-settings"]
        if let burnStep {
            app.launchArguments += ["-ui-testing-demo-burn-step", "\(burnStep)"]
        }
        app.launch()
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 5))
        return app
    }

    private func openScheduleSheet(in app: XCUIApplication) {
        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app)
        XCTAssertTrue(app.buttons["resetCredits.schedule"].waitForExistence(timeout: 5))
        UITestSupport.tap(app.buttons["resetCredits.schedule"])
        XCTAssertTrue(app.navigationBars["Scheduled reset"].waitForExistence(timeout: 5))
    }

    private func refresh(_ app: XCUIApplication) {
        UITestSupport.tap(app.buttons["Refresh usage"])
        XCTAssertTrue(app.buttons["Refresh usage"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.4)
    }
}
