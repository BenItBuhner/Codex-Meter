# AGENTS.md

## Repository layout

Codex Meter is a **monorepo** with native clients and no backend:

| Path | Stack | Package / product |
|------|--------|-------------------|
| Repository root | Shared docs, license, changelog, CI entrypoints | — |
| `android/` | Android (Gradle `:app`, `:shared`, `:wear`) | `dev.bennett.codexmeter` (+ Wear companion) |
| `ios/` | SwiftUI / WidgetKit / watchOS (Xcode) | `CodexMeter` app + widgets + `CodexMeterWatch` + `CodexMeterCore` (marketing **1.2.0**) |

Clients talk directly to OpenAI/ChatGPT remote endpoints. Tokens stay on-device (Android Keystore / iOS Keychain).

Convenience wrappers at the repo root forward into the Android project:

- `./run-tests.sh` → `android/run-tests.sh`
- `./build.sh` → `android/build.sh`
- `./lint.sh` → `android/lint.sh`

iOS build instructions are in `ios/README.md`.

## Cursor Cloud specific instructions (Android)

### Toolchain (pre-installed in the VM snapshot)
- JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (project targets Java 17; JDK 21 builds fine with Gradle 9.6.1).
- Android SDK at `~/android-sdk` with `platforms;android-36` + `build-tools;36.0.0` + `platform-tools`.
- Gradle 9.6.1 via the committed wrapper (`android/gradlew`); no system Gradle needed.
- `JAVA_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and `PATH` are exported from `~/.bashrc`. `android/build.sh` / `android/lint.sh` only auto-detect these on macOS paths, so on this Linux VM they rely on those env vars being present. In a non-login/non-interactive shell that did not source `~/.bashrc`, export them first:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_SDK_ROOT=$HOME/android-sdk ANDROID_HOME=$HOME/android-sdk`.

### One UI / SESL dependency resolution
`android/build.sh` (`:app:assembleRelease` + `:wear:assembleRelease`) and `android/lint.sh` (`:app:lintRelease` + `:wear:lintRelease`) resolve `io.github.tribalfs:oneui-design` and its transitive **SESL** dependencies. GitHub Packages Maven **always requires authentication, even for public packages**, so without credentials live SESL downloads return `401 Unauthorized`. `android/vendor/m2` caches the top-level `oneui-design` AAR **and** the SESL transitive artifacts, so phone + Wear release builds work offline without `GH_USERNAME` / `GH_ACCESS_TOKEN`. Those env vars remain optional for refreshing deps from GitHub Packages; `android/settings.gradle.kts` still reads them when present.

### Running / testing (Android)
- `./run-tests.sh` (or `android/run-tests.sh`) compiles and runs the pure-Java core self-tests (usage-response parsing, PKCE/OAuth, JWT claims, widget options) — no Android SDK or GitHub creds required. Use this as the fast correctness check.
- There is no Android emulator/GUI in this VM, and an APK cannot be installed/launched headlessly here. Validate changes with `run-tests.sh` and a successful `build.sh`/`lint.sh`. Signed phone + Wear APKs land in `android/dist/` and are the product artifacts.

## iOS notes for agents

- Work under `ios/`. Keep Android changes under `android/`. Do not flatten either tree into the repo root.
- Prefer native SwiftUI / WidgetKit / WatchConnectivity patterns; do not port Samsung One UI or Android update installers.
- Core pure logic lives in `ios/CodexMeterCore` (iOS + watchOS platforms). ActivityKit types stay behind `os(iOS)`.
- Fast checks: `swift test --package-path ios/CodexMeterCore`; iPhone Simulator build of scheme `CodexMeter`; watchOS Simulator builds of schemes `CodexMeterWatch` and `CodexMeterWatchWidgets` (see `ios/README.md`).
- CI: `.github/workflows/ios.yml` (phone + watch unsigned builds).
- Full Xcode builds need a macOS host; Linux cloud VMs typically cannot build the iOS/watch targets.
