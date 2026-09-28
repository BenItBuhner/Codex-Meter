import XCTest

extension DemoGalleryTests {
    /// Scheduled reset in demo mode, driven from the dashboard card's own `Schedule reset`
    /// button: the card with both actions side by side (light and dark), a 5-hour threshold
    /// armed after its confirmation, the schedule firing on refresh and spending one credit,
    /// a date/time schedule armed and cancelled, a due schedule skipping once the last credit
    /// was spent by hand, and the action pair stacked at the largest accessibility size.
    /// Stills start at 30 so the range cannot collide with legs added by other open PRs.
    /// Local notifications do not render reliably in the simulator, so the in-app state
    /// carries every outcome here.
    ///
    /// `-ui-testing-demo-burn-step 30` makes each demo refresh add 30% of 5-hour usage
    /// (the shipped demo adds 1%), so a threshold is crossed in two taps instead of sixty.
    func recordScheduledResetLeg(app: XCUIApplication) {
        app.terminate()
        app.launchArguments = [
            "-ui-testing-demo",
            "-ui-testing-reset-settings",
            "-ui-testing-demo-burn-step", "30"
        ]
        app.launch()
        guard app.navigationBars["Codex Meter"].waitForExistence(timeout: 8),
              app.staticTexts["5-hour"].waitForExistence(timeout: 8) else {
            XCTFail("The demo dashboard did not appear for the scheduled-reset leg")
            return
        }
        UITestSupport.settle(0.8)

        // The card with Use 1 reset and Schedule reset side by side, light then dark.
        guard showResetCard(in: app, expecting: app.buttons["resetCredits.schedule"]) else {
            XCTFail("The reset-credits card did not show its Schedule reset action")
            return
        }
        capture("30-reset-credits-card-light")
        guard setAppearance("Dark", in: app),
              showResetCard(in: app, expecting: app.buttons["resetCredits.schedule"]) else {
            XCTFail("The reset-credits card was not reached in dark appearance")
            return
        }
        capture("31-reset-credits-card-dark")
        guard setAppearance("System", in: app) else { return }

        // Threshold schedule from the card button: options, confirmation, armed card.
        guard openScheduleSheet(in: app) else { return }
        UITestSupport.settle(0.8)
        capture("32-scheduled-reset-options")

        guard confirmSchedule(in: app, capturing: "33-scheduled-reset-confirm") else { return }
        guard showResetCard(in: app, expecting: app.staticTexts["Scheduled · when 5-hour reaches 5%"]) else {
            XCTFail("The dashboard card did not show the armed threshold schedule")
            return
        }
        capture("34-scheduled-reset-armed")

        // 62% remaining → 32% → 2%: the second refresh crosses 5% and spends one credit.
        refreshDemo(app)
        UITestSupport.settle(0.6)
        refreshDemo(app)
        guard app.staticTexts["1 reset available"].waitForExistence(timeout: 5),
              showResetCard(in: app, expecting: app.staticTexts["scheduledReset.outcome"]) else {
            XCTFail("The threshold schedule did not fire after two demo refreshes")
            return
        }
        capture("35-scheduled-reset-fired")

        // The reset screen carries the same outcome; the sheet opens at its medium detent,
        // so drag it up by its navigation bar to bring the Scheduled reset card into view.
        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app)
        UITestSupport.tap(app.buttons["Use 1 reset"])
        guard app.navigationBars["Codex reset"].waitForExistence(timeout: 5) else {
            XCTFail("The reset screen did not open after the scheduled reset fired")
            return
        }
        UITestSupport.settle(0.5)
        app.navigationBars["Codex reset"].swipeUp()
        UITestSupport.settle(0.8)
        capture("36-reset-screen-after-fire")
        UITestSupport.tap(app.buttons["Close"])
        guard app.navigationBars["Codex Meter"].waitForExistence(timeout: 5) else { return }

        // Date and time schedule, then Cancel (no confirmation).
        guard openScheduleSheet(in: app) else { return }
        UITestSupport.tap(app.segmentedControls.buttons["Date and time"])
        guard app.switches["Only if remaining is at or below"].waitForExistence(timeout: 5) else {
            XCTFail("The date and time options did not appear")
            return
        }
        UITestSupport.settle(0.8)
        capture("37-scheduled-reset-date-options")
        guard confirmSchedule(in: app, capturing: "38-scheduled-reset-date-confirm") else { return }
        let armedDateLine = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'Scheduled · '")
        ).firstMatch
        guard showResetCard(in: app, expecting: armedDateLine) else {
            XCTFail("The dashboard card did not show the armed date and time schedule")
            return
        }
        capture("39-scheduled-reset-date-armed")
        UITestSupport.tap(app.buttons["resetCredits.cancelSchedule"])
        guard app.buttons["resetCredits.schedule"].waitForExistence(timeout: 5) else {
            XCTFail("Cancel did not disarm the date and time schedule")
            return
        }
        UITestSupport.settle(0.6)

        // Skipped outcome: arm again, spend the last credit by hand, then refresh until due.
        guard openScheduleSheet(in: app),
              confirmSchedule(in: app, capturing: nil),
              app.staticTexts["Scheduled · when 5-hour reaches 5%"].waitForExistence(timeout: 5) else {
            XCTFail("The second threshold schedule was not armed")
            return
        }
        UITestSupport.scrollDashboard(untilVisible: app.buttons["Use 1 reset"], in: app)
        UITestSupport.tap(app.buttons["Use 1 reset"])
        guard app.navigationBars["Codex reset"].waitForExistence(timeout: 5) else {
            XCTFail("The reset screen did not open for the manual reset")
            return
        }
        UITestSupport.tap(app.buttons["Use reset"])
        let manualConfirm = app.alerts["Use one Codex reset?"]
        guard manualConfirm.waitForExistence(timeout: 5) else {
            XCTFail("The manual reset confirmation did not appear")
            return
        }
        UITestSupport.tap(manualConfirm.buttons["Use reset"])
        guard app.buttons["Done"].waitForExistence(timeout: 8) else {
            XCTFail("The manual demo reset did not complete")
            return
        }
        UITestSupport.settle(0.6)
        UITestSupport.tap(app.buttons["Close"])
        guard app.navigationBars["Codex Meter"].waitForExistence(timeout: 5),
              showResetCard(in: app, expecting: app.buttons["No resets available"]) else {
            XCTFail("The dashboard card did not show the armed schedule without a credit")
            return
        }
        capture("40-scheduled-reset-armed-no-credit")

        // 100% remaining → 70% → 40% → 10% → 0%: due on the fourth refresh, with no credit left.
        for _ in 0..<4 {
            refreshDemo(app)
        }
        let outcome = app.staticTexts["scheduledReset.outcome"]
        guard outcome.waitForExistence(timeout: 5),
              outcome.label.hasPrefix("Scheduled reset skipped"),
              showResetCard(in: app, expecting: outcome) else {
            XCTFail("The due schedule did not report a skipped outcome")
            return
        }
        capture("41-scheduled-reset-skipped")

        // Largest accessibility text size: the two actions no longer fit beside each
        // other and stack. The card is the last one on the page, so scroll to the end
        // blind and ask the accessibility tree once.
        app.terminate()
        app.launchArguments = [
            "-ui-testing-demo",
            "-ui-testing-reset-settings",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        guard app.navigationBars["Codex Meter"].waitForExistence(timeout: 8),
              app.staticTexts["5-hour"].waitForExistence(timeout: 8) else {
            XCTFail("The demo dashboard did not appear at the AX5 text size")
            return
        }
        UITestSupport.settle(0.8)
        scrollDashboardToEnd(in: app)
        UITestSupport.settle(0.8)
        if app.buttons["resetCredits.schedule"].waitForExistence(timeout: 5),
           UITestSupport.isFullyVisible(app.buttons["resetCredits.schedule"], in: app) {
            capture("42-reset-credits-card-ax5")
        } else {
            XCTFail("The reset-credits card was not reached at the AX5 text size")
        }
        UITestSupport.settle(0.8)
    }

    private func openScheduleSheet(in app: XCUIApplication) -> Bool {
        UITestSupport.scrollDashboard(untilVisible: app.buttons["resetCredits.schedule"], in: app)
        guard UITestSupport.tap(app.buttons["resetCredits.schedule"]),
              app.navigationBars["Scheduled reset"].waitForExistence(timeout: 5) else {
            XCTFail("The Scheduled reset sheet did not open from the card button")
            return false
        }
        return true
    }

    /// Taps Schedule reset, optionally captures the confirmation, and confirms it.
    private func confirmSchedule(in app: XCUIApplication, capturing still: String?) -> Bool {
        UITestSupport.tap(app.buttons["scheduledReset.arm"])
        let confirmation = app.alerts["Schedule a Codex reset?"]
        guard confirmation.waitForExistence(timeout: 5) else {
            XCTFail("The schedule confirmation did not appear")
            return false
        }
        if let still {
            UITestSupport.settle(0.6)
            capture(still)
        }
        UITestSupport.tap(confirmation.buttons["Schedule reset"])
        return app.navigationBars["Codex Meter"].waitForExistence(timeout: 5)
    }

    /// Waits for `element`, scrolls the reset-credits card into view, and settles.
    private func showResetCard(in app: XCUIApplication, expecting element: XCUIElement) -> Bool {
        guard element.waitForExistence(timeout: 5) else { return false }
        UITestSupport.scrollDashboard(untilVisible: element, in: app)
        UITestSupport.settle(0.8)
        return true
    }

    /// Switches the appearance segment in Settings and returns to the dashboard.
    private func setAppearance(_ name: String, in app: XCUIApplication) -> Bool {
        UITestSupport.tap(app.buttons["Settings"])
        guard app.navigationBars["Settings"].waitForExistence(timeout: 5),
              UITestSupport.tap(app.segmentedControls.buttons[name]) else {
            XCTFail("The \(name) appearance could not be selected")
            return false
        }
        UITestSupport.settle(0.6)
        UITestSupport.tap(app.buttons["Done"])
        guard app.navigationBars["Codex Meter"].waitForExistence(timeout: 5) else {
            XCTFail("Settings did not dismiss after choosing \(name) appearance")
            return false
        }
        UITestSupport.settle(0.6)
        return true
    }

    private func refreshDemo(_ app: XCUIApplication) {
        UITestSupport.tap(app.buttons["Refresh usage"])
        _ = app.buttons["Refresh usage"].waitForExistence(timeout: 5)
        UITestSupport.settle(0.4)
    }

    /// Scrolls to the end of the dashboard without asking where it is: a momentum swipe
    /// alternates with a long drag from a different start point so a stroke the history
    /// chart swallows is never repeated from the same spot, and the scroll simply clamps
    /// at the last card.
    private func scrollDashboardToEnd(in app: XCUIApplication, passes: Int = 8) {
        for pass in 0..<passes {
            app.swipeUp()
            let anchor: CGFloat = pass.isMultiple(of: 2) ? 0.92 : 0.76
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: anchor))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: anchor - 0.6))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
    }
}
