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
        let data = try XCTUnwrap(context[WatchSyncPayload.contextKey] as? Data)
        let json = String(decoding: data, as: UTF8.self).lowercased()
        for forbidden in ["token", "email", "accountid", "keychain"] {
            XCTAssertFalse(json.contains(forbidden))
        }
    }

    func testMissingPayloadThrows() {
        XCTAssertThrowsError(try WatchSyncPayload.snapshot(fromApplicationContext: [:]))
    }

    func testEnvelopeCarriesExpiryAndRejectsFutureSchema() throws {
        let expiry = Date(timeIntervalSince1970: 2_000_100_000)
        var envelope = WatchSnapshotEnvelope(
            fiveHour: nil,
            weekly: nil,
            creditsAvailable: 1,
            nextCreditExpiry: expiry,
            planLabel: "Plus",
            mode: .live,
            fetchTime: Date(timeIntervalSince1970: 2_000_000_000),
            sourceTime: Date(timeIntervalSince1970: 2_000_000_001),
            freshness: .fresh
        )
        XCTAssertEqual(try WatchSyncPayload.decodeEnvelope(WatchSyncPayload.encode(envelope)).nextCreditExpiry, expiry)
        envelope.schemaVersion = 99
        XCTAssertThrowsError(try WatchSyncPayload.decodeEnvelope(WatchSyncPayload.encode(envelope)))
    }
}
