import XCTest
@testable import CodexMeter

final class SettingsTransferTests: XCTestCase {
    func testRoundTripAllPortablePreferences() throws {
        var settings = AppSettings.defaults
        settings.appearance = .dark
        settings.refreshOnLaunch = false
        settings.refreshMinutes = 60
        settings.usagePaceEnabled = true
        settings.usagePaceSensitivity = .relaxed
        settings.notificationsEnabled = true
        settings.alertMetric = .fiveHour
        settings.alertThreshold = 50
        settings.creditIncreaseAlertsEnabled = false
        settings.unexpectedRefillAlertsEnabled = false
        settings.creditExpiryLeadMinutes = [60, 1_440]
        settings.liveMonitorAutoStartOnAcceleratedUsage = true

        let data = try SettingsTransfer.encode(SettingsTransfer.makeDocument(settings: settings))
        let applied = SettingsTransfer.apply(document: try SettingsTransfer.parse(data), to: .defaults)

        XCTAssertEqual(applied.appearance, .dark)
        XCTAssertFalse(applied.refreshOnLaunch)
        XCTAssertEqual(applied.refreshMinutes, 60)
        XCTAssertEqual(applied.usagePaceSensitivity, .relaxed)
        XCTAssertTrue(applied.notificationsEnabled)
        XCTAssertEqual(applied.alertMetric, .fiveHour)
        XCTAssertEqual(applied.creditExpiryLeadMinutes, [60, 1_440])
        XCTAssertTrue(applied.liveMonitorAutoStartOnAcceleratedUsage)
    }

    func testTransferCannotContainCredentialsOrAccountData() throws {
        let data = try SettingsTransfer.encode(SettingsTransfer.makeDocument(settings: .defaults))
        let json = String(decoding: data, as: UTF8.self).lowercased()
        for forbidden in ["access_token", "refreshtoken", "idtoken", "email", "accountid", "keychain", "cachedusage"] {
            XCTAssertFalse(json.contains(forbidden), "Leaked forbidden field \(forbidden)")
        }
    }

    func testFutureVersionDoesNotApply() throws {
        let future = #"{"schemaVersion":99,"exportedAt":"2033-05-18T03:33:20Z","appearance":{"mode":"dark"}}"#
        XCTAssertThrowsError(try SettingsTransfer.parse(Data(future.utf8)))
        XCTAssertEqual(AppSettings.defaults.appearance, .system)
    }
}
