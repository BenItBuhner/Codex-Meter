import XCTest

/// Walks the offline demo for humans who have no iPhone or Mac.
/// Writes numbered PNGs to /tmp/codex-meter-gallery and is recorded by CI via
/// simctl; `.tour-started` / `.tour-finished` markers in the same directory
/// tell ci/record-demo-gallery.sh when to start and stop the recording.
@MainActor
final class DemoGalleryTests: XCTestCase {
    private var gallery: DemoGalleryCapture!

    override func setUpWithError() throws {
        continueAfterFailure = true
        gallery = DemoGalleryCapture()
    }

    func testRecordDemoGallery() throws {
        gallery.mark("tour-started")
        UITestSupport.settle(2.0)
        defer { gallery.mark("tour-finished") }

        let app = UITestSupport.launch(arguments: ["-ui-testing-reset-settings"])
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.0)
        capture("01-signed-out")

        UITestSupport.tap(app.buttons["Sign in with ChatGPT"])
        XCTAssertTrue(app.navigationBars["Connect account"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("02-sign-in")
        UITestSupport.tap(app.buttons["Cancel"])
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.5)

        UITestSupport.tap(app.buttons["Explore demo"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.2)
        capture("03-demo-dashboard")

        UITestSupport.scrollDashboard(untilVisible: app.staticTexts["Usage history"], in: app)
        UITestSupport.settle(0.6)
        capture("04-demo-usage-history")

        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app)
        UITestSupport.settle(0.6)
        capture("05-demo-reset-credits")

        UITestSupport.tap(app.buttons["Use 1 reset"])
        XCTAssertTrue(app.navigationBars["Codex reset"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("06-reset-sheet")
        UITestSupport.tap(app.buttons["Use reset"])
        XCTAssertTrue(app.alerts["Use one Codex reset?"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.6)
        capture("07-reset-confirm")
        UITestSupport.tap(app.alerts["Use one Codex reset?"].buttons["Cancel"])
        UITestSupport.tap(app.buttons["Close"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.4)

        UITestSupport.tap(app.buttons["Edit dashboard"])
        XCTAssertTrue(app.navigationBars["Edit dashboard"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("08-edit-dashboard")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))

        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("09-settings")

        UITestSupport.scrollForm(untilExists: app.staticTexts["System permission"], in: app)
        UITestSupport.settle(0.5)
        capture("10-settings-notifications")

        UITestSupport.scrollForm(untilExists: app.staticTexts["Data"], in: app)
        UITestSupport.settle(0.4)
        if !UITestSupport.tap(app.buttons["View usage history"]) {
            UITestSupport.tap(app.staticTexts["View usage history"])
        }
        XCTAssertTrue(app.navigationBars["Usage history"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("11-usage-history")
        tapBack(in: app, from: "Usage history", to: "Settings")

        UITestSupport.scrollForm(untilExists: app.staticTexts["About Codex Meter"], in: app)
        if !UITestSupport.tap(app.buttons["About Codex Meter"]) {
            UITestSupport.tap(app.staticTexts["About Codex Meter"])
        }
        XCTAssertTrue(app.navigationBars["About"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("12-about")
        tapBack(in: app, from: "About", to: "Settings")

        scrollSettingsToTop(in: app)
        UITestSupport.settle(0.3)
        UITestSupport.tap(app.segmentedControls.buttons["Dark"])
        UITestSupport.settle(0.8)
        capture("13-settings-dark")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))

        // Relaunch so the dark dashboard is captured from the top, with the
        // meters visible, instead of at whatever scroll offset the tour left.
        app.terminate()
        app.launchArguments = ["-ui-testing-demo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.2)
        capture("14-demo-dashboard-dark")

        app.terminate()
        app.launchArguments = [
            "-ui-testing-demo",
            "-ui-testing-reset-settings",
            "-ui-testing-refresh-failure"
        ]
        app.launch()
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 8))
        UITestSupport.settle(0.8)
        UITestSupport.tap(app.buttons["Refresh usage"])
        _ = app.staticTexts["Demo refresh failed. Showing the last cached snapshot."]
            .waitForExistence(timeout: 5)
        UITestSupport.settle(1.0)
        capture("15-refresh-failure")

        // Largest accessibility text size: the signed-out hero, the meters, the
        // in-card glyphs, and the settings form all scale with Dynamic Type and
        // must not clip. One launch covers every screen; launches are the
        // slowest tour step. The signed-out card is taller than the screen here,
        // so "Explore demo" has to be scrolled into view before it can be tapped,
        // and every later still is gated on the dashboard actually appearing so
        // a missed tap cannot pass off the signed-out screen as the dashboard.
        app.terminate()
        app.launchArguments = [
            "-ui-testing-signed-out",
            "-ui-testing-reset-settings",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.0)
        capture("16-signed-out-ax5")

        UITestSupport.scrollDashboard(untilVisible: app.buttons["Explore demo"], in: app)
        XCTAssertTrue(UITestSupport.isFullyVisible(app.buttons["Explore demo"], in: app))
        UITestSupport.tap(app.buttons["Explore demo"])
        guard app.navigationBars["Codex Meter"].waitForExistence(timeout: 8),
              app.staticTexts["5-hour"].waitForExistence(timeout: 8) else {
            XCTFail("Explore demo did not open the demo dashboard at the AX5 text size")
            return
        }
        UITestSupport.settle(1.2)
        capture("17-demo-dashboard-ax5")

        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("18-settings-ax5")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))

        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app, maxAttempts: 30)
        UITestSupport.settle(0.6)
        if UITestSupport.isFullyVisible(app.buttons["Use 1 reset"], in: app) {
            capture("19-demo-reset-credits-ax5")
        } else {
            XCTFail("Reset credits card was not reached at the AX5 text size")
        }
        UITestSupport.settle(0.8)
    }

    private func capture(_ name: String) {
        gallery.save(name, test: self)
    }

    private func tapBack(in app: XCUIApplication, from current: String, to title: String) {
        let bar = app.navigationBars[current]
        if bar.buttons["BackButton"].exists {
            UITestSupport.tap(bar.buttons["BackButton"])
        } else {
            UITestSupport.tap(bar.buttons[title])
        }
        _ = app.navigationBars[title].waitForExistence(timeout: 4)
        UITestSupport.settle(0.4)
    }

    private func scrollSettingsToTop(in app: XCUIApplication) {
        for _ in 0..<6 {
            if app.staticTexts["Account"].exists { return }
            app.swipeDown()
        }
    }
}
