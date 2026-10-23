import XCTest

/// Walks the offline demo on the Mac and saves numbered stills (window and full screen) for
/// people who review the app without a Mac. Run by ci/record-mac-gallery.sh, skipped in the
/// regular test pass.
@MainActor
final class MacGalleryTests: XCTestCase {
    private var gallery: MacGalleryCapture!

    override func setUpWithError() throws {
        continueAfterFailure = true
        gallery = MacGalleryCapture()
    }

    func testRecordLightGallery() throws {
        tour(appearance: "light", prefix: "light")
    }

    func testRecordDarkGallery() throws {
        tour(appearance: "dark", prefix: "dark")
    }

    func testRecordOnboardingAndSignIn() throws {
        var app = MacUITestSupport.launch(["-ui-testing-onboarding"])
        MacUITestSupport.settle(2.0)
        capture("onboarding-01-welcome", app)
        app.terminate()

        app = MacUITestSupport.launch(MacUITestSupport.signedOut)
        XCTAssertTrue(app.buttons["Explore demo"].waitForExistence(timeout: 10))
        MacUITestSupport.settle(1.0)
        capture("signed-out-01-codex", app)

        MacUITestSupport.click(app.buttons["Sign in with ChatGPT"])
        XCTAssertTrue(app.buttons["Get one-time code"].waitForExistence(timeout: 8))
        MacUITestSupport.settle(0.8)
        capture("signed-out-02-codex-sign-in", app)
        MacUITestSupport.click(app.buttons["Cancel"])

        MacUITestSupport.click(MacUITestSupport.sidebarRow("claude_code", in: app))
        MacUITestSupport.settle(1.0)
        capture("signed-out-03-claude", app)
        MacUITestSupport.click(MacUITestSupport.sidebarRow("cursor", in: app))
        MacUITestSupport.settle(1.0)
        capture("signed-out-04-cursor", app)
        app.terminate()
    }

    private func tour(appearance: String, prefix: String) {
        let app = MacUITestSupport.launch(MacUITestSupport.demo, appearance: appearance)
        XCTAssertTrue(MacUITestSupport.text("5-hour", in: app).waitForExistence(timeout: 12))
        MacUITestSupport.settle(1.5)
        capture("\(prefix)-01-codex", app)
        capture("\(prefix)-01-codex-desktop", app, fullScreen: true)

        let scroll = app.scrollViews.element(boundBy: 0)
        if scroll.exists {
            scroll.scroll(byDeltaX: 0, deltaY: -600)
            MacUITestSupport.settle(0.8)
            capture("\(prefix)-02-codex-scrolled", app)
            scroll.scroll(byDeltaX: 0, deltaY: 2_000)
        }

        MacUITestSupport.click(MacUITestSupport.sidebarRow("claude_code", in: app))
        XCTAssertTrue(MacUITestSupport.text("Weekly", in: app).waitForExistence(timeout: 8))
        MacUITestSupport.settle(1.0)
        capture("\(prefix)-03-claude", app)

        MacUITestSupport.click(MacUITestSupport.sidebarRow("cursor", in: app))
        XCTAssertTrue(
            MacUITestSupport.text("Included usage · Weekly", in: app).waitForExistence(timeout: 8)
        )
        MacUITestSupport.settle(1.0)
        capture("\(prefix)-04-cursor", app)

        MacUITestSupport.click(MacUITestSupport.sidebarRow("codex", in: app))
        MacUITestSupport.settle(0.8)
        if MacUITestSupport.click(app.buttons["Edit dashboard"], timeout: 4) {
            MacUITestSupport.settle(1.0)
            capture("\(prefix)-05-edit-dashboard", app)
            app.typeKey("[", modifierFlags: .command)
            MacUITestSupport.settle(0.6)
        }

        MacUITestSupport.openSettings(in: app)
        if MacUITestSupport.text("Accounts", in: app).waitForExistence(timeout: 8) {
            MacUITestSupport.settle(1.0)
            capture("\(prefix)-06-settings", app)
            if MacUITestSupport.click(app.buttons["Menu Bar"], timeout: 4) {
                MacUITestSupport.settle(0.8)
                capture("\(prefix)-07-settings-menu-bar", app)
            }
            app.typeKey("w", modifierFlags: .command)
            MacUITestSupport.settle(0.6)
        }

        // The real status item panel, captured with the whole screen.
        let statusItem = app.statusItems.firstMatch
        if statusItem.waitForExistence(timeout: 5) {
            statusItem.click()
            MacUITestSupport.settle(1.5)
            capture("\(prefix)-08-menu-bar-panel-desktop", app, fullScreen: true)
            app.typeKey(XCUIKeyboardKey.escape.rawValue, modifierFlags: [])
            MacUITestSupport.settle(0.5)
        }
        app.terminate()

        // The same panel in a plain window, for a crisp close-up.
        let preview = MacUITestSupport.launch(
            MacUITestSupport.demo + ["-ui-testing-menu-bar-preview"],
            appearance: appearance
        )
        let card = preview.descendants(matching: .any).matching(identifier: "menu-card-cursor").firstMatch
        if card.waitForExistence(timeout: 10) {
            MacUITestSupport.settle(1.2)
            let window = preview.windows["Menu Bar Preview"]
            let shot = window.exists ? window.screenshot() : XCUIScreen.main.screenshot()
            save(shot, name: "\(prefix)-09-menu-bar-panel")
        }
        preview.terminate()
    }

    private func capture(_ name: String, _ app: XCUIApplication, fullScreen: Bool = false) {
        gallery.save(name, app: app, test: self, fullScreen: fullScreen)
    }

    private func save(_ screenshot: XCUIScreenshot, name: String) {
        try? screenshot.pngRepresentation.write(
            to: MacGalleryCapture.stagingDirectory.appendingPathComponent("\(name).png")
        )
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
