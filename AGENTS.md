# AGENTS.md

Codex Meter is a monorepo of native clients with no backend: `android/` (Gradle `:app`, `:shared`, `:wear`, `:glass`), `ios/` (SwiftUI app, widgets, and the `CodexMeterCore` package), `macos/` (compiles the `ios/` sources), `windows/` (WinUI 3 on a shared C# core) and `linux/` (GTK 4 on the same core). Clients call provider endpoints directly and keep tokens on the device.

## Branches and releases

- `main` is the stable channel (`v2.7.0` tags, marked latest). `alpha` is the rapid channel (`v2.8.0-alpha.1` tags, prereleases). `CONTRIBUTING.md` has the full process.
- Target `alpha` unless the user says the work is for `main`. Docs, CI and user-requested hotfixes to the shipped stable can go to `main`.
- Never push `v*` tags, create or edit releases, or bump versions unless the user asks. Tags ship signed builds to real users.
- Never force-push `main` or `alpha`, and never delete or recreate `alpha`. Merging `main` into `alpha` is fine.
- An alpha version is the *next* stable version plus a suffix (after `2.7.0`, cut `2.8.0-alpha.1`, which SemVer orders above `2.7.0`). Its `versionCode` stays equal to the newest stable's, so switching channels stays an in-place install. A stable release drops the suffix, bumps `versionCode` by one and folds the alpha changelog sections together.
- `release-invariants.sh` lists every place a version is written and checks they agree; release prep edits those places, never the script.

## Checks

CI's `CI gate` job is the required check, and every job behind it treats warnings as errors. Run the part you touched before pushing:

- Android: `./release-invariants.sh`, `./run-tests.sh` (plain-JVM tests and resource rules, no SDK needed), `./lint.sh`, and `cd android && ./gradlew :app:testDebugUnitTest` for Robolectric tests. Fix new lint warnings rather than growing `lint-baseline.xml`; the baseline only shrinks.
- Desktop: `dotnet test windows/tests/CodexMeter.Core.Tests` and `dotnet test linux/tests/CodexMeter.Linux.Tests` (any OS), plus `dotnet format <project> --verify-no-changes`.
- Apple: `swiftlint lint --strict` and `swift test --package-path ios/CodexMeterCore`. Xcode builds need macOS.

Write tests that exercise behavior. Don't add checks that grep source for a string; a rule that matters belongs in a linter, `android/ci/resource-checks.py` (manifest and resource constraints) or `release-invariants.sh`. Tests wait on signals, never on sleeps.

## Platform judgment

- Android builds resolve One UI and SESL from `android/vendor/m2`, because GitHub Packages needs credentials even for public packages. Vendor any new pinned version there.
- Keep Android code under `android/` and Apple code under `ios/` or `macos/`. Don't port Samsung One UI patterns or Android update installers to Apple platforms.
- New `ios/` files join the Mac build automatically. Keep shared views free of iOS-only APIs where a Mac equivalent exists; map the rest in `macos/Shared/IOSCompatibility.swift`, and use `#if os(iOS)` only for code with no Mac meaning.
- There's no emulator in cloud VMs. Validate Android changes with the checks above; signed APKs land in `android/dist/`.
