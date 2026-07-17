import XCTest

@MainActor
final class CodexMeterUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testDemoDashboardAndSettings() throws {
        let app = launchDemo()

        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["5-hour"].exists)
        XCTAssertTrue(app.staticTexts["Weekly"].exists)
        XCTAssertTrue(app.staticTexts["Demo data — no OpenAI requests"].exists)

        app.buttons["Settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Mode"].exists)
        XCTAssertTrue(app.buttons["Leave Demo"].exists)
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["System permission"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Send test notification"].waitForExistence(timeout: 3))
    }

    func testSignedOutAndSignInPresentation() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing-signed-out", "-ui-testing-reset-settings"]
        app.launch()

        XCTAssertTrue(app.buttons["Sign in with ChatGPT"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Explore demo"].exists)
        app.buttons["Sign in with ChatGPT"].tap()
        XCTAssertTrue(app.navigationBars["Connect account"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Get one-time code"].exists)
        XCTAssertTrue(
            app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS %@", "device-code flow")
            ).firstMatch.exists
        )
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 3))
    }

    func testResetRequiresIrreversibleConfirmation() throws {
        let app = launchDemo()

        XCTAssertTrue(app.buttons["Use 1 reset"].waitForExistence(timeout: 5))
        app.buttons["Use 1 reset"].tap()
        XCTAssertTrue(app.navigationBars["Codex reset"].waitForExistence(timeout: 3))
        app.buttons["Use reset"].tap()
        XCTAssertTrue(app.alerts["Use one Codex reset?"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.alerts["Use one Codex reset?"].staticTexts["This action consumes one available reset credit and cannot be undone."].exists)
        app.alerts["Use one Codex reset?"].buttons["Cancel"].tap()
        XCTAssertFalse(app.alerts["Use one Codex reset?"].exists)
    }

    func testRefreshFailureKeepsCachedDashboardVisible() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ui-testing-demo",
            "-ui-testing-reset-settings",
            "-ui-testing-refresh-failure"
        ]
        app.launch()

        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 5))
        app.buttons["Refresh usage"].tap()
        XCTAssertTrue(app.staticTexts["Demo refresh failed. Showing the last cached snapshot."].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["5-hour"].exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Showing cached data'")).firstMatch.exists)
    }

    func testLeaveDemoReturnsToSignedOutExperience() throws {
        let app = launchDemo()

        app.buttons["Settings"].tap()
        XCTAssertTrue(app.buttons["Leave Demo"].waitForExistence(timeout: 3))
        app.buttons["Leave Demo"].tap()
        XCTAssertTrue(app.buttons["Sign in with ChatGPT"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Explore demo"].exists)
    }

    private func launchDemo() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-reset-settings"]
        app.launch()
        return app
    }
}
