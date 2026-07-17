# Contributing

This repository is a monorepo:

- **Android** lives at the repository root (Gradle, `app/`, `shared/`, `wear/`, `tests/`).
- **iOS** lives under [`ios/`](ios/).

Keep platform-specific changes in the matching tree. Prefer focused commits and
update tests with behavior changes. Do not commit credentials, tokens, or
generated build artifacts.

## Android local setup

Install JDK 17 or newer and Android SDK Platform 36 with Build Tools 36.x. Set
`ANDROID_SDK_ROOT` or `ANDROID_HOME` to the SDK directory.

The OneUI-Design dependencies are hosted on GitHub Packages. Export `GH_USERNAME`
and a `GH_ACCESS_TOKEN` with `read:packages` access before running a full build or
Android lint.

Run the core checks, release build, and Android lint with:

```bash
./run-tests.sh
./build.sh
./lint.sh
```

## iOS local setup

Install Xcode 26 or newer. From `ios/`:

```bash
swift test --package-path CodexMeterCore
xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

See [`ios/README.md`](ios/README.md) for device signing, App Groups, and release
checklist notes.
