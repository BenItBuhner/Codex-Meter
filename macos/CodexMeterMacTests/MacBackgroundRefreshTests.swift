import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// The Mac's in-app refresh timer: when it fires next, given the preferred interval and the next reset.
final class MacBackgroundRefreshTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_900_000_000)

    func testItFiresAtTheEarlierOfTheIntervalAndTheNextReset() {
        XCTAssertEqual(BackgroundRefreshCoordinator.fireDate(preferredMinutes: 30, nextReset: nil, now: now), now.addingTimeInterval(30 * 60))
        XCTAssertEqual(BackgroundRefreshCoordinator.fireDate(preferredMinutes: 30, nextReset: now.addingTimeInterval(600), now: now), now.addingTimeInterval(600))
        XCTAssertEqual(BackgroundRefreshCoordinator.fireDate(preferredMinutes: 15, nextReset: now.addingTimeInterval(7_200), now: now), now.addingTimeInterval(15 * 60))
    }

    func testAResetThatAlreadyPassedOrIsSecondsAwayNeverMeansABusyLoop() {
        XCTAssertEqual(BackgroundRefreshCoordinator.fireDate(preferredMinutes: 30, nextReset: now.addingTimeInterval(-60), now: now), now.addingTimeInterval(30 * 60))
        XCTAssertEqual(BackgroundRefreshCoordinator.fireDate(preferredMinutes: 30, nextReset: now.addingTimeInterval(5), now: now), now.addingTimeInterval(BackgroundRefreshCoordinator.minimumDelay))
        XCTAssertEqual(BackgroundRefreshCoordinator.fireDate(preferredMinutes: 30, nextReset: now, now: now), now.addingTimeInterval(30 * 60))
    }

    @MainActor
    func testScheduleUsesAnAllowedIntervalAndCancelClearsIt() throws {
        let coordinator = BackgroundRefreshCoordinator {
            BackgroundRefreshOutcome(preferredMinutes: 30, nextReset: nil)
        }
        XCTAssertTrue(coordinator.register())
        XCTAssertNil(coordinator.nextRunDate)
        // 7 minutes isn't a choice in Settings, so it falls back to 30.
        try coordinator.schedule(preferredMinutes: 7, nextReset: nil, now: now)
        XCTAssertEqual(coordinator.nextRunDate, now.addingTimeInterval(30 * 60))
        try coordinator.schedule(preferredMinutes: 30, nextReset: now.addingTimeInterval(900), now: now)
        XCTAssertEqual(coordinator.nextRunDate, now.addingTimeInterval(900))
        coordinator.cancel()
        XCTAssertNil(coordinator.nextRunDate)
    }
}
