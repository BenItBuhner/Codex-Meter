import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// Drives `AppModel` in demo mode with isolated stores: no network, no Keychain, and no
/// background-task registration.
@MainActor
final class ScheduledResetFlowTests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!
    private var temporaryURLs: [URL] = []

    override func setUp() {
        super.setUp()
        suiteName = "ScheduledResetFlowTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        for url in temporaryURLs {
            try? FileManager.default.removeItem(at: url)
        }
        super.tearDown()
    }

    func testStoreRoundTripsScheduleAndOutcomeAndStaysOutOfSettingsTransfer() throws {
        let store = ScheduledResetStore(defaults: defaults)
        XCTAssertNil(store.schedule)
        XCTAssertNil(store.outcome)

        let now = Date(timeIntervalSince1970: 1_900_000_000)
        let schedule = ScheduledReset(
            trigger: .threshold(window: .weekly, remainingPercent: 10),
            createdAt: now,
            lastCheckedAt: now.addingTimeInterval(60),
            failureCount: 1
        )
        store.schedule = schedule
        XCTAssertEqual(store.schedule, schedule)

        let outcome = ScheduledResetOutcome(
            trigger: schedule.trigger,
            kind: .fired(creditsRemaining: 1, windowsReset: 2),
            observedRemainingPercent: 9,
            at: now
        )
        store.outcome = outcome
        XCTAssertEqual(store.outcome, outcome)

        // The settings document carries AppSettings only; an armed schedule never leaves the device.
        let transfer = try JSONEncoder().encode(AppSettingsStore(defaults: defaults).settings)
        let json = try XCTUnwrap(String(data: transfer, encoding: .utf8))
        XCTAssertFalse(json.contains("scheduled"))

        store.clear()
        XCTAssertNil(store.schedule)
        XCTAssertNil(store.outcome)
    }

    func testThresholdScheduleWaitsThenFiresOnRefreshAndConsumesOneCredit() async throws {
        let (model, store) = try await makeDemoModel()
        await model.startIfNeeded()
        XCTAssertEqual(model.mode, .demo)
        XCTAssertEqual(model.availableResetCredits, 2)
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 62)

        await model.armScheduledReset(.threshold(window: .fiveHour, remainingPercent: 60))
        XCTAssertNotNil(model.scheduledReset)
        XCTAssertEqual(store.schedule, model.scheduledReset)
        XCTAssertEqual(model.availableResetCredits, 2, "62% remaining is above the 60% threshold")

        await model.refresh()
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 61)
        XCTAssertNotNil(model.scheduledReset)
        XCTAssertNotNil(model.scheduledReset?.lastCheckedAt)
        XCTAssertNil(model.scheduledResetOutcome)

        await model.refresh()
        XCTAssertNil(model.scheduledReset, "one-shot: the schedule disarms after firing")
        XCTAssertNil(store.schedule)
        let outcome = try XCTUnwrap(model.scheduledResetOutcome)
        XCTAssertEqual(outcome.kind, .fired(creditsRemaining: 1, windowsReset: 2))
        XCTAssertEqual(outcome.observedRemainingPercent, 60)
        XCTAssertEqual(store.outcome, outcome)
        XCTAssertEqual(model.availableResetCredits, 1)
        XCTAssertEqual(model.credits?.availableCount, 1)
        XCTAssertEqual(model.usage?.fiveHour?.usedPercent, 0, "the demo reset clears the window")
        XCTAssertNil(model.resetResultMessage, "the manual-reset banner is not the scheduled outcome")
        XCTAssertEqual(
            ScheduledResetCopy.outcomeMessage(outcome),
            "Scheduled reset used 1 credit — Your 5-hour limit reached 60% remaining. 1 reset credit left."
        )

        model.dismissScheduledResetOutcome()
        XCTAssertNil(model.scheduledResetOutcome)
        XCTAssertNil(store.outcome)
    }

    func testDueScheduleSkipsWhenTheLastCreditWasSpentManually() async throws {
        let (model, store) = try await makeDemoModel(fiveHourStep: 30)
        await model.startIfNeeded()

        await model.armScheduledReset(.threshold(window: .fiveHour, remainingPercent: 5))
        XCTAssertNotNil(model.scheduledReset)

        await model.consumeResetCredit()
        await model.consumeResetCredit()
        XCTAssertEqual(model.availableResetCredits, 0)
        XCTAssertNotNil(model.scheduledReset, "a manual reset leaves the schedule armed")
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 100)

        for expected in [70, 40, 10] {
            await model.refresh()
            XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, expected)
            XCTAssertNotNil(model.scheduledReset)
        }
        await model.refresh()
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 0)
        XCTAssertNil(model.scheduledReset)
        XCTAssertNil(store.schedule)
        XCTAssertEqual(model.scheduledResetOutcome?.kind, .skipped(.noCreditAvailable))
        XCTAssertEqual(model.availableResetCredits, 0)
    }

    func testDueDateTimeScheduleRefetchesStaleUsageBeforeDeciding() async throws {
        // The demo snapshot is stamped ten minutes ago, so a terminal decision must wait for
        // a fresh fetch. On the stale data the lowest remaining allowance is the weekly
        // window at 36%, which fails a 35% condition and would end the schedule as
        // skipped; the fresh fetch lands the weekly window at 35% and the reset fires.
        let (model, _) = try await makeDemoModel(referenceDate: Date().addingTimeInterval(-600))
        await model.startIfNeeded()
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 62)
        XCTAssertEqual(model.usage?.weekly?.remainingPercent, 36)

        await model.armScheduledReset(
            .dateTime(fireAt: Date().addingTimeInterval(-1), onlyIfRemainingAtMost: 35)
        )
        XCTAssertNil(model.scheduledReset)
        XCTAssertEqual(model.scheduledResetOutcome?.kind, .fired(creditsRemaining: 1, windowsReset: 2))
        XCTAssertEqual(model.availableResetCredits, 1)
        XCTAssertEqual(model.usage?.weekly?.usedPercent, 0)
    }

    func testDateTimeConditionNotMetSkipsAndCancelClearsAnArmedSchedule() async throws {
        let (model, store) = try await makeDemoModel()
        await model.startIfNeeded()

        await model.armScheduledReset(
            .dateTime(fireAt: Date().addingTimeInterval(-1), onlyIfRemainingAtMost: 10)
        )
        XCTAssertNil(model.scheduledReset)
        XCTAssertEqual(
            model.scheduledResetOutcome?.kind,
            .skipped(.conditionNotMet(remainingPercent: 36, requiredAtMost: 10))
        )
        XCTAssertEqual(model.availableResetCredits, 2, "a skip spends nothing")

        let fireAt = Date().addingTimeInterval(3_600)
        await model.armScheduledReset(.dateTime(fireAt: fireAt, onlyIfRemainingAtMost: nil))
        XCTAssertEqual(model.scheduledReset?.trigger, .dateTime(fireAt: fireAt, onlyIfRemainingAtMost: nil))
        XCTAssertNil(model.scheduledResetOutcome, "arming clears the previous outcome")
        XCTAssertEqual(model.previewScheduledResetDecision(.threshold(window: .fiveHour, remainingPercent: 5)), .wait)
        XCTAssertEqual(model.previewScheduledResetDecision(.threshold(window: .fiveHour, remainingPercent: 70)), .fire)
        XCTAssertFalse(model.scheduledResetCreditsExpireBefore(try XCTUnwrap(model.scheduledReset).trigger))
        XCTAssertTrue(
            model.scheduledResetCreditsExpireBefore(
                .dateTime(fireAt: Date().addingTimeInterval(400 * 86_400), onlyIfRemainingAtMost: nil)
            )
        )

        await model.cancelScheduledReset()
        XCTAssertNil(model.scheduledReset)
        XCTAssertNil(store.schedule)
        XCTAssertEqual(model.availableResetCredits, 2)
    }

    private func makeDemoModel(
        referenceDate: Date = Date(),
        fiveHourStep: Int = 1
    ) async throws -> (AppModel, ScheduledResetStore) {
        defaults.set(AppMode.demo.rawValue, forKey: "codex-meter.session-mode-v1")
        let cacheURL = temporaryFileURL("scheduled-reset-cache-\(UUID().uuidString).json")
        let widgetURL = temporaryFileURL("scheduled-reset-widget-\(UUID().uuidString).json")
        let historyURL = temporaryFileURL("scheduled-reset-history-\(UUID().uuidString).json")
        temporaryURLs += [cacheURL, widgetURL, historyURL]

        let appCache = AppCacheStore(fileURL: cacheURL)
        let demo = DemoCodexService(
            referenceDate: referenceDate,
            appCache: appCache,
            widgetCache: WidgetSnapshotCache(fileURL: widgetURL)
        )
        await demo.setFiveHourStep(fiveHourStep)
        let store = ScheduledResetStore(defaults: defaults)
        let model = AppModel(
            demoService: demo,
            cache: appCache,
            settingsStore: AppSettingsStore(defaults: defaults),
            notificationCoordinator: NotificationCoordinator(defaults: defaults),
            usageHistoryStore: UsageHistoryStore(fileURL: historyURL),
            defaults: defaults,
            scheduledResetStore: store,
            registersBackgroundRefresh: false
        )
        return (model, store)
    }
}
