import UIKit
import XCTest

/// Checks the adaptive iPad layout and captures stills of it. Runs only on iPad simulators
/// (it skips on iPhone), once per device size in CI: ci/record-ipad-gallery.sh copies the
/// stills from /tmp/codex-meter-ipad-gallery after each device.
@MainActor
final class IPadLayoutTests: XCTestCase {
    private static let stagingDirectory = URL(fileURLWithPath: "/tmp/codex-meter-ipad-gallery", isDirectory: true)

    override func setUpWithError() throws {
        try XCTSkipUnless(
            UIDevice.current.userInterfaceIdiom == .pad,
            "The iPad layout tests run on iPad simulators"
        )
        continueAfterFailure = true
        try? FileManager.default.createDirectory(at: Self.stagingDirectory, withIntermediateDirectories: true)
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    private func launch(_ orientation: UIDeviceOrientation, providers: String = "codex,claude_code,cursor,copilot") -> XCUIApplication {
        XCUIDevice.shared.orientation = orientation
        let app = UITestSupport.launch(arguments: [
            "-ui-testing-reset-settings",
            "-ui-testing-demo",
            "-ui-testing-providers", providers
        ])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 10))
        UITestSupport.settle(1.2)
        return app
    }

    // MARK: Layout

    func testLandscapeDashboardUsesColumnsSidebarAndKeyboard() throws {
        let app = launch(.landscapeLeft)
        capture("01-landscape-codex")
        assertSideBySide(app.staticTexts["5-hour"], app.staticTexts["Weekly"], "Codex 5-hour and Weekly share a row in landscape")

        // The sidebar holds every provider tab; opening it narrows the dashboard, which must
        // still keep at least two columns on a landscape iPad.
        if openSidebar(in: app) {
            UITestSupport.settle(1.0)
            capture("02-landscape-sidebar")
            XCTAssertTrue(app.buttons["Copilot"].firstMatch.exists, "The sidebar lists the added providers too")
            assertSideBySide(app.staticTexts["5-hour"], app.staticTexts["Weekly"], "Columns survive the sidebar")
            UITestSupport.tap(app.buttons["Claude Code"])
            XCTAssertTrue(app.navigationBars["Claude Code"].waitForExistence(timeout: 5), "Sidebar rows switch tabs")
            UITestSupport.settle(0.8)
            capture("03-landscape-sidebar-claude")
            closeSidebar(in: app)
            UITestSupport.settle(0.8)
        }

        // ⌘1…⌘4 follow the tab order; ⌘R refreshes the visible tab without leaving it.
        app.typeKey("3", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Cursor"].waitForExistence(timeout: 5), "⌘3 opens Cursor")
        XCTAssertTrue(app.staticTexts["Included usage · Weekly"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("04-landscape-cursor")

        app.typeKey("4", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Copilot"].waitForExistence(timeout: 5), "⌘4 opens Copilot")
        UITestSupport.settle(0.8)
        capture("05-landscape-copilot")

        app.typeKey("2", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Claude Code"].waitForExistence(timeout: 5), "⌘2 opens Claude Code")
        assertSideBySide(app.staticTexts["5-hour"], app.staticTexts["Weekly"], "Claude Code windows share a row")
        UITestSupport.settle(0.6)
        capture("06-landscape-claude")

        app.typeKey("1", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5), "⌘1 returns to Codex")
        app.typeKey("r", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["5-hour"].waitForExistence(timeout: 5), "⌘R keeps the dashboard")

        app.typeKey(",", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5), "⌘, opens Settings")
        UITestSupport.settle(0.8)
        capture("07-landscape-settings")
        UITestSupport.tap(app.buttons["Done"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))

        UITestSupport.scrollDashboard(untilVisible: app.staticTexts["Usage history"], in: app)
        UITestSupport.settle(0.8)
        capture("08-landscape-codex-history")
    }

    func testPortraitDashboardKeepsTwoColumns() throws {
        let app = launch(.portrait)
        capture("10-portrait-codex")
        assertSideBySide(app.staticTexts["5-hour"], app.staticTexts["Weekly"], "Portrait iPad shows two columns")

        UITestSupport.tap(app.buttons["Cursor"])
        XCTAssertTrue(app.navigationBars["Cursor"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("11-portrait-cursor")

        UITestSupport.tap(app.buttons["Copilot"])
        XCTAssertTrue(app.navigationBars["Copilot"].waitForExistence(timeout: 5))
        UITestSupport.settle(0.8)
        capture("12-portrait-copilot")

        UITestSupport.tap(app.buttons["Codex"])
        XCTAssertTrue(app.navigationBars["Codex"].waitForExistence(timeout: 5))
        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app)
        UITestSupport.settle(0.8)
        capture("13-portrait-codex-scrolled")

        // Rotating re-flows the same dashboard into landscape columns without a relaunch.
        XCUIDevice.shared.orientation = .landscapeRight
        UITestSupport.settle(1.5)
        capture("14-rotated-to-landscape")
        XCUIDevice.shared.orientation = .portrait
        UITestSupport.settle(1.0)
    }

    func testSignedOutAndOnboardingStayCardSized() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = UITestSupport.launch(arguments: ["-ui-testing-reset-settings", "-ui-testing-signed-out"])
        let signIn = app.buttons["Sign in with ChatGPT"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 8))
        UITestSupport.settle(1.0)
        XCTAssertTrue(app.staticTexts["Your Codex allowance at a glance"].exists)
        capture("20-landscape-signed-out")
        app.terminate()

        let onboarding = UITestSupport.launch(arguments: ["-ui-testing-onboarding"])
        UITestSupport.settle(2.0)
        capture("21-landscape-onboarding")
        onboarding.terminate()
    }

    // MARK: Helpers

    /// Two cards are side by side when their titles share a line and start in different
    /// columns.
    private func assertSideBySide(
        _ first: XCUIElement,
        _ second: XCUIElement,
        _ message: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let a = first.firstMatch
        let b = second.firstMatch
        guard a.waitForExistence(timeout: 5), b.waitForExistence(timeout: 5) else {
            XCTFail("\(message): cards not found", file: file, line: line)
            return
        }
        XCTAssertEqual(a.frame.midY, b.frame.midY, accuracy: 6, message, file: file, line: line)
        XCTAssertGreaterThan(abs(a.frame.minX - b.frame.minX), 150, message, file: file, line: line)
    }

    /// Shows the sidebar from the tab bar's sidebar button, if this iPadOS version offers one.
    private func openSidebar(in app: XCUIApplication) -> Bool {
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sidebar'")).firstMatch
        guard button.waitForExistence(timeout: 3) else { return false }
        button.tap()
        return app.buttons["sidebar-settings"].waitForExistence(timeout: 4)
            || app.buttons["Copilot"].firstMatch.waitForExistence(timeout: 2)
    }

    private func closeSidebar(in app: XCUIApplication) {
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sidebar'")).firstMatch
        if button.exists, button.isHittable {
            button.tap()
        }
    }

    private func capture(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        try? screenshot.pngRepresentation.write(to: Self.stagingDirectory.appendingPathComponent("\(name).png"))
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
