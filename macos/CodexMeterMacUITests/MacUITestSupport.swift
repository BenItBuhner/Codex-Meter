import XCTest

@MainActor
enum MacUITestSupport {
    /// Debug launch arguments understood by the shared `AppModel` and the Mac shell.
    static let demo = ["-ui-testing-reset-settings", "-ui-testing-demo"]
    static let signedOut = ["-ui-testing-reset-settings", "-ui-testing-signed-out"]

    static func launch(_ arguments: [String], appearance: String = "light") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = arguments + ["-ui-testing-appearance", appearance]
        app.launch()
        app.activate()
        return app
    }

    static func settle(_ seconds: TimeInterval = 0.8) {
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: "settle")], timeout: seconds)
    }

    /// The sidebar row for a provider (`codex`, `claude_code`, `cursor`).
    static func sidebarRow(_ providerID: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(identifier: "sidebar-\(providerID)")
            .firstMatch
    }

    @discardableResult
    static func click(_ element: XCUIElement, timeout: TimeInterval = 8) -> Bool {
        let target = element.firstMatch
        guard target.waitForExistence(timeout: timeout) else { return false }
        if target.isHittable {
            target.click()
        } else {
            target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).click()
        }
        return true
    }

    static func text(_ label: String, in app: XCUIApplication) -> XCUIElement {
        app.staticTexts[label].firstMatch
    }

    static func openSettings(in app: XCUIApplication) {
        app.typeKey(",", modifierFlags: .command)
    }
}

/// Writes gallery stills to a fixed staging folder that ci/record-mac-gallery.sh copies
/// from, and attaches each one to the test result as a fallback.
struct MacGalleryCapture {
    static let stagingDirectory = URL(fileURLWithPath: "/tmp/codex-meter-mac-gallery", isDirectory: true)

    init() {
        try? FileManager.default.createDirectory(at: Self.stagingDirectory, withIntermediateDirectories: true)
    }

    /// Saves the frontmost window, and the whole screen when `fullScreen` is set (to show the
    /// menu bar item and its panel in context).
    @MainActor
    func save(_ name: String, app: XCUIApplication, test: XCTestCase, fullScreen: Bool = false) {
        let window = app.windows.element(boundBy: 0)
        let screenshot = fullScreen || !window.exists ? XCUIScreen.main.screenshot() : window.screenshot()
        try? screenshot.pngRepresentation.write(to: Self.stagingDirectory.appendingPathComponent("\(name).png"))
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        test.add(attachment)
    }
}
