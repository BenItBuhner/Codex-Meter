# Contributing

This repository is a monorepo:

- Shared docs and release notes live at the repository root (`README.md`,
  `CHANGELOG.md`, `LICENSE`, `AGENTS.md`).
- **Android** lives under [`android/`](android/) (Gradle, `app/`, `shared/`,
  `wear/`, `tests/`).
- **iOS** lives under [`ios/`](ios/).

Keep platform-specific changes in the matching tree. Prefer focused commits and
update tests with behavior changes. Do not commit credentials, tokens, or
generated build artifacts.

## Android local setup

Install JDK 17 or newer and Android SDK Platform 36 with Build Tools 36.x. Set
`ANDROID_SDK_ROOT` or `ANDROID_HOME` to the SDK directory.

The OneUI-Design dependencies are hosted on GitHub Packages. Export `GH_USERNAME`
and a `GH_ACCESS_TOKEN` with `read:packages` access before running a full build or
Android lint when `android/vendor/m2` is incomplete.

From the repository root (wrappers) or from `android/`:

```bash
./run-tests.sh
./build.sh
./lint.sh
```

See [`android/README.md`](android/README.md) for module layout details.

## Release channels (Android)

Two long-lived branches feed two update channels in the app (Settings → Updates →
Update channel):

- `main` is the **stable** channel. Tags look like `v2.7.0`.
- `alpha` is the **rapid-iteration** channel. Tags look like `v2.7.0-alpha.1` and
  publish as GitHub prereleases. The `Ensure alpha branch` workflow creates the
  branch from `main` automatically if it is ever missing.

Both channels are built by the same tag-triggered CI job and signed with the same
release keystore, so the in-app updater's SHA-256 and signing-certificate checks
pass when switching channels in either direction — no uninstall/reinstall.

Versioning rules (enforced by CI on tags):

- **Alpha releases** bump only `versionName` (append/increment the `-alpha.N`
  suffix) and must keep `versionCode` **equal to** the newest stable release's
  `versionCode`. Android permits equal-`versionCode` installs, which is what makes
  the one-tap "Return to stable" flow an ordinary in-place install.
- **Stable releases** drop the suffix and bump `versionCode` by one, so a stable
  promotion is a normal upgrade for both channels.

Cutting an alpha: branch work off `alpha`, set `versionName` (for example,
`2.7.0-alpha.1`) in `android/app/build.gradle.kts`, `android/wear/build.gradle.kts`,
`AppConstants.java`, `android/build.sh`, and the guards in `android/run-tests.sh`,
add a `## 2.7.0-alpha.1` section to `CHANGELOG.md`, then tag `v2.7.0-alpha.1`.

Promoting to stable: merge `alpha` into `main`, drop the suffix, bump
`versionCode`, consolidate the alpha changelog sections under the stable version,
then tag as usual.

## iOS local setup

Install Xcode 26 or newer. From `ios/`:

```bash
swift test --package-path CodexMeterCore
xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

See [`ios/README.md`](ios/README.md) for device signing, App Groups, and release
checklist notes.
