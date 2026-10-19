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

        UITestSupport.tap(app.buttons["Claude Code"])
        XCTAssertTrue(app.buttons["Sign in with Claude"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("02b-claude-signed-out")
        UITestSupport.tap(app.buttons["Sign in with Claude"])
        XCTAssertTrue(app.navigationBars["Connect Claude"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("02c-claude-sign-in")
        UITestSupport.tap(app.buttons["Cancel"])
        XCTAssertTrue(app.buttons["Sign in with Claude"].waitForExistence(timeout: 5))

        UITestSupport.tap(app.buttons["Cursor"])
        XCTAssertTrue(app.buttons["Sign in with Cursor"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("02d-cursor-signed-out")
        UITestSupport.tap(app.buttons["Sign in with Cursor"])
        XCTAssertTrue(app.navigationBars["Connect Cursor"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("02e-cursor-sign-in")
        UITestSupport.tap(app.buttons["Cancel"])
        XCTAssertTrue(app.buttons["Sign in with Cursor"].waitForExistence(timeout: 5))
        UITestSupport.tap(app.buttons["Codex"])
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.5)

        UITestSupport.tap(app.buttons["Explore demo"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.2)
        capture("03-demo-dashboard")

        UITestSupport.tap(app.buttons["Claude Code"])
        XCTAssertTrue(app.navigationBars["Claude Code"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Weekly"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("03b-claude-code-demo")
        UITestSupport.tap(app.buttons["Cursor"])
        XCTAssertTrue(app.navigationBars["Cursor"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Included usage"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("03c-cursor-demo")
        UITestSupport.tap(app.buttons["Codex"])
        XCTAssertTrue(app.navigationBars["Codex Meter"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.6)

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
        UITestSupport.settle(0.8)

        // Settings → Providers narrows and orders the tabs: two providers keep the bar (in the
        // chosen order, Cursor first here), and one hides it.
        app.terminate()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-providers", "cursor,codex"]
        app.launch()
        XCTAssertTrue(app.buttons["Cursor"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["Claude Code"].exists)
        UITestSupport.tap(app.buttons["Cursor"])
        XCTAssertTrue(app.staticTexts["Included usage"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("16-providers-two-tabs")

        app.terminate()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-providers", "cursor"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Cursor"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Included usage"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Codex"].exists)
        UITestSupport.settle(1.0)
        capture("17-providers-cursor-only")

        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.scrollForm(untilExists: app.buttons["settings-providers"], in: app)
        UITestSupport.tap(app.buttons["settings-providers"])
        XCTAssertTrue(app.navigationBars["Providers"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("18-providers-settings")
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
        for _ in 0..<6 where !app.staticTexts["Account"].exists {
            app.swipeDown()
        }
    }
}
