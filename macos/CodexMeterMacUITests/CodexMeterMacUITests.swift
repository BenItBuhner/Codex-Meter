import XCTest

/// End-to-end checks of the Mac shell around the shared dashboards, all offline (demo or
/// signed out).
@MainActor
final class CodexMeterMacUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    override func tearDown() {
        XCUIApplication().terminate()
        super.tearDown()
    }

    func testSidebarSwitchesBetweenProviderDashboards() throws {
        let app = MacUITestSupport.launch(MacUITestSupport.demo)

        let codex = MacUITestSupport.sidebarRow("codex", in: app)
        XCTAssertTrue(codex.waitForExistence(timeout: 10), "Codex sidebar row")
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 10))
        XCTAssertTrue(MacUITestSupport.text("Demo data — no OpenAI requests", in: app).exists)

        XCTAssertTrue(MacUITestSupport.click(MacUITestSupport.sidebarRow("claude_code", in: app)))
        XCTAssertTrue(MacUITestSupport.text("Weekly", in: app).waitForExistence(timeout: 8))

        XCTAssertTrue(MacUITestSupport.click(MacUITestSupport.sidebarRow("cursor", in: app)))
        XCTAssertTrue(
            MacUITestSupport.text("Included usage · Weekly", in: app).waitForExistence(timeout: 8)
        )

        XCTAssertTrue(MacUITestSupport.click(codex))
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 8))
    }

    func testKeyboardShortcutsSelectProviders() throws {
        let app = MacUITestSupport.launch(MacUITestSupport.demo)
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 10))

        app.typeKey("3", modifierFlags: .command)
        XCTAssertTrue(
            MacUITestSupport.text("Included usage · Weekly", in: app).waitForExistence(timeout: 8)
        )
        app.typeKey("1", modifierFlags: .command)
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 8))
    }

    func testSignedOutCodexOffersSignInAndDemo() throws {
        let app = MacUITestSupport.launch(MacUITestSupport.signedOut)

        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 10))
        XCTAssertTrue(MacUITestSupport.click(app.buttons["Sign in with ChatGPT"]))
        XCTAssertTrue(app.buttons["Get one-time code"].waitForExistence(timeout: 8))
        XCTAssertTrue(MacUITestSupport.click(app.buttons["Cancel"]))
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 8))

        XCTAssertTrue(MacUITestSupport.click(app.buttons["Explore demo"]))
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 10))
    }

    func testSettingsWindowShowsSharedAndMacSettings() throws {
        let app = MacUITestSupport.launch(MacUITestSupport.demo)
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 10))

        MacUITestSupport.openSettings(in: app)
        // The Accounts row is a navigation link whose title and summary read as one element.
        let accounts = app.descendants(matching: .any).matching(identifier: "settings-accounts").firstMatch
        XCTAssertTrue(accounts.waitForExistence(timeout: 8), "Accounts row in Settings")
        XCTAssertTrue(
            app.buttons["Leave Demo"].exists
                || app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Leave Demo' OR value == 'Demo'")).firstMatch.exists
        )

        XCTAssertTrue(MacUITestSupport.click(app.buttons["Menu Bar"]))
        XCTAssertTrue(
            app.checkBoxes["Show Codex Meter in the menu bar"].waitForExistence(timeout: 5)
                || app.switches["Show Codex Meter in the menu bar"].waitForExistence(timeout: 2)
        )
    }

    func testMenuBarPanelSummarizesEveryProvider() throws {
        let app = MacUITestSupport.launch(MacUITestSupport.demo + ["-ui-testing-menu-bar-preview"])

        for id in ["codex", "claude_code", "cursor"] {
            let card = app.descendants(matching: .any).matching(identifier: "menu-card-\(id)").firstMatch
            XCTAssertTrue(card.waitForExistence(timeout: 10), "menu bar card for \(id)")
        }
        XCTAssertTrue(app.buttons["Open Codex Meter"].exists)
        // Each meter row combines its texts into one element, so match on any element type.
        let meters = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS '% left'"))
        XCTAssertGreaterThanOrEqual(meters.count, 3, "a remaining percentage for every provider")
    }

    func testStatusItemIsInstalled() throws {
        let app = MacUITestSupport.launch(MacUITestSupport.demo)
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 10))
        XCTAssertTrue(app.statusItems.firstMatch.waitForExistence(timeout: 8), "menu bar status item")
    }
}
