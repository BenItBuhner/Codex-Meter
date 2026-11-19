import Foundation

/// The version people see and diagnostics record: the full release version (2.9.0-alpha.30) when
/// the build carries one, since the bundle's short version must be numbers only (2.9.0).
enum AppVersion {
    static var display: String {
        let info = Bundle.main.infoDictionary ?? [:]
        if let full = info["CodexMeterReleaseVersion"] as? String, !full.isEmpty { return full }
        return info["CFBundleShortVersionString"] as? String ?? "1.0"
    }
}
