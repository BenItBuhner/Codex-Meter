#if os(iOS)
import CodexMeterCore
import SwiftUI
import UIKit

// iPad multiple windows. Usage data and settings stay on the one shared `AppModel`; each
// window keeps its own tab. Sheets the model asks for (Settings, sign-in, a reset) open in the
// window the person is using, and stay in that window until they close, so a second window
// never mirrors them.

/// Which window is in front, and which window owns the sheet that is open.
@MainActor
@Observable
final class WindowRegistry {
    static let shared = WindowRegistry()

    /// The window that last became key: where the keyboard and menu commands go.
    private(set) var keyWindowID: UUID?
    /// The window showing the model's sheets while any is open.
    private(set) var presenterID: UUID?
    private(set) var openWindows: [UUID] = []

    func windowAppeared(_ id: UUID) {
        if !openWindows.contains(id) { openWindows.append(id) }
        if keyWindowID.map({ !openWindows.contains($0) }) ?? true { keyWindowID = id }
    }

    func windowDisappeared(_ id: UUID) {
        openWindows.removeAll { $0 == id }
        if keyWindowID == id { keyWindowID = openWindows.last }
        if presenterID == id { presenterID = nil }
    }

    /// Records that window `id` is in front. Returns true when that is a change, so the
    /// window's tab should take over the model's selection.
    @discardableResult
    func windowBecameKey(_ id: UUID) -> Bool {
        guard keyWindowID != id else { return false }
        keyWindowID = id
        return true
    }

    /// Called when the model's sheet state changes: the first sheet to open is pinned to the
    /// window in front; the pin clears once every sheet is closed.
    func sheetsChanged(anyOpen: Bool) {
        if anyOpen {
            if presenterID == nil { presenterID = keyWindowID }
        } else {
            presenterID = nil
        }
    }

    /// True when window `id` should show the model's sheets.
    func presentsSheets(in id: UUID) -> Bool {
        if let presenterID, openWindows.contains(presenterID) { return presenterID == id }
        return isKey(id)
    }

    /// True when window `id` is the one in front. With one window, or before any window has
    /// reported becoming key (UIKit may make the first window key before it is observed), the
    /// only window counts as in front.
    func isKey(_ id: UUID) -> Bool {
        guard openWindows.count > 1,
              let keyWindowID,
              openWindows.contains(keyWindowID) else { return true }
        return keyWindowID == id
    }
}

/// Reports when the hosting `UIWindow` becomes key (tapped, clicked or focused from the
/// keyboard), so the registry knows which window the person is using.
struct KeyWindowObserver: UIViewRepresentable {
    let onBecomeKey: () -> Void

    func makeUIView(context: Context) -> ObserverView {
        let view = ObserverView()
        view.onBecomeKey = onBecomeKey
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: ObserverView, context: Context) {
        uiView.onBecomeKey = onBecomeKey
    }

    final class ObserverView: UIView {
        var onBecomeKey: (() -> Void)?
        nonisolated(unsafe) private var observer: NSObjectProtocol?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let observer { NotificationCenter.default.removeObserver(observer) }
            observer = nil
            guard let window else { return }
#if DEBUG
            Self.closeRestoredWindowsForUITests(keeping: window.windowScene)
#endif
            observer = NotificationCenter.default.addObserver(
                forName: UIWindow.didBecomeKeyNotification,
                object: window,
                queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.onBecomeKey?() }
            }
            if window.isKeyWindow {
                // Not during the layout pass that moved the view: SwiftUI state changes there
                // would land mid-update.
                DispatchQueue.main.async { [weak self] in self?.onBecomeKey?() }
            }
        }

#if DEBUG
        private static var closedRestoredWindows = false

        /// UI tests relaunch the app many times; iPadOS would bring back a window an earlier
        /// test opened, and key presses could then land in it. A reset launch keeps one window.
        private static func closeRestoredWindowsForUITests(keeping scene: UIWindowScene?) {
            guard !closedRestoredWindows,
                  let scene,
                  ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-settings") else { return }
            closedRestoredWindows = true
            for session in UIApplication.shared.openSessions where session != scene.session {
                UIApplication.shared.requestSceneSessionDestruction(session, options: nil)
            }
        }
#endif

        deinit {
            if let observer { NotificationCenter.default.removeObserver(observer) }
        }
    }
}
#endif
