# AGENTS.md

## Repository layout

Codex Meter is a **monorepo** with native clients and no backend:

| Path | Stack | Package / product |
|------|--------|-------------------|
| Repository root | Shared docs, license, changelog, CI entrypoints | — |
| `android/` | Android (Gradle `:app`, `:shared`, `:wear`) | `dev.bennett.codexmeter` (+ Wear companion) |
| `ios/` | SwiftUI / WidgetKit (Xcode) | `CodexMeter` app + widgets + `CodexMeterCore` package |

Clients talk directly to OpenAI/ChatGPT remote endpoints. Tokens stay on-device (Android Keystore / iOS Keychain).

## Release channels & etiquette (Android)

Two long-lived branches feed the app's two update channels (Settings → Updates → Update channel); the full process lives in `CONTRIBUTING.md`:

- `main` — **stable** channel. Tags look like `v2.7.0` and publish as regular GitHub releases marked latest.
- `alpha` — **rapid-iteration** channel. Tags look like `v2.7.0-alpha.1` (lowercase) and publish as GitHub prereleases, never latest.

Rules for agents:

- Target **`alpha`** with feature and experimental PRs unless the user explicitly says the work is for `main`. Docs, CI, and user-requested hotfixes to the shipped stable go to `main`.
- **Never push `v*` tags or create/edit GitHub releases unless the user explicitly asks.** Tags trigger signed public releases that ship to real users on both channels.
- **Never bump `versionCode`/`versionName` on your own.** Version bumps are release preparation and happen only when the user asks to cut a release.
- Preparing an **alpha release** (`X.Y.Z-alpha.N`): bump `versionName` only; `versionCode` must stay **equal to** the newest stable release's versionCode. CI rejects the tag otherwise — this invariant is what keeps in-app channel switching (including "Return to stable") an in-place install with no uninstall.
- Preparing a **stable release** (promotion): merge `alpha` into `main`, drop the suffix, bump `versionCode` by exactly one, and consolidate the alpha changelog sections under the stable version.
- Any release prep must update every synced version touchpoint together, or `run-tests.sh` fails: `android/app/build.gradle.kts`, `android/wear/build.gradle.kts`, `AppConstants.java` (`VERSION_NAME`, `VERSION_CODE`, and the literal user-agent string), `android/build.sh`, the version guards in `android/run-tests.sh`, and a matching `## <version>` section in root `CHANGELOG.md` (the release job fails when notes are missing).
- Merging `main` into `alpha` to keep it fresh is fine; never force-push either branch, and never delete or recreate `alpha` on your own.

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
- Prefer native SwiftUI / WidgetKit patterns; do not port Samsung One UI or Android update installers.
- Core pure logic for the iOS app lives in `ios/CodexMeterCore`.
- Fast checks: `swift test --package-path ios/CodexMeterCore` (from repo root) or from `ios/` as documented in `ios/README.md`.
- Full Xcode builds need a macOS host; Linux cloud VMs typically cannot build the iOS target.
