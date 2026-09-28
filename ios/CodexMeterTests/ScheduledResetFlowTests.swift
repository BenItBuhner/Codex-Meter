import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// Drives `AppModel` in demo mode with isolated stores: no network, no Keychain, and no
/// background-task registration. Every test owns one fixture and removes it on the way out.
@MainActor
final class ScheduledResetFlowTests: XCTestCase {
    func testStoreRoundTripsScheduleAndOutcomeAndStaysOutOfSettingsTransfer() throws {
        let suiteName = "ScheduledResetFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
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
        XCTAssertFalse(json.lowercased().contains("scheduled"))

        store.clear()
        XCTAssertNil(store.schedule)
        XCTAssertNil(store.outcome)
    }

    func testThresholdScheduleWaitsThenFiresOnRefreshAndConsumesOneCredit() async throws {
        let fixture = try await makeDemoFixture()
        defer { fixture.cleanUp() }
        let model = fixture.model
        await model.startIfNeeded()
        XCTAssertEqual(model.mode, .demo)
        XCTAssertEqual(model.availableResetCredits, 2)
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 62)

        let trigger = ScheduledResetTrigger.threshold(window: .fiveHour, remainingPercent: 60)
        await model.armScheduledReset(trigger)
        let armed = try XCTUnwrap(model.scheduledReset)
        XCTAssertEqual(armed.trigger, trigger)
        XCTAssertEqual(fixture.store.schedule?.id, armed.id)
        XCTAssertEqual(fixture.store.schedule?.trigger, trigger)
        XCTAssertEqual(model.availableResetCredits, 2, "62% remaining is above the 60% threshold")

        await model.refresh()
        XCTAssertEqual(model.usage?.fiveHour?.remainingPercent, 61)
        XCTAssertEqual(model.scheduledReset?.id, armed.id)
        XCTAssertNotNil(model.scheduledReset?.lastCheckedAt)
        XCTAssertNil(model.scheduledResetOutcome)

        await model.refresh()
        XCTAssertNil(model.scheduledReset, "one-shot: the schedule disarms after firing")
        XCTAssertNil(fixture.store.schedule)
        let outcome = try XCTUnwrap(model.scheduledResetOutcome)
        XCTAssertEqual(outcome.trigger, trigger)
        XCTAssertEqual(outcome.kind, .fired(creditsRemaining: 1, windowsReset: 2))
        XCTAssertEqual(outcome.observedRemainingPercent, 60)
        XCTAssertEqual(fixture.store.outcome?.kind, outcome.kind)
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
        XCTAssertNil(fixture.store.outcome)
    }

    func testDueScheduleSkipsWhenTheLastCreditWasSpentManually() async throws {
        let fixture = try await makeDemoFixture(fiveHourStep: 30)
        defer { fixture.cleanUp() }
        let model = fixture.model
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
        XCTAssertNil(fixture.store.schedule)
        XCTAssertEqual(model.scheduledResetOutcome?.kind, .skipped(.noCreditAvailable))
        XCTAssertEqual(fixture.store.outcome?.kind, .skipped(.noCreditAvailable))
        XCTAssertEqual(model.availableResetCredits, 0)
    }

    func testDueDateTimeScheduleRefetchesStaleUsageBeforeDeciding() async throws {
        // The demo snapshot is stamped ten minutes ago, so a terminal decision must wait for
        // a fresh fetch. On the stale data the lowest remaining allowance is the weekly
        // window at 36%, which fails a 35% condition and would end the schedule as
        // skipped; the fresh fetch lands the weekly window at 35% and the reset fires.
        let fixture = try await makeDemoFixture(referenceDate: Date().addingTimeInterval(-600))
        defer { fixture.cleanUp() }
        let model = fixture.model
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
        let fixture = try await makeDemoFixture()
        defer { fixture.cleanUp() }
        let model = fixture.model
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
        let trigger = ScheduledResetTrigger.dateTime(fireAt: fireAt, onlyIfRemainingAtMost: nil)
        await model.armScheduledReset(trigger)
        XCTAssertEqual(model.scheduledReset?.trigger, trigger)
        XCTAssertNil(model.scheduledResetOutcome, "arming clears the previous outcome")
        XCTAssertEqual(model.previewScheduledResetDecision(.threshold(window: .fiveHour, remainingPercent: 5)), .wait)
        XCTAssertEqual(model.previewScheduledResetDecision(.threshold(window: .fiveHour, remainingPercent: 70)), .fire)
        XCTAssertFalse(model.scheduledResetCreditsExpireBefore(trigger))
        XCTAssertTrue(
            model.scheduledResetCreditsExpireBefore(
                .dateTime(fireAt: Date().addingTimeInterval(400 * 86_400), onlyIfRemainingAtMost: nil)
            )
        )

        await model.cancelScheduledReset()
        XCTAssertNil(model.scheduledReset)
        XCTAssertNil(fixture.store.schedule)
        XCTAssertEqual(model.availableResetCredits, 2)
    }

    private struct Fixture {
        let model: AppModel
        let store: ScheduledResetStore
        let suiteName: String
        let temporaryURLs: [URL]

        @MainActor
        func cleanUp() {
            UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
            for url in temporaryURLs {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    private func makeDemoFixture(
        referenceDate: Date = Date(),
        fiveHourStep: Int = 1
    ) async throws -> Fixture {
        let suiteName = "ScheduledResetFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.set(AppMode.demo.rawValue, forKey: "codex-meter.session-mode-v1")

        let token = UUID().uuidString
        let cacheURL = temporaryFileURL("scheduled-reset-cache-\(token).json")
        let widgetURL = temporaryFileURL("scheduled-reset-widget-\(token).json")
        let historyURL = temporaryFileURL("scheduled-reset-history-\(token).json")

        let appCache = AppCacheStore(fileURL: cacheURL)
        let demo = DemoCodexService(
            referenceDate: referenceDate,
            appCache: appCache,
            widgetCache: WidgetSnapshotCache(fileURL: widgetURL)
        )
        await demo.setFiveHourStep(fiveHourStep)
        let store = ScheduledResetStore(defaults: defaults)
        // The coordinator is an actor: a UserDefaults that has passed through main-actor
        // code cannot be sent into it, and nothing here asserts on notification state, so
        // it takes the same default the app does.
        let model = AppModel(
            demoService: demo,
            cache: appCache,
            settingsStore: AppSettingsStore(defaults: defaults),
            notificationCoordinator: NotificationCoordinator(),
            usageHistoryStore: UsageHistoryStore(fileURL: historyURL),
            defaults: defaults,
            scheduledResetStore: store,
            registersBackgroundRefresh: false
        )
        return Fixture(
            model: model,
            store: store,
            suiteName: suiteName,
            temporaryURLs: [cacheURL, widgetURL, historyURL]
        )
    }
}
