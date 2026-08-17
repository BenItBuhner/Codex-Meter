import XCTest

@MainActor
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
        let target = element.firstMatch
        guard target.waitForExistence(timeout: timeout) else { return false }
        if target.isHittable {
            target.tap()
        } else {
            target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
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
        let target = element.firstMatch
        let anchors: [CGFloat] = [0.85, 0.45]
        for attempt in 0..<maxAttempts where !target.isHittable {
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
        let target = element.firstMatch
        for _ in 0..<maxAttempts where !target.exists {
            app.swipeUp()
        }
    }
}

struct DemoGalleryCapture {
    static let fallbackPath = "/tmp/codex-meter-gallery"

    let directories: [URL]

    init(environment: [String: String] = ProcessInfo.processInfo.environment) {
        var paths = [Self.fallbackPath]
        if let configured = environment["GALLERY_OUTPUT"], !configured.isEmpty {
            paths.insert(configured, at: 0)
        }
        directories = paths.map { URL(fileURLWithPath: $0, isDirectory: true) }
        for directory in directories {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    func save(_ name: String, test: XCTestCase) {
        let screenshot = XCUIScreen.main.screenshot()
        let data = screenshot.pngRepresentation
        for directory in directories {
            try? data.write(to: directory.appendingPathComponent("\(name).png"))
        }
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        test.add(attachment)
    }
}
