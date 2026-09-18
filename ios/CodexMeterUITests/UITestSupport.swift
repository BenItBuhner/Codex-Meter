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
    ///
    /// Visibility is judged from the element frame rather than `isHittable`:
    /// resolving hit points for a card clipped at the scroll edge can stall
    /// XCUITest for minutes before it throws, whereas a frame query is cheap.
    static func scrollDashboard(
        untilVisible element: XCUIElement,
        in app: XCUIApplication,
        maxAttempts: Int = 10
    ) {
        let target = element.firstMatch
        let anchors: [CGFloat] = [0.85, 0.45]
        // Stop as soon as the target is in view: a `where` clause would keep
        // re-querying the accessibility tree once per remaining attempt, and on a
        // starved runner each of those snapshots is a chance to time out.
        for attempt in 0..<maxAttempts {
            if isFullyVisible(target, in: app) { return }
            let dy = anchors[attempt % anchors.count]
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: dy))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: dy - 0.35))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
    }

    /// One long drag down the dashboard from `anchor`, covering 60% of the window.
    static func dragDashboard(in app: XCUIApplication, from anchor: CGFloat) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: anchor))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: anchor - 0.6))
        start.press(forDuration: 0.05, thenDragTo: end)
    }

    /// Scrolls the dashboard to its end without asking where it is: at
    /// accessibility text sizes the page runs to several thousand points, and on
    /// a starved runner every hierarchy snapshot along the way is a chance to
    /// time out, while the scroll simply clamps at the last card. A momentum
    /// swipe alternates with a long drag from a different start point so a
    /// stroke the history chart swallows is never repeated from the same spot.
    static func scrollDashboardToBottom(in app: XCUIApplication, passes: Int = 8) {
        for pass in 0..<passes {
            app.swipeUp()
            dragDashboard(in: app, from: pass.isMultiple(of: 2) ? 0.92 : 0.76)
        }
    }

    /// True when the element exists and sits inside the window, clear of the
    /// navigation bar at the top and of the bottom edge.
    static func isFullyVisible(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        guard !frame.isEmpty else { return false }
        let window = app.frame
        let visibleArea = CGRect(
            x: window.minX,
            y: window.minY + 120,
            width: window.width,
            height: window.height - 120 - 60
        )
        return visibleArea.contains(frame)
    }

    static func scrollForm(
        untilExists element: XCUIElement,
        in app: XCUIApplication,
        maxAttempts: Int = 8
    ) {
        let target = element.firstMatch
        for _ in 0..<maxAttempts {
            if target.exists { return }
            app.swipeUp()
        }
    }
}

/// Writes the gallery stills and start/finish markers to a fixed staging
/// directory on the host that ci/record-demo-gallery.sh clears, polls, and
/// copies from. The simulator shares the host file system, so a plain path is
/// the one channel that works without relying on test-runner environment
/// variables reaching the UI test process.
struct DemoGalleryCapture {
    static let stagingDirectory = URL(fileURLWithPath: "/tmp/codex-meter-gallery", isDirectory: true)

    init() {
        try? FileManager.default.createDirectory(at: Self.stagingDirectory, withIntermediateDirectories: true)
    }

    func save(_ name: String, test: XCTestCase) {
        let screenshot = XCUIScreen.main.screenshot()
        try? screenshot.pngRepresentation.write(to: Self.stagingDirectory.appendingPathComponent("\(name).png"))
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        test.add(attachment)
    }

    /// Drops an empty `.name` marker file that the recording script polls for.
    func mark(_ name: String) {
        _ = FileManager.default.createFile(
            atPath: Self.stagingDirectory.appendingPathComponent(".\(name)").path,
            contents: nil
        )
    }
}
