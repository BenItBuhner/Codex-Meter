# AGENTS.md

## Cursor Cloud specific instructions

Codex Meter is a **single native Android app** (Gradle module `:app`, package `dev.bennett.codexmeter`). There is no backend, database, web frontend, or companion service — it talks directly to OpenAI/ChatGPT remote endpoints. Standard build/test/lint commands are in `README.md` (`./run-tests.sh`, `./build.sh`, `./lint.sh`).

### Toolchain (pre-installed in the VM snapshot)
- JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (project targets Java 17; JDK 21 builds fine with Gradle 9.6.1).
- Android SDK at `~/android-sdk` with `platforms;android-36` + `build-tools;36.0.0` + `platform-tools`.
- Gradle 9.6.1 via the committed wrapper (`./gradlew`); no system Gradle needed.
- `JAVA_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and `PATH` are exported from `~/.bashrc`. `build.sh`/`lint.sh` only auto-detect these on macOS paths, so on this Linux VM they rely on those env vars being present. In a non-login/non-interactive shell that did not source `~/.bashrc`, export them first:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_SDK_ROOT=$HOME/android-sdk ANDROID_HOME=$HOME/android-sdk`.

### Blocker: GitHub Packages credentials required for the full build
`build.sh` (`:app:assembleRelease`) and `lint.sh` (`:app:lintRelease`) resolve `io.github.tribalfs:oneui-design`'s transitive **SESL** dependencies from `https://maven.pkg.github.com/tribalfs/oneui-design`. GitHub Packages Maven **always requires authentication, even for public packages**, so without credentials every SESL artifact returns `401 Unauthorized` and the build fails. `vendor/m2` only caches the top-level `oneui-design` AAR, not its transitive deps.

To build/lint, set repo secrets `GH_USERNAME` (a GitHub username) and `GH_ACCESS_TOKEN` (a PAT with `read:packages`); `settings.gradle.kts` reads them from the environment. `run-tests.sh` does **not** need these credentials.

### Running / testing
- `./run-tests.sh` compiles and runs the pure-Java core self-tests (usage-response parsing, PKCE/OAuth, JWT claims, widget options) — no Android SDK or GitHub creds required. Use this as the fast correctness check.
- There is no Android emulator/GUI in this VM, and an APK cannot be installed/launched headlessly here. Validate changes with `run-tests.sh` and (once creds exist) a successful `build.sh`/`lint.sh`. The signed APK lands in `dist/` and is the product artifact.
