# Codex Meter for Mac

Native SwiftUI app for macOS 15 Sequoia and later. It shows the same usage as the
iPhone app (Codex, Claude Code, and Cursor) in a Mac shape:

- **Menu bar meter.** A gauge whose needle follows the allowance closest to running
  out, with its remaining percentage. Click it for a panel with every provider's
  meters, reset countdowns, Refresh All (⌘R), sign-in shortcuts, and Open Codex Meter.
  Settings → Menu Bar picks the provider it follows, hides the percentage, or removes
  the item.
- **Main window.** A sidebar of providers (each with its headroom ring) and the
  selected provider's dashboard. The dashboards, Edit dashboard, usage history, reset
  credits, sign-in, and first-run setup are the iOS views, unchanged.
- **Settings window (⌘,).** General is the iOS settings page as a grouped Mac form
  (accounts, providers, dashboard, refresh, reset credits, estimates, notifications,
  data, about). Menu Bar holds the Mac-only options and Open at login.
- **Usage menu.** Refresh (⌘R), Refresh All Providers (⇧⌘R), switch provider
  (⌘1–⌘9), Use a Reset Credit…, Explore Demo.
- **Desktop and Notification Center widgets.** The iOS home-screen widget (small,
  medium, large, extra large) with the same configuration options. Lock Screen
  accessory widgets are iPhone-only.
- **Keeps running in the menu bar.** Closing the window leaves Codex Meter in the menu
  bar, where it refreshes Codex at the chosen interval or at the next reset (whichever
  is sooner), posts the same notifications, updates widgets, and auto-uses expiring
  resets. Claude Code and Cursor refresh when their dashboard or the menu bar panel
  opens, as on iOS, because their usage routes rate-limit polling.

Tokens stay in the Keychain and requests go straight to each provider, as on iOS.
Sign-in pages open in your default browser.

## How the code is shared

`macos/CodexMeterMac.xcodeproj` has no copies of iOS code. Its targets compile the iOS
folders directly through Xcode synchronized folders:

| Mac target | Compiles | Plus |
|------------|----------|------|
| `CodexMeterMac` (`Codex Meter.app`) | `ios/CodexMeter` except `CodexMeterApp.swift` and `ContentView.swift` | `CodexMeterMac/` (app, sidebar window, menu bar, Settings, Mac background refresh) and `Shared/` |
| `CodexMeterMacWidgets` | `ios/CodexMeterWidgets` except the accessory widgets, previews, and iOS bundle | `CodexMeterMacWidgets/` (Mac widget bundle) and `Shared/` |
| `CodexMeterMacTests` | `ios/CodexMeterTests` (the iOS unit tests, run on macOS) | — |
| `CodexMeterMacUITests` | — | `CodexMeterMacUITests/` |

All targets link the `ios/CodexMeterCore` Swift package. New files added to those iOS
folders join the Mac build automatically, so new providers and dashboard cards reach the
Mac without Mac-specific work, as long as the shared views avoid iOS-only APIs.

`Shared/IOSCompatibility.swift` gives the few iOS-only SwiftUI modifiers and colors the
shared views use a Mac meaning (for example `.topBarTrailing` becomes a window toolbar
action and `systemGroupedBackground` a matching Mac color), and
`CodexMeterMac/Platform/` replaces the UIKit pieces: the clipboard, VoiceOver
announcements, the Safari sign-in sheet (the default browser), the share sheet, and
`BackgroundRefreshCoordinator` (BGTaskScheduler is iOS-only).

## Build and run

Requires Xcode 26 or later.

```bash
open macos/CodexMeterMac.xcodeproj   # scheme CodexMeterMac, destination My Mac
```

From the command line, without a signing identity:

```bash
cd macos
xcodebuild -project CodexMeterMac.xcodeproj -scheme CodexMeterMac \
  -destination 'platform=macOS' -derivedDataPath DerivedData \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= \
  CODE_SIGN_ENTITLEMENTS= ENABLE_HARDENED_RUNTIME=NO test
```

Ad hoc builds run without the App Sandbox and App Group, so desktop widgets only load
from a build signed with the team's provisioning profile.

The app shares the iOS bundle identifier (`com.bukovinafilip.CodexMeter`) for
universal purchase, plus the iOS App Group, Keychain service names, and settings keys.

## Tests and gallery

`.github/workflows/macos-ci.yml` runs on every PR that touches `macos/` or the shared
`ios/` sources. It builds Debug and Release, checks the bundle (app and embedded widget
extension), runs the iOS unit tests on macOS and the Mac UI tests
(`CodexMeterMacUITests`), and uploads:

- **macos-demo-gallery**: window and full-screen stills of the offline demo in Light and
  Dark Mode (dashboards, Edit dashboard, Settings, menu bar panel, sign-in, setup),
  recorded by `ci/record-mac-gallery.sh`.
- **macos-app-unsigned**: the Release app, ad hoc signed. Right-click → Open the first
  time.

UI tests use the same debug launch arguments as iOS (`-ui-testing-demo`,
`-ui-testing-signed-out`, `-ui-testing-reset-settings`, `-ui-testing-onboarding`) plus
`-ui-testing-appearance light|dark`, `-ui-testing-menu-bar-preview` (shows the menu bar
panel in a window), and `-ui-testing-open-settings`.
