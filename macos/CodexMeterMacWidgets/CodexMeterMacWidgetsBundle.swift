import SwiftUI
import WidgetKit

/// Desktop and Notification Center widgets. The widget, its configuration, timeline, and
/// views are the iOS widget sources (ios/CodexMeterWidgets); the Lock Screen accessory
/// widgets are iPhone-only and are left out.
@main
struct CodexMeterMacWidgetsBundle: WidgetBundle {
    var body: some Widget {
        CodexMeterHomeWidget()
    }
}
