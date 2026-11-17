import XCTest
@testable import CodexMeterCore

/// The Mac menu bar's "While using AI apps" list, through its stored (UserDefaults) form.
final class DesktopAppMappingsTests: XCTestCase {
    /// What the app in front points the menu bar at, after a save and reload of `list`.
    private func shows(_ list: DesktopAppList, _ names: String?...) -> UsageProvider? {
        DesktopAppList(json: list.json).matcher.provider(names: names)
    }

    func testAddedAppCountsForItsProviderAndASwitchedOffOneDoesNot() {
        var list = DesktopAppList()
        XCTAssertEqual(shows(list, "Grok Bot"), .grok)
        XCTAssertNil(shows(list, "Zed"))

        list.set("Grok Bot", to: .cursor)
        list.set("Zed", to: .claudeCode)
        XCTAssertEqual(shows(list, "Grok Bot"), .cursor)
        XCTAssertEqual(shows(list, "GrokBot.app"), .cursor)
        XCTAssertEqual(shows(list, "Grok Bot Helper"), .cursor)
        XCTAssertEqual(shows(list, "Zed"), .claudeCode)
        XCTAssertEqual(shows(list, "Grok"), .grok)

        list.setOn("Grok Bot", false)
        XCTAssertNil(shows(list, "Grok Bot"))
        // A switched-off app isn't brought back by its other names.
        XCTAssertNil(shows(list, "Grok Bot", "Grok"))
        list.setOn("Grok Bot", true)
        XCTAssertEqual(shows(list, "Grok Bot"), .cursor)

        let rules = DesktopAppList(json: list.json).rules
        let grokBot = rules.first { $0.app == "Grok Bot" }
        XCTAssertEqual(grokBot?.provider, .cursor)
        XCTAssertEqual(grokBot?.originalProvider, .grok)
        XCTAssertEqual(rules.last?.app, "Zed")
        XCTAssertEqual(rules.last?.isBuiltIn, false)
    }

    func testBuiltInAppsCanBeRemovedRenamedAndRestored() {
        var list = DesktopAppList()
        list.remove("Claude")
        XCTAssertNil(shows(list, "Claude"))
        XCTAssertFalse(list.rules.contains { $0.app == "Claude" })

        list.set("Cursor Nightly", to: .cursor, replacing: "Cursor")
        XCTAssertEqual(shows(list, "Cursor Nightly"), .cursor)
        XCTAssertNil(shows(list, "Cursor"))

        list.restoreDefaults()
        XCTAssertFalse(list.isCustomized)
        XCTAssertEqual(list.rules.map(\.app), DesktopAppList.defaults.map(\.app))
        XCTAssertEqual(shows(list, "Claude"), .claudeCode)
    }

    func testPointingABuiltInAppBackClearsTheEditAndDamagedValuesFallBack() {
        var list = DesktopAppList()
        list.set("Grok Bot", to: .cursor)
        list.set("grok bot", to: .grok)
        XCTAssertFalse(list.isCustomized)
        XCTAssertEqual(DesktopAppList(json: "not json"), DesktopAppList())
        XCTAssertNil(shows(list, "Claudette"))
        XCTAssertNil(shows(list, ""))
    }
}
