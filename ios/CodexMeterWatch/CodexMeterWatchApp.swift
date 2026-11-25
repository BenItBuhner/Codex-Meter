import SwiftUI

@main
struct CodexMeterWatchApp: App {
    @State private var model = WatchModel()

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environment(model)
                // Headers keep the case they are written in; header-case.sh checks it.
                .textCase(nil)
                .task { model.start() }
        }
    }
}
// CI path test (Apple Watch only); this pull request is never merged.
