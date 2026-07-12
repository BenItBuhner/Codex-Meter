# AGENTS.md

## Cursor Cloud specific instructions

Codex Meter is a **native Android client app** (`dev.bennett.codexmeter`). There is **no backend, server, or database** in this repo, so there is no long-running service to start — the dev workflow is build / test / lint, and the shippable artifact is a signed APK. Standard commands live in `README.md`, `build.sh`, `run-tests.sh`, and `lint.sh`; the notes below only cover the non-obvious environment caveats.

### Toolchain / environment
- **Android SDK** is installed at `~/android-sdk` (`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`, `cmdline-tools`). `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and the SDK `PATH` entries are exported from `~/.bashrc` and persist via the VM snapshot. The startup update script reinstalls the SDK only if `platforms/android-36` or `build-tools/36.0.0` are missing.
- **JDK 21** is the system Java and satisfies the project's "JDK 17+" requirement; no separate JDK 17 install is needed. `build.sh`/`lint.sh` auto-detect `JAVA_HOME` only via macOS paths, so on this Linux VM ensure `JAVA_HOME` is set (e.g. `export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"`) before running them.

### Tests / lint / build
- `./run-tests.sh` runs **fully offline** on the JVM (parser/OAuth/PKCE/widget self-tests + source-integrity greps) with no device/emulator. It self-heals the `org.json` test jar by downloading it if `build/test-libs/` is empty (`build/` is gitignored). This is the primary fast verification loop — prefer it.
- **`./build.sh` (`:app:assembleRelease`) and `./lint.sh` (`:app:lintRelease`) require a GitHub Packages token.** The `oneui-design` library's transitive `sesl.*` dependencies are published **only** to `maven.pkg.github.com/tribalfs/oneui-design` (they 404 on Maven Central and JitPack) and GitHub Packages returns **HTTP 401** without auth even though they are public. Set `GH_USERNAME` and `GH_ACCESS_TOKEN` (a PAT with the `read:packages` scope) — see `README.md`. The pre-provisioned `gh` CLI token does **not** have `read:packages` for the `tribalfs` org and cannot be used. Only the top-level `oneui-design` aar is cached in `vendor/m2`, not its transitive tree, so the token is required until those deps are vendored.
- Running the app end-to-end additionally needs an Android emulator/device (API 26+) and a real ChatGPT account for the OAuth sign-in against OpenAI's servers — those are external to this repo.
