import XCTest

/// Walks the offline demo for humans who have no iPhone or Mac.
/// Writes numbered PNGs to /tmp/codex-meter-gallery and is recorded by CI via
/// simctl; `.tour-started` / `.tour-finished` markers in the same directory
/// tell ci/record-demo-gallery.sh when to start and stop the recording.
@MainActor
final class DemoGalleryTests: XCTestCase {
    // Built with the test case: setUpWithError isn't main-actor isolated, so it can't assign it.
    private let gallery = DemoGalleryCapture()

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    func testRecordDemoGallery() throws {
        gallery.mark("tour-started")
        UITestSupport.settle(2.0)
        defer { gallery.mark("tour-finished") }

        // What's new after a big update comes first: the whole tour runs close to its time cap.
        // A sheet; Continue (or swiping it down) closes it for good.
        let app = UITestSupport.launch(arguments: ["-ui-testing-whats-new"])
        XCTAssertTrue(app.buttons["whats-new-continue"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.0)
        capture("00-whats-new")
        UITestSupport.tap(app.buttons["whats-new-continue"])
        XCTAssertTrue(app.buttons["whats-new-continue"].waitForNonExistence(timeout: 5))

        app.terminate()
        app.launchArguments = ["-ui-testing-reset-settings"]
        app.launch()
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
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 8))
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
        XCTAssertTrue(app.staticTexts["Included usage · Weekly"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("03c-cursor-demo")
        UITestSupport.tap(app.buttons["Priority"].firstMatch)
        XCTAssertTrue(app.navigationBars["Priority"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["New models"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("03d-priority-demo")
        UITestSupport.tap(app.buttons["priority-edit"])
        XCTAssertTrue(app.navigationBars["Edit Priority"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("03e-priority-edit")
        UITestSupport.tap(app.buttons["priority-edit-done"])
        XCTAssertTrue(app.navigationBars["Edit Priority"].waitForNonExistence(timeout: 5))
        UITestSupport.tap(app.buttons["Codex"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.6)

        UITestSupport.scrollDashboard(untilVisible: app.staticTexts["17,000 credits remaining"], in: app)
        UITestSupport.settle(0.6)
        capture("04-demo-monthly-credit-limit")

        UITestSupport.scrollDashboard(untilVisible: app.staticTexts["Usage history"], in: app)
        UITestSupport.settle(0.6)
        capture("05-demo-usage-history")

        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app)
        UITestSupport.settle(0.6)
        capture("06-demo-reset-credits")

        UITestSupport.tap(app.buttons["Use 1 reset"])
        XCTAssertTrue(app.navigationBars["Codex reset"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("07-reset-sheet")
        UITestSupport.tap(app.buttons["Use reset"])
        XCTAssertTrue(app.alerts["Use one Codex reset?"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.6)
        capture("08-reset-confirm")
        UITestSupport.tap(app.alerts["Use one Codex reset?"].buttons["Cancel"])
        UITestSupport.tap(app.buttons["Close"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.4)

        UITestSupport.tap(app.buttons["Edit dashboard"])
        XCTAssertTrue(app.navigationBars["Edit dashboard"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("09-edit-dashboard")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))

        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("10-settings")

        // Usage > Reset credits: one section per provider. Codex expiry auto-use is on by
        // default; running-low asks first.
        UITestSupport.scrollForm(untilExists: app.buttons["settings-reset-credits"], in: app)
        UITestSupport.tap(app.buttons["settings-reset-credits"])
        XCTAssertTrue(app.navigationBars["Reset credits"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("09b-settings-reset-auto-use")
        let lowUsageSwitch = app.switches["Use when running low"].firstMatch
        if lowUsageSwitch.waitForExistence(timeout: 3) {
            lowUsageSwitch.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        }
        if app.alerts["Use resets automatically?"].waitForExistence(timeout: 5) {
            UITestSupport.settle(0.5)
            capture("09c-reset-auto-use-confirm")
            UITestSupport.tap(app.alerts["Use resets automatically?"].buttons["Turn on"])
            UITestSupport.settle(0.8)
            capture("09d-reset-auto-use-on")
        }
        // The Claude Code section, with its demo saved resets.
        UITestSupport.scrollForm(untilExists: app.buttons["Check with Anthropic"], in: app, maxAttempts: 4)
        UITestSupport.settle(0.5)
        capture("09e-settings-claude-resets")
        tapBack(in: app, from: "Reset credits", to: "Settings")

        UITestSupport.scrollForm(untilExists: app.buttons["settings-notifications"], in: app)
        UITestSupport.tap(app.buttons["settings-notifications"])
        XCTAssertTrue(app.navigationBars["Notifications"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["System permission"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.5)
        capture("11-settings-notifications")
        tapBack(in: app, from: "Notifications", to: "Settings")

        UITestSupport.tap(app.buttons["settings-live-activity"])
        if app.navigationBars["Live Activity"].waitForExistence(timeout: 5) {
            UITestSupport.settle(0.5)
            capture("11a-settings-live-activity")
            tapBack(in: app, from: "Live Activity", to: "Settings")
        }

        UITestSupport.scrollForm(untilExists: app.buttons["settings-privacy-data"], in: app)
        UITestSupport.tap(app.buttons["settings-privacy-data"])
        XCTAssertTrue(app.navigationBars["Privacy & data"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.5)
        capture("11b-settings-privacy-data")
        if !UITestSupport.tap(app.buttons["View usage history"]) {
            UITestSupport.tap(app.staticTexts["View usage history"])
        }
        XCTAssertTrue(app.navigationBars["Usage history"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("12-usage-history")
        tapBack(in: app, from: "Usage history", to: "Privacy & data")
        tapBack(in: app, from: "Privacy & data", to: "Settings")

        UITestSupport.scrollForm(untilExists: app.staticTexts["About Codex Meter"], in: app)
        if !UITestSupport.tap(app.buttons["About Codex Meter"]) {
            UITestSupport.tap(app.staticTexts["About Codex Meter"])
        }
        XCTAssertTrue(app.navigationBars["About"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("13-about")
        tapBack(in: app, from: "About", to: "Settings")

        UITestSupport.scrollForm(untilExists: app.segmentedControls.buttons["Dark"], in: app)
        UITestSupport.settle(0.3)
        UITestSupport.tap(app.segmentedControls.buttons["Dark"])
        UITestSupport.settle(0.8)
        capture("14-settings-dark")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))

        // Relaunch so the dark dashboard is captured from the top, with the
        // meters visible, instead of at whatever scroll offset the tour left.
        app.terminate()
        app.launchArguments = ["-ui-testing-demo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.2)
        capture("15-demo-dashboard-dark")

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
        capture("16-refresh-failure")

        // Largest accessibility text size: the signed-out hero, the meters, the
        // in-card glyphs, and the settings form all scale with Dynamic Type and
        // must not clip. Every dashboard still is gated on the dashboard actually
        // appearing so a missed step cannot pass off another screen as it.
        app.terminate()
        app.launchArguments = [
            "-ui-testing-signed-out",
            "-ui-testing-reset-settings",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.0)
        capture("16a-signed-out-ax5")

        // Relaunch straight into demo: entering it from the signed-out card would
        // keep that card's scroll offset, and the dashboard must be shot from the top.
        app.terminate()
        app.launchArguments = [
            "-ui-testing-demo",
            "-ui-testing-reset-settings",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        guard app.navigationBars["Codex"].waitForExistence(timeout: 8),
              app.staticTexts["5-hour"].waitForExistence(timeout: 8) else {
            XCTFail("The demo dashboard did not appear at the AX5 text size")
            return
        }
        UITestSupport.settle(1.2)
        capture("16b-demo-dashboard-ax5")

        // One long drag brings the first meter card fully into view: ring on top,
        // full-width title and details stacked beneath it.
        UITestSupport.dragDashboard(in: app, from: 0.92)
        UITestSupport.settle(0.8)
        capture("16c-demo-meter-ax5")

        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("16d-settings-ax5")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))

        // Reset credits is the last dashboard card, so the end of the page is the
        // card; scroll there blind and ask the accessibility tree only once.
        UITestSupport.scrollDashboardToBottom(in: app)
        UITestSupport.settle(0.8)
        let useReset = app.buttons["Use 1 reset"]
        // A stroke the history chart swallows can leave the blind passes short of the end, so
        // finish with the checked scroll, which stops once the button is in view.
        if !(useReset.waitForExistence(timeout: 5) && UITestSupport.isFullyVisible(useReset, in: app)) {
            UITestSupport.scrollDashboard(untilVisible: useReset, in: app, maxAttempts: 6)
            UITestSupport.settle(0.6)
        }
        if useReset.exists, UITestSupport.isFullyVisible(useReset, in: app) {
            capture("16e-demo-reset-credits-ax5")
        } else {
            XCTFail("Reset credits card was not reached at the AX5 text size")
        }
        UITestSupport.settle(0.8)

        // Settings → Providers narrows and orders the tabs: two providers keep the bar (in the
        // chosen order, Cursor first here), and one hides it.
        app.terminate()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-providers", "cursor,codex"]
        app.launch()
        XCTAssertTrue(app.buttons["Cursor"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["Claude Code"].exists)
        UITestSupport.tap(app.buttons["Cursor"])
        XCTAssertTrue(app.staticTexts["Included usage · Weekly"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("17-providers-two-tabs")

        app.terminate()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-providers", "cursor"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Cursor"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Included usage · Weekly"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Codex"].exists)
        UITestSupport.settle(1.0)
        capture("18-providers-cursor-only")

        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.scrollForm(untilExists: app.buttons["settings-providers"], in: app)
        UITestSupport.tap(app.buttons["settings-providers"])
        XCTAssertTrue(app.navigationBars["Providers"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("19-providers-settings")

        // First-run setup on a fresh install: nothing is preselected, each chosen provider
        // gets its own Connect step.
        app.terminate()
        app.launchArguments = ["-ui-testing-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["Get started"].waitForExistence(timeout: 8))
        UITestSupport.settle(1.0)
        capture("20-onboarding-welcome")

        UITestSupport.tap(app.buttons["Get started"])
        XCTAssertTrue(app.staticTexts["Choose your providers"].waitForExistence(timeout: 5))
        UITestSupport.tap(app.buttons["onboarding-provider-claude_code"])
        UITestSupport.tap(app.buttons["onboarding-provider-cursor"])
        UITestSupport.settle(0.8)
        capture("21-onboarding-providers")

        UITestSupport.tap(app.buttons["Continue"])
        XCTAssertTrue(app.staticTexts["Connect Claude Code"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("22-onboarding-connect")

        // Settings → Providers: each provider with its account line and tab switch, then Add
        // account (nothing is connected on a signed-out install).
        app.terminate()
        app.launchArguments = [
            "-ui-testing-signed-out",
            "-ui-testing-providers", "codex,claude_code,cursor"
        ]
        app.launch()
        XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 8))
        UITestSupport.tap(app.buttons["Settings"])
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("23-settings-providers-row")
        UITestSupport.tap(app.buttons["settings-providers"])
        XCTAssertTrue(app.navigationBars["Providers"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["account-add"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("23b-providers-page")

        // A provider's own page: not signed in here, so it offers Sign in.
        UITestSupport.tap(app.buttons["provider-codex"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["provider-sign-in"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("23c-provider-page")
        tapBack(in: app, from: "Codex", to: "Providers")

        // Add account: the searchable picker of every provider, then each sign-in style.
        UITestSupport.tap(app.buttons["account-add"])
        XCTAssertTrue(app.navigationBars["Add account"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("24-add-account")

        UITestSupport.tap(app.buttons["add-account-copilot"])
        XCTAssertTrue(app.navigationBars["Connect Copilot"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("25-sign-in-device-code")
        tapBack(in: app, from: "Connect Copilot", to: "Add account")

        if !app.buttons["add-account-windsurf"].exists { app.swipeUp() }
        UITestSupport.tap(app.buttons["add-account-windsurf"])
        XCTAssertTrue(app.navigationBars["Connect Windsurf"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("26-sign-in-paste-experimental")
        tapBack(in: app, from: "Connect Windsurf", to: "Add account")

        if !app.buttons["add-account-zai"].exists { app.swipeUp() }
        UITestSupport.tap(app.buttons["add-account-zai"])
        XCTAssertTrue(app.navigationBars["Connect Z.ai"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("27-sign-in-api-key")

        // An added provider's tab, with local demo data.
        app.terminate()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-providers", "codex,copilot,kimi,zai"]
        app.launch()
        XCTAssertTrue(app.buttons["Copilot"].waitForExistence(timeout: 8))
        UITestSupport.tap(app.buttons["Copilot"])
        XCTAssertTrue(app.navigationBars["Copilot"].waitForExistence(timeout: 5))
        _ = app.staticTexts["Weekly"].waitForExistence(timeout: 5)
        UITestSupport.settle(1.0)
        capture("28-copilot-demo-tab")

        // More: with more tabs than the bar holds, the system's More tab is a pushed list; a row
        // pushes its provider with Back to More (Android's Liquid Glass design does the same).
        app.terminate()
        app.launchArguments = ["-ui-testing-demo", "-ui-testing-providers", "codex,claude_code,cursor,copilot,kimi,zai"]
        app.launch()
        let more = app.tabBars.buttons["More"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 8))
        UITestSupport.tap(more)
        XCTAssertTrue(app.navigationBars["More"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("29-more-list")
        UITestSupport.tap(app.cells.containing(.staticText, identifier: "Z.ai").firstMatch)
        XCTAssertTrue(app.navigationBars["Z.ai"].waitForExistence(timeout: 5))
        UITestSupport.settle(1.0)
        capture("30-more-pushed")
        tapBack(in: app, from: "Z.ai", to: "More")
        capture("31-more-back")
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
}
