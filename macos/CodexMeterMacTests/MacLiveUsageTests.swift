import AppKit
import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// The menu bar's rules, shared with the Windows tray and the Android live notification: which
/// provider and which window it shows, and how a used-up window reads.
final class MacLiveUsageTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_900_000_000)

    private func snapshot(fiveHour: Int?, weekly: Int?, monthly: Int? = nil, resetIn: TimeInterval = 7_200) -> UsageSnapshot {
        func window(_ used: Int?, seconds: Int64) -> UsageWindow? {
            used.map { UsageWindow(usedPercent: $0, windowSeconds: seconds, resetAt: now.addingTimeInterval(resetIn)) }
        }
        return UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: window(fiveHour, seconds: 18_000),
            weekly: window(weekly, seconds: 604_800),
            monthly: window(monthly, seconds: 2_592_000),
            fetchedAt: now
        )
    }

    func testTheAppInFrontWinsThenTheChosenProviderThenTheFirst() {
        let candidates: [UsageProvider] = [.codex, .claudeCode, .cursor]
        XCTAssertEqual(MacLiveUsage.chooseProvider(chosen: .cursor, frontApp: .claudeCode, candidates: candidates), .claudeCode)
        XCTAssertEqual(MacLiveUsage.chooseProvider(chosen: .cursor, frontApp: nil, candidates: candidates), .cursor)
        XCTAssertEqual(MacLiveUsage.chooseProvider(chosen: nil, frontApp: nil, candidates: candidates), .codex)
        // An app or choice whose provider has no usage here falls through to the next rule.
        XCTAssertEqual(MacLiveUsage.chooseProvider(chosen: .cursor, frontApp: .grok, candidates: candidates), .cursor)
        XCTAssertEqual(MacLiveUsage.chooseProvider(chosen: .grok, frontApp: nil, candidates: candidates), .codex)
        XCTAssertNil(MacLiveUsage.chooseProvider(chosen: .codex, frontApp: .codex, candidates: []))
    }

    func testAutoShowsWhicheverWindowHasLessLeft() throws {
        let weeklyTighter = try XCTUnwrap(MacLiveUsage.chooseMeter(snapshot(fiveHour: 20, weekly: 70), window: .auto))
        XCTAssertEqual(weeklyTighter.id, "weekly")
        XCTAssertEqual(weeklyTighter.remainingPercent, 30)
        let fiveTighter = try XCTUnwrap(MacLiveUsage.chooseMeter(snapshot(fiveHour: 90, weekly: 70), window: .auto))
        XCTAssertEqual(fiveTighter.id, "five-hour")
        // A tie keeps the 5-hour window, the one that resets first.
        XCTAssertEqual(MacLiveUsage.chooseMeter(snapshot(fiveHour: 50, weekly: 50), window: .auto)?.id, "five-hour")
    }

    func testAChosenWindowFallsBackWhenTheProviderHasNoSuchWindow() {
        XCTAssertEqual(MacLiveUsage.chooseMeter(snapshot(fiveHour: 10, weekly: 90), window: .fiveHour)?.id, "five-hour")
        XCTAssertEqual(MacLiveUsage.chooseMeter(snapshot(fiveHour: 10, weekly: 90), window: .weekly)?.id, "weekly")
        // Codex Free reports a monthly window instead of weekly.
        XCTAssertEqual(MacLiveUsage.chooseMeter(snapshot(fiveHour: nil, weekly: nil, monthly: 40), window: .weekly)?.title, "Monthly")
        XCTAssertEqual(MacLiveUsage.chooseMeter(snapshot(fiveHour: nil, weekly: nil, monthly: 40), window: .fiveHour)?.id, "monthly")
        XCTAssertEqual(MacLiveUsage.chooseMeter(snapshot(fiveHour: 10, weekly: nil), window: .weekly)?.id, "five-hour")
        XCTAssertNil(MacLiveUsage.chooseMeter(snapshot(fiveHour: nil, weekly: nil), window: .auto))
        XCTAssertNil(MacLiveUsage.chooseMeter(nil, window: .auto))
    }

    func testAUsedUpWindowShowsTimeUntilItResetsInsteadOfZero() throws {
        let meter = try XCTUnwrap(MacLiveUsage.chooseMeter(snapshot(fiveHour: 100, weekly: 40, resetIn: 2 * 3_600 + 14 * 60), window: .fiveHour))
        let reading = MacLiveReading(provider: .codex, meter: meter, fetchedAt: now)
        XCTAssertTrue(reading.isExhausted)
        XCTAssertEqual(reading.compactText(now: now), "2h 14m")
        XCTAssertEqual(reading.headline(now: now), "Codex · 5-hour resets in 2h 14m")
        // Once the reset time passes without a new reading, it says so plainly.
        let later = now.addingTimeInterval(3 * 3_600)
        XCTAssertNil(reading.untilReset(now: later))
        XCTAssertEqual(reading.compactText(now: later), "0%")
        XCTAssertEqual(reading.headline(now: later), "Codex · 5-hour used up")
    }

    func testAHealthyWindowShowsWhatIsLeft() throws {
        let meter = try XCTUnwrap(MacLiveUsage.chooseMeter(snapshot(fiveHour: 38, weekly: 10), window: .fiveHour))
        let reading = MacLiveReading(provider: .claudeCode, meter: meter, fetchedAt: now)
        XCTAssertFalse(reading.isExhausted)
        XCTAssertEqual(reading.compactText(now: now), "62%")
        XCTAssertEqual(reading.headline(now: now), "Claude Code · 5-hour 62% left")
        XCTAssertEqual(reading.fraction, 0.62, accuracy: 0.0001)
    }

    func testShortDurationsRoundUpToTheNextMinute() {
        XCTAssertEqual(MacLiveUsage.shortDuration(0), "1m")
        XCTAssertEqual(MacLiveUsage.shortDuration(-30), "1m")
        XCTAssertEqual(MacLiveUsage.shortDuration(61), "2m")
        XCTAssertEqual(MacLiveUsage.shortDuration(45 * 60), "45m")
        XCTAssertEqual(MacLiveUsage.shortDuration(3 * 3_600), "3h")
        XCTAssertEqual(MacLiveUsage.shortDuration(3 * 3_600 + 12 * 60), "3h 12m")
        XCTAssertEqual(MacLiveUsage.shortDuration(2 * 86_400), "2d")
        XCTAssertEqual(MacLiveUsage.shortDuration(2 * 86_400 + 4 * 3_600 + 59), "2d 4h")
    }

    func testTheRingTakesTheMenuBarColorUntilItRunsLow() {
        XCTAssertTrue(MacMenuBarRing.image(fraction: 0.6, level: .healthy, exhausted: false).isTemplate)
        XCTAssertTrue(MacMenuBarRing.image(fraction: nil, level: nil, exhausted: false).isTemplate)
        XCTAssertFalse(MacMenuBarRing.image(fraction: 0.2, level: .low, exhausted: false).isTemplate)
        XCTAssertFalse(MacMenuBarRing.image(fraction: 0.05, level: .critical, exhausted: false).isTemplate)
        XCTAssertFalse(MacMenuBarRing.image(fraction: 0, level: .critical, exhausted: true).isTemplate)
        XCTAssertEqual(MacMenuBarRing.image(fraction: 0.5, level: .healthy, exhausted: false, size: 18).size, NSSize(width: 18, height: 18))
    }

    func testMenuBarStyleAndWindowChoicesKeepTheirStoredValues() {
        // Stored in UserDefaults by raw value; renaming one would reset everyone's choice.
        XCTAssertEqual(MacLiveWindow.allCases.map(\.rawValue), ["auto", "five_hour", "weekly"])
        XCTAssertEqual(MacMenuBarStyle.allCases.map(\.rawValue), ["ring_text", "ring", "text"])
        XCTAssertTrue(MacMenuBarStyle.ringAndText.showsRing && MacMenuBarStyle.ringAndText.showsText)
        XCTAssertFalse(MacMenuBarStyle.ring.showsText)
        XCTAssertFalse(MacMenuBarStyle.text.showsRing)
    }

    func testHidingThePercentageEarlierCarriesOverAsRingOnly() {
        let defaults = UserDefaults.standard
        let key = MacPreferences.menuBarShowsPercentKey
        let saved = defaults.object(forKey: key)
        defer { defaults.set(saved, forKey: key) }

        defaults.removeObject(forKey: key)
        XCTAssertEqual(MacPreferences.defaultMenuBarStyle, MacMenuBarStyle.ringAndText.rawValue)
        defaults.set(false, forKey: key)
        XCTAssertEqual(MacPreferences.defaultMenuBarStyle, MacMenuBarStyle.ring.rawValue)
        defaults.set(true, forKey: key)
        XCTAssertEqual(MacPreferences.defaultMenuBarStyle, MacMenuBarStyle.ringAndText.rawValue)
    }
}
