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
        [.banner, .list, .sound]
    }

    func userNotificationCenter(
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
struct CodexMeterApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppModel()

    var body: some Scene {
        // iPad can open several windows; Open in New Window passes the provider to show first.
        WindowGroup(for: UsageProvider.self) { $provider in
            ContentView(openedProvider: provider)
                .environment(model)
#if DEBUG
                .modifier(SimulatedWindowWidth())
#endif
                .onReceive(NotificationCenter.default.publisher(for: .codexMeterOpenRoute)) { note in
                    if let route = note.userInfo?["route"] as? String {
                        model.handle(route: route)
                    }
                }
        }
        .commands {
            CodexMeterCommands(model: model)
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

#if DEBUG
/// UI tests only: `-ui-testing-window-width 507` lays the app out in a column that wide, at
/// the size class iPadOS gives a window that wide, to check Split View, Slide Over and
/// Stage Manager widths that a simulator can't be dragged to from a test.
private struct SimulatedWindowWidth: ViewModifier {
    private static let width: CGFloat? = {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-ui-testing-window-width"),
              index + 1 < arguments.count,
              let value = Double(arguments[index + 1]), value > 0 else { return nil }
        return CGFloat(value)
    }()

    func body(content: Content) -> some View {
        if let width = Self.width {
            content
                .environment(\.horizontalSizeClass, width < 600 ? .compact : .regular)
                .frame(width: width)
                .clipped()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.systemGray4).ignoresSafeArea())
        } else {
            content
        }
    }
}
#endif
