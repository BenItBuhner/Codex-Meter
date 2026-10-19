# Codex Meter for iPhone and iPad

Native SwiftUI client for viewing the Codex allowance attached to a signed-in
ChatGPT account. It shows adaptive standard and model-specific usage windows,
Free-tier monthly limits, purchased usage credits, reset times, earned reset
credits, local burn history, notifications, and WidgetKit widgets.

This directory is the **iOS** package of the Codex Meter monorepo. The Android
application lives under [`../android/`](../android/). Behavior is aligned with
the Android app where platform APIs allow; it does not include Samsung One UI,
Wear OS tiles/complications, Now Bar / Live Update monitors, or Android in-app
APK updates.

Portable behavior through Android 2.8.0 is included: adaptive refresh, additional
limit parsing, Free-tier monthly windows, reorderable dashboard sections,
scrubbable on-device burn charts with insights and plan-value estimates,
usage-history customize, usage-credit and reset-credit auto-hiding, diagnostic
log export, and widgets that follow the weekly or monthly long window.

## Claude Code tab

Like the Android app, the dashboard has bottom provider tabs: **Codex** (the
default, unchanged) and **Claude Code**. The selection is remembered.

- Sign-in uses Claude Code's own OAuth client with PKCE and its manual
  redirect (`https://platform.claude.com/oauth/code/callback`): the app opens
  claude.ai in Safari, Claude shows a `CODE#STATE` value, and the user pastes it
  back. The state is checked before the code is exchanged.
- Usage comes from `GET https://api.anthropic.com/api/oauth/usage` (the route
  behind Claude Code's `/usage`) and maps onto the same snapshot and meter cards:
  5-hour, weekly, model caps such as Opus/Sonnet, and enabled extra usage. The
  plan badge and email come from `/api/oauth/profile`.
- That route rate-limits polling, so Claude usage is fetched only when the tab
  opens with data older than five minutes or on pull-to-refresh / Refresh. It is
  never part of background refresh, notifications, or widgets.
- Claude tokens use their own Keychain item (`claude-code-tokens`) and the
  snapshot its own cache file (`claude-cache-v1.json`); Codex storage keys are
  unchanged and the two sign-outs are independent.
- Demo mode shows local Claude data too. Parsing, plan labels, and OAuth request
  shapes live in `CodexMeterCore` (`ClaudeUsageParser`, `ClaudeCodeOAuth`,
  `UsageProvider`) and are covered by `swift test`.

These Anthropic routes are undocumented implementation details of Claude Code
and may change without notice; Codex Meter is not affiliated with Anthropic.

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
  -parallel-testing-enabled NO \
  -skip-testing:CodexMeterUITests/DemoGalleryTests test
```

The signed-out screen includes an offline demo mode. Automated tests never
contact OpenAI. The `-skip-testing` flag leaves out the demo gallery tour,
which takes several minutes and writes stills to `/tmp/codex-meter-gallery`;
drop it (or run the test from Xcode) when you want the tour locally.

iOS CI (`.github/workflows/ios-ci.yml`) runs the same commands on a macOS
runner for every iOS pull request and uploads an `ios-demo-gallery` artifact:
a screen recording plus numbered stills of the offline demo tour
(`CodexMeterUITests/DemoGalleryTests`, recorded by `ci/record-demo-gallery.sh`).
The tour is skipped in the correctness run and never fails the build.

## Layout

| Path | Role |
|------|------|
| `CodexMeter/` | Main app target |
| `CodexMeterWidgets/` | WidgetKit extension |
| `CodexMeterCore/` | Shared models/parsers (local Swift package) |
| `CodexMeterTests/` | Unit tests |
| `CodexMeterUITests/` | UI tests and the demo gallery tour |
| `ci/` | Demo gallery recording script used by iOS CI |

## Data and stability

OAuth credentials are stored only in the device Keychain. Widgets receive a
sanitized usage snapshot through an App Group and never receive credentials.
The app has no analytics, advertisements, or application relay server.

The ChatGPT usage and reset-credit routes are implementation details and may
change without notice. This app is not affiliated with or endorsed by OpenAI.
Production distribution is gated on confirming acceptable OpenAI OAuth, API,
trademark, and branding use; see `RELEASE_CHECKLIST.md`.

## License

MIT. See the repository root `LICENSE` and the in-app acknowledgements.
