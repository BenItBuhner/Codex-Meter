import SwiftUI
import WidgetKit

@main
struct CodexMeterWatchWidgetsBundle: WidgetBundle {
    var body: some Widget {
        WatchFiveHourWidget()
        WatchWeeklyWidget()
        WatchDualUsageWidget()
    }
}
