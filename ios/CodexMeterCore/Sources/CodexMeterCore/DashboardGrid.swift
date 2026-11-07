import Foundation

/// How dashboard cards share rows as the window gets wider: one column on iPhone and in narrow
/// iPad windows (Slide Over, a third of the screen in Split View or a small Stage Manager
/// window), two in a half-screen or portrait iPad window, three in a wide landscape window.
/// The choice follows the width the cards actually get, not the device or size class, so a
/// window that is resized live re-flows at the same points every time.
public enum DashboardGrid {
    /// Content narrower than this stays one card per row.
    public static let twoColumnMinWidth: Double = 600
    /// Content at least this wide gets three cards per row.
    public static let threeColumnMinWidth: Double = 980
    /// The dashboard stops widening here and centers instead, so cards never stretch across a
    /// 13-inch landscape screen with the sidebar hidden.
    public static let contentMaxWidth: Double = 1_240

    /// Cards per row for content `width` points wide. Accessibility text sizes keep one or two
    /// columns: a meter ring beside large text needs the room.
    public static func columns(forWidth width: Double, accessibilityText: Bool = false) -> Int {
        guard width.isFinite, width >= twoColumnMinWidth else { return 1 }
        if accessibilityText { return width >= threeColumnMinWidth ? 2 : 1 }
        return width >= threeColumnMinWidth ? 3 : 2
    }

    /// Groups cards into rows, keeping their order. A wide card (the usage history chart) takes
    /// a row of its own; the others fill rows of `columns`. Returns indexes into `isWide`.
    public static func rows(isWide: [Bool], columns: Int) -> [[Int]] {
        let columns = max(1, columns)
        var result: [[Int]] = []
        var current: [Int] = []
        for (index, wide) in isWide.enumerated() {
            if wide || columns == 1 {
                if !current.isEmpty {
                    result.append(current)
                    current = []
                }
                result.append([index])
                continue
            }
            current.append(index)
            if current.count == columns {
                result.append(current)
                current = []
            }
        }
        if !current.isEmpty {
            result.append(current)
        }
        return result
    }
}
