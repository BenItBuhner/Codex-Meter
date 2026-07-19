import XCTest
@testable import CodexMeterCore

final class SettingsTransferDocumentTests: XCTestCase {
    func testRoundTripContainsPreferencesAndNoCredentialFields() throws {
        let document = SettingsTransferDocument(
            exportedAt: Date(timeIntervalSince1970: 2_000_000_000),
            appearance: .init(mode: "dark"),
            refresh: .init(refreshOnLaunch: true, preferredMinutes: 30),
            usageWarnings: .init(estimatesEnabled: true, sensitivity: .balanced),
            notifications: .init(
                enabled: true,
                metric: "both",
                threshold: 25,
                creditIncrease: true,
                unexpectedRefill: true,
                creditExpiry: true,
                creditExpiryLeadMinutes: [1_440]
            ),
            liveActivity: .init(autoStartOnAcceleratedUsage: true)
        )
        let data = try document.encoded()
        XCTAssertEqual(try SettingsTransferDocument.decodeValidated(data), document)
        let json = String(decoding: data, as: UTF8.self).lowercased()
        for forbidden in ["token", "email", "accountid", "cachedusage", "keychain"] {
            XCTAssertFalse(json.contains(forbidden), "Export leaked forbidden field: \(forbidden)")
        }
    }

    func testRejectsFutureAndMalformedDocuments() throws {
        let future = SettingsTransferDocument(schemaVersion: 2, appearance: .init(mode: "system"))
        let raw = try JSONEncoder().encode(future)
        XCTAssertThrowsError(try SettingsTransferDocument.decodeValidated(raw))
        XCTAssertThrowsError(try SettingsTransferDocument.decodeValidated(Data("{".utf8)))
        XCTAssertThrowsError(try SettingsTransferDocument.decodeValidated(Data()))
    }
}
