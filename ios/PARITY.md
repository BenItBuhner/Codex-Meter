# Apple platform parity — upstream v2.4.3

Baseline: [`BenItBuhner/Codex-Meter` v2.4.3](https://github.com/BenItBuhner/Codex-Meter/releases/tag/v2.4.3), commit `6efd43920b16437b9d96d538a99dc8737e5fbadf`.

This report separates portable product behavior from Android host integration. “Apple-native equivalent” means the same user outcome is delivered with supported Apple frameworks rather than private Samsung or Android APIs.

| Upstream capability | Apple status | Apple implementation / boundary |
|---|---|---|
| Five-hour and weekly allowance meters | Implemented | Shared Swift models and SwiftUI dashboard |
| Reset dates and local countdowns | Implemented | Timeline/date styles; no polling for countdown display |
| Reset-credit inventory, expiry, and phone redemption | Implemented | Irreversible confirmation and post-redemption refresh; redemption is intentionally unavailable on Watch |
| OAuth sign-in and refresh-token rotation | Apple-native equivalent | Device-code browser flow and Keychain storage |
| Offline/demo mode | Implemented | Local service with no network calls |
| Cached/error/freshness states | Implemented | App cache, dashboard banners, widgets, and watch offline state |
| Usage-pace projection | Implemented | `UsagePaceSensitivity` and `UsagePaceAssessment`, matching v2.4.3 gates |
| Off/Sensitive/Balanced/Relaxed warnings | Implemented | Off preserves estimates and suppresses warnings/auto-start |
| Live usage monitor | Apple-native equivalent | ActivityKit Lock Screen, Dynamic Island, and Smart Stack Live Activity |
| Accelerated-use monitor auto-start | Implemented | Phone-authoritative pace trigger with per-window dismissal suppression |
| Home-screen widgets | Apple-native equivalent | WidgetKit home and accessory families |
| Wear OS companion, tiles, and complications | Apple-native equivalent | watchOS glance app plus circular, rectangular, corner, and inline WidgetKit complications |
| Watch refresh command | Implemented | Reachable-watch command asks iPhone to perform the authenticated refresh |
| Settings transfer | Apple-native equivalent | Versioned JSON preferences only; OAuth and identity export are prohibited |
| First-run onboarding | Implemented | Sign in, demo, or skip |
| Usage, reset, credit, refill, and expiry notifications | Implemented | UserNotifications with permission state and configurable lead times |
| Appearance selection | Apple-native equivalent | System, Light, and Dark with semantic colors, contrast, and Dynamic Type |
| Android automatic APK updates | Android-only | App Store and TestFlight supply Apple updates |
| Material You and Samsung One UI components | Android/Samsung-only | Native SwiftUI design language |
| Samsung Now Bar and lock/AOD providers | Android/Samsung-only | ActivityKit, Dynamic Island, Smart Stack, and complications |
| Promoted-notification firmware diagnostics | Android/Samsung-only | No public Apple equivalent or diagnostic need |
| Exact alarms and reboot rescheduling | Android-only | BackgroundTasks and UserNotifications are best-effort system scheduling |
| APK signing/install and firmware compatibility | Android-only | App Store signing, archive validation, and TestFlight |

## Security invariants

- The iPhone is authoritative for authentication, settings, and network access.
- `SettingsTransferDocument` has no credential, identity, Keychain, or cached-usage fields.
- `WatchSnapshotEnvelope` contains only usage windows, credit count/expiry, plan label, mode, freshness, and timestamps.
- Apple Watch cannot redeem resets and never receives OAuth material or account email.

## Release gates

- Simulator builds do not prove OAuth, background delivery, ActivityKit, WatchConnectivity, or notification behavior on physical devices.
- Production distribution requires written authorization for the OAuth identity/private routes and acceptable use of OpenAI/ChatGPT/Codex branding.
- The account owner must explicitly approve the one real reset-redemption test.
