import XCTest

enum UITestSupport {
    static func launch(arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = arguments
        app.launch()
        return app
    }

    static func settle(_ seconds: TimeInterval = 0.8) {
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: "settle")], timeout: seconds)
    }

    @discardableResult
    static func tap(_ element: XCUIElement, timeout: TimeInterval = 5) -> Bool {
        guard element.waitForExistence(timeout: timeout) else { return false }
        if element.isHittable {
            element.tap()
        } else {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        return true
    }

    /// Scrolls the dashboard with short drags from alternating anchor points.
    /// The usage-history chart scrubs via DragGesture(minimumDistance: 0), so
    /// it swallows any scroll gesture that starts inside its plot area. The two
    /// anchors are farther apart than the plot is tall, so the chart can never
    /// capture two consecutive attempts and scrolling always makes progress.
    static func scrollDashboard(
        untilHittable element: XCUIElement,
        in app: XCUIApplication,
        maxAttempts: Int = 10
    ) {
        let anchors: [CGFloat] = [0.85, 0.45]
        for attempt in 0..<maxAttempts where !element.isHittable {
            let dy = anchors[attempt % anchors.count]
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: dy))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: dy - 0.35))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
    }

    static func scrollForm(
        untilExists element: XCUIElement,
        in app: XCUIApplication,
        maxAttempts: Int = 8
    ) {
        for _ in 0..<maxAttempts where !element.exists {
            app.swipeUp()
        }
    }
}

struct DemoGalleryCapture {
    let directory: URL

    init(environment: [String: String] = ProcessInfo.processInfo.environment) {
        let path = environment["GALLERY_OUTPUT"]
            ?? FileManager.default.temporaryDirectory
                .appendingPathComponent("codex-meter-gallery", isDirectory: true)
                .path
        directory = URL(fileURLWithPath: path, isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func save(_ name: String, test: XCTestCase) {
        let screenshot = XCUIScreen.main.screenshot()
        let file = directory.appendingPathComponent("\(name).png")
        try? screenshot.pngRepresentation.write(to: file)
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        test.add(attachment)
    }
}
