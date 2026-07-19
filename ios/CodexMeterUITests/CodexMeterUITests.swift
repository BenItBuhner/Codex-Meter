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
        app.staticTexts["Account"].tap()
        XCTAssertTrue(app.staticTexts["Mode"].exists)
        XCTAssertTrue(app.buttons["Leave Demo"].exists)
        app.navigationBars["Account"].buttons["Settings"].tap()
        app.staticTexts["Notifications"].tap()
        XCTAssertTrue(app.staticTexts["System permission"].waitForExistence(timeout: 3))
        app.swipeUp()
        XCTAssertTrue(app.buttons["Send test notification"].waitForExistence(timeout: 3))
    }

    func testSignedOutAndSignInPresentation() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ui-testing-signed-out",
            "-ui-testing-reset-settings",
            "-ui-testing-skip-onboarding"
        ]
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
            "-ui-testing-skip-onboarding",
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
        app.staticTexts["Account"].tap()
        XCTAssertTrue(app.buttons["Leave Demo"].waitForExistence(timeout: 3))
        app.buttons["Leave Demo"].tap()
        XCTAssertTrue(app.buttons["Sign in with ChatGPT"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Explore demo"].exists)
    }

    func testSettingsHubLiveActivityAndCredentialFreeTransfer() throws {
        let app = launchDemo()
        app.buttons["Settings"].tap()

        for destination in ["Account", "Appearance", "Refresh & Usage", "Notifications", "Live Activity", "Data & Privacy", "About"] {
            XCTAssertTrue(app.staticTexts[destination].waitForExistence(timeout: 3), "Missing settings destination \(destination)")
        }

        app.staticTexts["Refresh & Usage"].tap()
        XCTAssertTrue(app.switches["Show usage pace estimates"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Accelerated-usage warnings"].exists)
        app.navigationBars["Refresh & Usage"].buttons["Settings"].tap()

        app.staticTexts["Live Activity"].tap()
        XCTAssertTrue(app.buttons["Start Live Activity"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.switches["Auto-start on accelerated usage"].exists)
        app.navigationBars["Live Activity"].buttons["Settings"].tap()

        app.staticTexts["Data & Privacy"].tap()
        XCTAssertTrue(app.buttons["Export settings…"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Import settings…"].exists)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "credentials embeds")).firstMatch.exists)
    }

    func testDashboardAdaptsToLandscape() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }

        let app = launchDemo()
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["5-hour"].exists)
        XCTAssertTrue(app.staticTexts["Weekly"].exists)
        XCTAssertTrue(app.buttons["Settings"].isHittable)
    }

    private func launchDemo() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ui-testing-demo",
            "-ui-testing-reset-settings",
            "-ui-testing-skip-onboarding"
        ]
        app.launch()
        return app
    }
}
