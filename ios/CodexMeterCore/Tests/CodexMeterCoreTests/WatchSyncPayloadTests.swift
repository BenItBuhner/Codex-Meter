import XCTest
@testable import CodexMeterCore

final class WatchSyncPayloadTests: XCTestCase {
    func testRoundTripApplicationContext() throws {
        let snapshot = SharedWidgetSnapshot(
            mode: .demo,
            fetchedAt: Date(timeIntervalSince1970: 2_000_000_000),
            planType: "plus",
            fiveHour: UsageWindow(usedPercent: 40, windowSeconds: 18_000),
            weekly: UsageWindow(usedPercent: 70, windowSeconds: 604_800),
            resetCreditsAvailable: 2,
            freshness: .fresh
        )
        let context = try WatchSyncPayload.applicationContext(for: snapshot)
        let decoded = try WatchSyncPayload.snapshot(fromApplicationContext: context)
        XCTAssertEqual(decoded.mode, .demo)
        XCTAssertEqual(decoded.fiveHour?.usedPercent, 40)
        XCTAssertEqual(decoded.resetCreditsAvailable, 2)
        XCTAssertEqual(decoded.planType, "plus")
    }

    func testMissingPayloadThrows() {
        XCTAssertThrowsError(try WatchSyncPayload.snapshot(fromApplicationContext: [:]))
    }
}
