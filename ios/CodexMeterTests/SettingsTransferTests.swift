import XCTest
@testable import CodexMeter

final class SettingsTransferTests: XCTestCase {
    func testRoundTripAppAndNotificationSettings() throws {
        var settings = AppSettings.defaults
        settings.appearance = .dark
        settings.refreshOnLaunch = false
        settings.refreshMinutes = 60
        settings.notificationsEnabled = true
        settings.alertMetric = .fiveHour
        settings.alertThreshold = 50
        settings.creditIncreaseAlertsEnabled = false
        settings.unexpectedRefillAlertsEnabled = false
        settings.creditExpiryRemindersEnabled = true
        settings.creditExpiryLeadMinutes = [60, 1_440]

        let document = SettingsTransfer.makeDocument(
            settings: settings,
            tokens: nil,
            options: .init(includeAppSettings: true, includeNotifications: true, includeAuthentication: false)
        )
        let data = try SettingsTransfer.encode(document)
        let parsed = try SettingsTransfer.parse(data)
        let applied = try SettingsTransfer.apply(
            document: parsed,
            to: .defaults,
            options: .init(importAppSettings: true, importNotifications: true, importAuthentication: false)
        )

        XCTAssertEqual(applied.settings.appearance, .dark)
        XCTAssertEqual(applied.settings.refreshOnLaunch, false)
        XCTAssertEqual(applied.settings.refreshMinutes, 60)
        XCTAssertEqual(applied.settings.notificationsEnabled, true)
        XCTAssertEqual(applied.settings.alertMetric, .fiveHour)
        XCTAssertEqual(applied.settings.alertThreshold, 50)
        XCTAssertEqual(applied.settings.creditIncreaseAlertsEnabled, false)
        XCTAssertEqual(applied.settings.unexpectedRefillAlertsEnabled, false)
        XCTAssertEqual(applied.settings.creditExpiryLeadMinutes, [60, 1_440])
        XCTAssertNil(applied.tokens)
        XCTAssertNil(parsed.securityWarning)
    }

    func testAuthenticationExportIncludesSecurityWarning() throws {
        let tokens = AuthTokens(
            accessToken: "access",
            refreshToken: "refresh",
            idToken: "id",
            expiresAt: Date(timeIntervalSince1970: 2_000_000_000),
            accountID: "acct",
            email: "user@example.com"
        )
        let document = SettingsTransfer.makeDocument(
            settings: .defaults,
            tokens: tokens,
            options: .init(includeAppSettings: false, includeNotifications: false, includeAuthentication: true)
        )
        XCTAssertEqual(document.securityWarning, SettingsTransfer.securityWarning)
        let data = try SettingsTransfer.encode(document)
        let parsed = try SettingsTransfer.parse(data)
        let applied = try SettingsTransfer.apply(
            document: parsed,
            to: .defaults,
            options: .init(importAppSettings: false, importNotifications: false, importAuthentication: true)
        )
        XCTAssertEqual(applied.tokens?.accessToken, "access")
        XCTAssertEqual(applied.tokens?.email, "user@example.com")
    }

    func testRejectsInvalidFormat() {
        let junk = Data(#"{"format":"nope","version":1,"sections":{}}"#.utf8)
        XCTAssertThrowsError(try SettingsTransfer.parse(junk))
    }

    func testRejectsEmptySelection() throws {
        let document = SettingsTransfer.makeDocument(
            settings: .defaults,
            tokens: nil,
            options: .init(includeAppSettings: true, includeNotifications: false, includeAuthentication: false)
        )
        XCTAssertThrowsError(
            try SettingsTransfer.apply(
                document: document,
                to: .defaults,
                options: .init(
                    importAppSettings: false,
                    importNotifications: false,
                    importAuthentication: false
                )
            )
        )
    }
}
