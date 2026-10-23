import AppKit
import SwiftUI

// Mac stand-ins for the handful of UIKit and SafariServices calls in the shared iOS views.
// Each one does the native Mac thing: the clipboard is NSPasteboard, announcements go through
// NSAccessibility, and web sign-in pages open in the user's default browser.

/// `UIPasteboard.general.string = …` copies to the Mac clipboard.
@MainActor
final class UIPasteboard {
    static let general = UIPasteboard()

    var string: String? {
        get { NSPasteboard.general.string(forType: .string) }
        set {
            NSPasteboard.general.clearContents()
            if let newValue {
                NSPasteboard.general.setString(newValue, forType: .string)
            }
        }
    }
}

/// VoiceOver announcements.
enum UIAccessibility {
    enum Notification {
        case announcement
    }

    @MainActor
    static func post(notification: Notification, argument: Any?) {
        guard let message = argument as? String, let element = NSApp.keyWindow ?? NSApp.mainWindow else {
            return
        }
        NSAccessibility.post(
            element: element,
            notification: .announcementRequested,
            userInfo: [
                .announcement: message,
                .priority: NSAccessibilityPriorityLevel.high.rawValue
            ]
        )
    }
}

/// Settings → Notifications → "Open notification settings" opens System Settings.
enum UIApplication {
    static let openNotificationSettingsURLString =
        "x-apple.systempreferences:com.apple.Notifications-Settings.extension"
}

/// Sign-in pages open in the default browser, which keeps the user's password managers and
/// passkeys. The sheet stays up as a reminder until the sign-in completes or is closed.
struct SafariView: View {
    let url: URL
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "safari")
                .font(.system(size: 44))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)
            Text("Continue in your browser")
                .font(.title2.bold())
            Text("The sign-in page opened in your default browser. Finish there, then come back to Codex Meter.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text(url.host() ?? url.absoluteString)
                .font(.callout.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            HStack(spacing: 12) {
                Button("Open Again") { openURL(url) }
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .controlSize(.large)
        }
        .padding(32)
        .frame(width: 420)
        .onAppear { openURL(url) }
    }
}

/// Diagnostics → Export logs: a share menu plus Show in Finder for the exported file.
struct ShareSheet: View {
    let items: [Any]
    @Environment(\.dismiss) private var dismiss

    private var urls: [URL] { items.compactMap { $0 as? URL } }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Diagnostic logs exported", systemImage: "doc.zipper")
                .font(.headline)
            ForEach(urls, id: \.self) { url in
                Text(url.lastPathComponent)
                    .font(.callout.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            HStack {
                if let url = urls.first {
                    ShareLink(item: url)
                    Button("Show in Finder") {
                        NSWorkspace.shared.activateFileViewerSelecting(urls)
                    }
                }
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 420)
    }
}
