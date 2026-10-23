#if os(macOS)
import AppKit
import SwiftUI

// The Mac app and its widgets compile the iOS app's SwiftUI sources unchanged (see
// macos/README.md). The few iOS-only modifiers and colors those views use are given Mac
// meanings here, so the shared files need no platform checks and stay easy to merge.

// MARK: - Navigation bar title mode

/// A Mac window has a single title style, so the inline/large choice is ignored.
enum NavigationBarItem {
    enum TitleDisplayMode: Sendable {
        case automatic
        case inline
        case large
    }
}

extension View {
    func navigationBarTitleDisplayMode(_ displayMode: NavigationBarItem.TitleDisplayMode) -> some View {
        self
    }

    /// Mac text fields never auto-capitalize.
    func textInputAutocapitalization(_ autocapitalization: TextInputAutocapitalization?) -> some View {
        self
    }
}

struct TextInputAutocapitalization: Sendable {
    static let never = TextInputAutocapitalization()
    static let words = TextInputAutocapitalization()
    static let sentences = TextInputAutocapitalization()
    static let characters = TextInputAutocapitalization()
}

// MARK: - Toolbar placements

extension ToolbarItemPlacement {
    /// Trailing navigation-bar buttons become window toolbar actions.
    static var topBarTrailing: ToolbarItemPlacement { .primaryAction }
    /// The leading navigation-bar slot is the toolbar's navigation area.
    static var topBarLeading: ToolbarItemPlacement { .navigation }
}

// MARK: - Search field placement

/// Stands in for iOS's `NavigationBarDrawerDisplayMode`, which the Mac SDK declares but marks
/// unavailable (so it can't be redeclared under that name).
enum SearchDrawerDisplayMode: Sendable {
    case automatic
    case always
}

extension SearchFieldPlacement {
    /// iOS pins a search field in a drawer under the navigation bar; a Mac window puts it in
    /// the toolbar, so the drawer placement maps to the automatic one.
    static func navigationBarDrawer(displayMode: SearchDrawerDisplayMode) -> SearchFieldPlacement {
        .automatic
    }
}

// MARK: - Size class and edit mode

/// A Mac window is always laid out as a regular-width page; the dashboard's two-column card
/// grid still collapses on its own when the window is narrow.
enum UserInterfaceSizeClass: Sendable, Hashable {
    case compact
    case regular
}

/// Mac lists reorder by dragging without an edit mode, so the value is accepted and ignored.
enum EditMode: Sendable, Hashable {
    case inactive
    case transient
    case active

    var isEditing: Bool { self != .inactive }
}

extension EnvironmentValues {
    var horizontalSizeClass: UserInterfaceSizeClass? {
        get { .regular }
        set {}
    }

    var verticalSizeClass: UserInterfaceSizeClass? {
        get { .regular }
        set {}
    }

    var editMode: Binding<EditMode>? {
        get { nil }
        set {}
    }
}

// MARK: - System colors

extension NSColor {
    /// The grouped page behind cards: light gray in Light Mode, near-black in Dark Mode,
    /// matching the iOS grouped background the cards were designed on.
    static var systemGroupedBackground: NSColor {
        dynamic(light: (0.949, 0.949, 0.969), dark: (0.110, 0.110, 0.118))
    }

    /// Card surface on the grouped page.
    static var secondarySystemGroupedBackground: NSColor {
        dynamic(light: (1, 1, 1), dark: (0.173, 0.173, 0.180))
    }

    static var systemBackground: NSColor {
        dynamic(light: (1, 1, 1), dark: (0.110, 0.110, 0.118))
    }

    static var secondarySystemBackground: NSColor {
        dynamic(light: (0.949, 0.949, 0.969), dark: (0.173, 0.173, 0.180))
    }

    private static func dynamic(
        light: (CGFloat, CGFloat, CGFloat),
        dark: (CGFloat, CGFloat, CGFloat)
    ) -> NSColor {
        NSColor(name: nil) { appearance in
            let rgb = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: rgb.0, green: rgb.1, blue: rgb.2, alpha: 1)
        }
    }
}
#endif
