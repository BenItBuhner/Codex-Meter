# Codex Meter for iPhone, iPad, and Apple Watch

Native SwiftUI client for viewing the Codex allowance attached to a signed-in
ChatGPT account. It shows the short and weekly usage windows, reset times,
earned reset credits, local notifications, WidgetKit widgets, an optional Live
Activity monitor, and a watchOS companion.

This directory is the **iOS / watchOS** package of the Codex Meter monorepo. The
Android application lives under [`../android/`](../android/). Behavior is aligned
with the Android app where platform APIs allow; it does not include Samsung One
UI, Now Bar private APIs, Material You, or Android in-app APK updates.

## Versioning

**iOS marketing version: 1.2.0** (`MARKETING_VERSION` in the Xcode project).

That is independent of the Android `versionName` / GitHub `v*` APK release tags.

### What’s in 1.2.0 (phases A–C + D maturity)

| Area | Features |
|------|----------|
| Core | Meters, reset credits, Keychain device-code auth, demo mode, widgets |
| Notifications | Low usage, scheduled resets, credit increases, unexpected refills, credit-expiry lead times |
| Transfer | Settings export/import (optional auth with warnings) |
| Onboarding | Three-page first run |
| Live Activity | Lock Screen / Dynamic Island usage monitor + auto-start |
| watchOS | Companion app + WCSession snapshot sync |
| CI | Core tests, iPhone Simulator build, watchOS Simulator build |

## Requirements

- Xcode 26 or newer
- iOS or iPadOS 26 or newer; watchOS 11+ for the companion
- An Apple development team for device builds, App Groups, Watch embedding, and
  the widget extension

## Continuous integration

PRs that touch `ios/` run `.github/workflows/ios.yml` on `macos-15`:

1. `swift test --package-path CodexMeterCore`
2. Unsigned iPhone Simulator build of `CodexMeter`
3. Unsigned watchOS Simulator build of `CodexMeterWatch`

## Live Activity usage monitor

Settings → **Usage monitor** can start a Live Activity (Lock Screen + Dynamic
Island) showing five-hour and weekly remaining percent, next reset timer, and
credits. Optional auto-start fires when remaining hits a threshold (similar to
Android Now Bar auto-start). The activity ends at the next reset, on stop, or
on sign-out. iOS cannot match Android exact-alarm / reboot alarm parity.

## watchOS companion

`CodexMeterWatch` is embedded in the iPhone app. The phone pushes a sanitized
`SharedWidgetSnapshot` over **WatchConnectivity** whenever widgets update (no
tokens, no network on the watch). The watch UI shows five-hour / weekly
remaining, credits, and next reset, and can request a re-push from the phone.

Complication **WidgetKit view definitions** live in
`CodexMeterWatch/WatchComplications.swift` and reload when a snapshot arrives.
A separate watch WidgetKit **extension** target may still be required for face
picker discovery depending on Xcode; the snapshot schema and defaults key are
shared via `WatchSyncPayload.watchDefaultsKey`.

Signing: same team as the iPhone app; bundle id
`com.bukovinafilip.CodexMeter.watchkitapp`.

## Build and test

From this `ios/` directory:

```sh
swift test --package-path CodexMeterCore
xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project CodexMeter.xcodeproj -target CodexMeterWatch \
  -destination 'generic/platform=watchOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination 'platform=iOS Simulator,name=iPhone 17e' \
  -parallel-testing-enabled NO test
```

The signed-out screen includes an offline demo mode. Automated tests never
contact OpenAI.

## Layout

| Path | Role |
|------|------|
| `CodexMeter/` | Main app target |
| `CodexMeterWidgets/` | Home / Lock Screen WidgetKit extension |
| `CodexMeterWatch/` | watchOS companion app |
| `CodexMeterCore/` | Shared models/parsers (local Swift package) |
| `CodexMeterTests/` | Unit tests |
| `CodexMeterUITests/` | UI tests |

## Data and stability

OAuth credentials are stored only in the device Keychain. Widgets and the watch
receive a sanitized usage snapshot only (App Group / WatchConnectivity) and
never receive credentials. The app has no analytics, advertisements, or
application relay server.

The ChatGPT usage and reset-credit routes are implementation details and may
change without notice. This app is not affiliated with or endorsed by OpenAI.
Production distribution is gated on confirming acceptable OpenAI OAuth, API,
trademark, and branding use; see `RELEASE_CHECKLIST.md`.

## License

MIT. See the repository root `LICENSE` and the in-app acknowledgements.
