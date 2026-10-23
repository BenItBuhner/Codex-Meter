import AppKit
import CodexMeterCore
import SwiftUI
import UserNotifications

extension Notification.Name {
    static let codexMeterOpenRoute = Notification.Name("codexMeterOpenRoute")
}

/// Owns the one `AppModel` shared by the main window, the menu bar extra, and Settings, and
/// mirrors the iOS app delegate's notification handling.
@MainActor
final class MacAppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    let model = AppModel()

    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = self
        MacLaunchOptions.applyAppearanceOverride()
        Task { await model.startIfNeeded() }
    }

    /// Closing the window leaves Codex Meter in the menu bar, still refreshing on schedule.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        !MacPreferences.showsMenuBarExtra
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        Task { await model.sceneBecameActive() }
    }

    func applicationDidResignActive(_ notification: Notification) {
        model.sceneBecameInactive()
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let content = response.notification.request.content
        if let token = content.userInfo["token"] as? String {
            await NotificationCoordinator().markCreditExpiryAnnounced(token: token)
        }

        let route: String?
        if response.actionIdentifier == NotificationDeduplication.useResetActionIdentifier {
            route = "reset"
        } else if let value = content.userInfo[NotificationDeduplication.routeUserInfoKey] as? String {
            route = value
        } else {
            route = nil
        }

        guard let route else { return }
        await MainActor.run {
            NotificationCenter.default.post(
                name: .codexMeterOpenRoute,
                object: nil,
                userInfo: ["route": route]
            )
        }
    }
}

@main
struct CodexMeterMacApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    @AppStorage(MacPreferences.showsMenuBarExtraKey) private var showsMenuBarExtra = true

    private var model: AppModel { appDelegate.model }

    /// MenuBarExtra writes its isInserted binding back on every scene update. Writing an
    /// unchanged value to @AppStorage still posts a defaults change, which re-evaluates the
    /// scene and loops at full CPU, so only real changes are stored.
    private var menuBarExtraInserted: Binding<Bool> {
        if MacLaunchOptions.diagnosticDisables("menubar") { return .constant(false) }
        let stored = showsMenuBarExtra
        return Binding(
            get: { stored },
            set: { newValue in
                if newValue != stored { showsMenuBarExtra = newValue }
            }
        )
    }

    var body: some Scene {
        Window("Codex Meter", id: MacSceneID.main) {
            if MacLaunchOptions.diagnosticDisables("root") {
                Text("Codex Meter")
                    .frame(width: 400, height: 300)
            } else {
                MacRootView()
                    .environment(model)
            }
        }
        .defaultSize(width: 1_040, height: 760)
        .commands {
            MacCommands(model: model)
        }

        MenuBarExtra(isInserted: menuBarExtraInserted) {
            MenuBarPanel()
                .environment(model)
        } label: {
            MenuBarLabel()
                .environment(model)
        }
        .menuBarExtraStyle(.window)

        Settings {
            MacSettingsView()
                .environment(model)
        }

        // A plain window that shows the menu bar panel, opened only by the UI-test gallery so
        // the panel can be captured without clicking the status item.
        Window("Menu Bar Preview", id: MacSceneID.menuBarPreview) {
            MenuBarPanel()
                .environment(model)
        }
        .windowResizability(.contentSize)
        .defaultLaunchBehavior(.suppressed)
    }
}

enum MacSceneID {
    static let main = "main"
    static let menuBarPreview = "menu-bar-preview"
}
