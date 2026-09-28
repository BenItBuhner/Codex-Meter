import SwiftUI
import UserNotifications

extension Notification.Name {
    static let codexMeterOpenRoute = Notification.Name("codexMeterOpenRoute")
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let content = notification.request.content
        if content.categoryIdentifier == NotificationDeduplication.scheduledResetCategoryIdentifier {
            // The app is in front when the schedule comes due: run it now and let the
            // in-app state carry the result instead of asking the user to tap a banner.
            Task { @MainActor in
                await ScheduledResetDispatcher.runIfDue(source: "due-foreground")
            }
            return []
        }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let content = response.notification.request.content
        if let token = content.userInfo["token"] as? String {
            await NotificationCoordinator().markCreditExpiryAnnounced(token: token)
        }

        if response.actionIdentifier == NotificationDeduplication.runScheduledResetActionIdentifier {
            // A background action: no scene opens, so the reset runs to completion here.
            await ScheduledResetDispatcher.runIfDue(source: "notification-action")
            return
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
struct CodexMeterApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(model)
                .onReceive(NotificationCenter.default.publisher(for: .codexMeterOpenRoute)) { note in
                    if let route = note.userInfo?["route"] as? String {
                        model.handle(route: route)
                    }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.sceneBecameActive() }
            } else {
                model.sceneBecameInactive()
            }
        }
    }
}
