# Codex Meter for Android

Codex Meter is an unofficial native Android application and widget for viewing the Codex allowance attached to a signed-in ChatGPT account. It displays the rolling five-hour and weekly limits, reset times, reset credits, home-screen widgets, Samsung One UI lock-screen widgets, and optional reset notifications.

## Version 1.7.0

Version 1.7 adds locally updating reset countdowns and threshold-based reset alerts without increasing the frequency of requests to OpenAI.

### Live countdowns

Samsung lock-screen widgets can display the remaining time until each usage window resets. The countdown is driven locally by Android `Chronometer` views using the reset timestamp already cached from the usage response; it does not repeatedly contact the server merely to update seconds or minutes.

### Reset alerts

Users can choose silent, notification-sound, or alarm-sound alerts for the five-hour limit, weekly limit, or both. Alerts can be conditional on the most recently observed allowance being below a selected threshold. Android schedules the notification for the cached reset time and performs a normal background refresh after the alert fires.

### Widget surfaces

The app includes:

- Adaptive home-screen widgets with bars, minimal layouts, rings, and gauges.
- Both-window, five-hour-only, and weekly-only configurations.
- Optional reset-credit inventory, expiration, and redemption controls.
- Transparent through opaque backgrounds.
- Material expressive and One UI presentation modes.
- Samsung lock/AOD providers for Numbers, Rings, Gauges, and Bars in square and wide sizes.
- High-resolution supersampled lock-screen geometry with native Android text overlays.
- Optional live time-to-reset labels on supported lock-screen hosts.

## Authentication and data handling

- Browser-based ChatGPT sign-in using OAuth authorization code + PKCE and a localhost loopback callback.
- Access-token refresh with refresh-token rotation preservation.
- Android Keystore AES-GCM encryption for locally stored tokens.
- Usage and reset-credit retrieval from the ChatGPT backend routes used by Codex.
- No analytics, advertisements, WebView, or application-level relay server.

## Compatibility

- Minimum Android 8.0 (API 26)
- Target and compile SDK Android 16 (API 36)
- Universal DEX APK with no native ABI libraries
- Standard Android home-screen widgets
- Private Samsung One UI lock/AOD integration on compatible Galaxy firmware

## Build from source

The project deliberately avoids Gradle and third-party Android runtime libraries. The included build script invokes Android SDK tools directly.

Requirements:

- JDK 17 or newer
- Android SDK Platform 36
- Android Build Tools 36.x
- `ANDROID_SDK_ROOT` or `ANDROID_HOME` configured

```bash
./run-tests.sh
./build.sh
```

`build.sh` compiles resources with `aapt2`, compiles Java, runs D8, assembles an aligned APK, and signs it. Unless signing environment variables are supplied, it creates a local development key under `.local-signing/`. That locally signed APK will not install over the distributed release build.

To use an existing signing key, provide:

```bash
export CODEX_METER_KEYSTORE=/path/to/codex-meter.jks
export CODEX_METER_KEY_ALIAS=codexmeter
export CODEX_METER_STORE_PASS='...'
export CODEX_METER_KEY_PASS='...'
./build.sh
```

## Source provenance for this archive

The original transient 1.7.0 build workspace was not retained after the signed APK was delivered. This archive was reconstructed from the clean 1.5.0 project and the distributed 1.7.0 APK. The current 1.7 Java classes and resources were recovered from the APK, decompiler control-flow damage was repaired in the affected methods, and the complete Java source set was compiled against Android API 36.

This is the closest recoverable, build-oriented 1.7.0 source package, but it should not be represented as a byte-for-byte copy of the lost original authoring tree. See `SOURCE_RECOVERY.md` for exact details.

## Platform stability

The ChatGPT usage and reset-credit routes and Samsung's lock-screen metadata are implementation details rather than stable third-party Android SDK contracts. OpenAI or Samsung may change eligibility, routing, response fields, host behavior, or private metadata.

OpenAI, ChatGPT, Codex, Samsung, Galaxy, One UI, and related marks belong to their respective owners. This project is not affiliated with or endorsed by OpenAI or Samsung.
