import AppKit
import CodexMeterCore
import Foundation
import ServiceManagement

/// Mac-only preferences (Settings → Menu Bar). Everything else lives in the shared
/// `AppSettings` the iOS app uses.
enum MacPreferences {
    static let showsMenuBarExtraKey = "codex-meter.mac.shows-menu-bar-extra-v1"
    static let menuBarShowsPercentKey = "codex-meter.mac.menu-bar-shows-percent-v1"
    /// A provider id, or empty to follow the provider selected in the window.
    static let menuBarProviderKey = "codex-meter.mac.menu-bar-provider-v1"

    static var showsMenuBarExtra: Bool {
        UserDefaults.standard.object(forKey: showsMenuBarExtraKey) as? Bool ?? true
    }

    /// The provider the menu bar meter follows.
    @MainActor
    static func menuBarProvider(storedID: String, model: AppModel) -> UsageProvider {
        if let provider = UsageProvider(rawValue: storedID), model.enabledProviders.contains(provider) {
            return provider
        }
        return model.selectedProvider
    }
}

/// Open at Login, through the system's login-item service.
enum LaunchAtLogin {
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    static var requiresApproval: Bool {
        SMAppService.mainApp.status == .requiresApproval
    }

    static func set(_ enabled: Bool) throws {
        if enabled {
            try SMAppService.mainApp.register()
        } else {
            try SMAppService.mainApp.unregister()
        }
    }
}

/// Launch arguments used by the UI tests and the screenshot gallery. Only read in Debug
/// builds; Release builds ignore them.
enum MacLaunchOptions {
    static var arguments: [String] { ProcessInfo.processInfo.arguments }

    /// `-ui-testing-appearance dark|light` forces the app's appearance regardless of the
    /// system setting, so the gallery can capture both.
    @MainActor
    static func applyAppearanceOverride() {
#if DEBUG
        guard let index = arguments.firstIndex(of: "-ui-testing-appearance"),
              index + 1 < arguments.count else { return }
        switch arguments[index + 1].lowercased() {
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        default: break
        }
#endif
    }

    /// `-ui-testing-menu-bar-preview` opens the menu bar panel in a window.
    static var opensMenuBarPreview: Bool {
#if DEBUG
        arguments.contains("-ui-testing-menu-bar-preview")
#else
        false
#endif
    }

    /// `-ui-testing-open-settings` opens the Settings window at launch.
    static var opensSettings: Bool {
#if DEBUG
        arguments.contains("-ui-testing-open-settings")
#else
        false
#endif
    }

    /// `-mac-diag-<name>` switches off one part of the Mac shell, so CI can bisect a launch
    /// problem without a Mac at hand: `commands`, `menubar`, `sidebar`, `dashboard`, `root`.
    static func diagnosticDisables(_ name: String) -> Bool {
#if DEBUG
        arguments.contains("-mac-diag-no-\(name)")
#else
        false
#endif
    }
}
