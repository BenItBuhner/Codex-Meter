# AGENTS.md

## Cursor Cloud specific instructions

Codex Meter is a **single native Android app** (Gradle module `:app`, package `dev.bennett.codexmeter`). There is no backend, database, web frontend, or companion service — it talks directly to OpenAI/ChatGPT remote endpoints. Standard build/test/lint commands are in `README.md` (`./run-tests.sh`, `./build.sh`, `./lint.sh`).

### Toolchain (pre-installed in the VM snapshot)
- JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (project targets Java 17; JDK 21 builds fine with Gradle 9.6.1).
- Android SDK at `~/android-sdk` with `platforms;android-36` + `build-tools;36.0.0` + `platform-tools`.
- Gradle 9.6.1 via the committed wrapper (`./gradlew`); no system Gradle needed.
- `JAVA_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and `PATH` are exported from `~/.bashrc`. `build.sh`/`lint.sh` only auto-detect these on macOS paths, so on this Linux VM they rely on those env vars being present. In a non-login/non-interactive shell that did not source `~/.bashrc`, export them first:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_SDK_ROOT=$HOME/android-sdk ANDROID_HOME=$HOME/android-sdk`.

### One UI / SESL dependency resolution
`build.sh` (`:app:assembleRelease` + `:wear:assembleRelease`) and `lint.sh` (`:app:lintRelease` + `:wear:lintRelease`) resolve `io.github.tribalfs:oneui-design` and its transitive **SESL** dependencies. GitHub Packages Maven **always requires authentication, even for public packages**, so without credentials live SESL downloads return `401 Unauthorized`. `vendor/m2` caches the top-level `oneui-design` AAR **and** the SESL transitive artifacts, so phone + Wear release builds work offline without `GH_USERNAME` / `GH_ACCESS_TOKEN`. Those env vars remain optional for refreshing deps from GitHub Packages; `settings.gradle.kts` still reads them when present.

### Running / testing
- `./run-tests.sh` compiles and runs the pure-Java core self-tests (usage-response parsing, PKCE/OAuth, JWT claims, widget options) — no Android SDK or GitHub creds required. Use this as the fast correctness check.
- There is no Android emulator/GUI in this VM, and an APK cannot be installed/launched headlessly here. Validate changes with `run-tests.sh` and a successful `build.sh`/`lint.sh`. Signed phone + Wear APKs land in `dist/` and are the product artifacts.
