# Codex Meter for iPhone and iPad

Native SwiftUI client for viewing the Codex allowance attached to a signed-in
ChatGPT account. It shows the short and weekly usage windows, reset times,
earned reset credits, local notifications, and WidgetKit widgets.

This directory is the **iOS** package of the Codex Meter monorepo. The Android
application lives under [`../android/`](../android/). Behavior is aligned with
the Android app where platform APIs allow; it does not include Samsung One UI,
Now Bar / Live Update monitors, or Android in-app APK updates.

Portable notification features from Android 2.1/2.2 are included: unexpected
refill alerts, independent reset-credit increase alerts, and configurable
reset-credit expiry reminders.

## Requirements

- Xcode 26 or newer
- iOS or iPadOS 26 or newer
- An Apple development team for device builds, App Groups, and the widget
  extension

## Build and test

From this `ios/` directory:

```sh
swift test --package-path CodexMeterCore
xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
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
| `CodexMeterWidgets/` | WidgetKit extension |
| `CodexMeterCore/` | Shared models/parsers (local Swift package) |
| `CodexMeterTests/` | Unit tests |
| `CodexMeterUITests/` | UI tests |

## Data and stability

OAuth credentials are stored only in the device Keychain. Widgets receive a
sanitized usage snapshot through an App Group and never receive credentials.
The app has no analytics, advertisements, or application relay server.

The ChatGPT usage and reset-credit routes are implementation details and may
change without notice. This app is not affiliated with or endorsed by OpenAI.
Production distribution is gated on confirming acceptable OpenAI OAuth, API,
trademark, and branding use; see `RELEASE_CHECKLIST.md`.

## Credits

- Original Android project: [Bennett](https://github.com/BenItBuhner)
- iOS development: [Filip Bukovina](https://github.com/FBukovina)

## License

MIT. See the repository root `LICENSE` and the in-app acknowledgements.
