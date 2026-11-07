import SwiftUI

@main
struct CodexMeterWatchApp: App {
    @State private var model = WatchModel()

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environment(model)
                .task { model.start() }
        }
    }
}
