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

## Release bar

A stable release (3.0.0 first) ships only when every item below holds on the commit being released. An alpha may ship with known gaps, but each one is written in its PR or the alpha's changelog section. A failing item blocks the release; it is never waived by editing this list in the same change.

**CI**
- `CI gate` is green on the commit, and the ruleset on `main` and `alpha` requires it, so nothing merges around it.
- No flaky tests, in the gate or in the optional workflows. A test that fails without a code change is a bug: fix its cause (wait on the right signal, scroll until the element is hittable, retry an input the simulator drops) in the PR that sees it. Never skip, quarantine or delete a test to get green, and don't lean on `-retry-tests-on-failure` to hide a real failure.
- Every optional workflow that a release depends on (iOS CI with the iPad suite, macOS CI, Windows UI tests, the update proof, the galleries) has passed on the release commit or on a commit with the same code for that platform.

**Performance and size.** Budgets are enforced in CI, not checked by hand. Raise a ceiling only with a measured reason in the PR; lower ceilings as the app gets leaner. The Android and Apple budgets take 15-30 minutes, so they run in the "Performance budgets" workflow (`performance.yml`: every push to `main` and `alpha`, nightly, on demand, and Android on its PRs) rather than behind `CI gate`; both jobs must be green on the release commit. A red run on `alpha` is fixed forward right away, and a red Android run on a PR is fixed before merging. The Windows and Linux budgets run in about 2 minutes and stay in the gate.
- Android release APK: cold start (median of 11 launches), total PSS after launch and APK size, measured on an emulator by `android/ci/perf-budgets.sh` (the "Android performance budgets" job).
- iPhone (simulator) and Mac Release builds: first frame (median of 7 launches) and physical footprint after launch, by `macos/ci/perf-budgets.sh` (the "Apple performance budgets" job).
- Windows and Linux release builds: first frame (median of 7 launches) and working set after launch, by `windows/ci/perf-budgets.ps1` and `linux/ci/perf-budgets.sh` (the "Windows/Linux performance budgets" jobs).
- Android release builds (phone and watch) are shrunk by R8. Code reached only by reflection or by name needs a keep rule in `app/proguard-rules.pro` or `wear/proguard-rules.pro`; the same job runs `android/ci/shrunk-smoke.sh`, which opens every design and screen on a debug build shrunk by those rules (`-PshrinkDebug=true`) and fails on a crash.
- Wire payloads (widget, Wear, live monitor): `android/tests/PayloadBudgetSelfTest.java`, reported on each PR by the "Payload budgets" job.
- A refresh never blocks the UI thread, never retries without backoff, and keeps the last good snapshot on screen when it fails.

**Errors and crashes**
- No uncaught exception path. Every platform logs crashes and unobserved background failures to its local log and, when on, the opt-in diagnostic log.
- Every network or parse failure shows a plain message and keeps cached data visible. Tests cover the failure path, not just the happy path.
- No `force_try` or `force_cast`, no swallowed exception without a comment saying why, and `async void` only for UI event handlers that catch their own failures.

**Parity and providers**
- Headers and labels keep the case they are written in on every platform and design ("Appearance", never "APPEARANCE"): never upper-case them in code, and SwiftUI app roots keep `.textCase(nil)`. `header-case.sh` checks this in CI.
- Settings use the shared layout and names on Android, iOS, macOS, Windows and Linux (Providers, Notifications, Live usage, Appearance, Updates, Privacy & data, About; each provider page has Account, Meters, Plan and, where the provider has them, Reset credits). A feature missing on a platform is listed in its PR with the reason.
- Every provider works on every platform where it can: no artificial gates, no `inferred` routes, no heuristic parsers. Parsers change together in Java, Swift and C#, with shared fixtures.
- Value estimates use dated, sourced figures and show Unknown rather than a guess.

**Feedback and real devices**
- Every tester report is either fixed and confirmed in a build the tester used, or written down as deferred with Bennett's agreement.
- Before a stable release, the release candidate is installed and used on real hardware: an Android phone (One UI) with widgets, live notification and the in-app updater; Wear OS; an iPhone with widgets and Live Activity; a Mac; Windows; and Linux. Emulators and simulators don't count for this step.

## Platform judgment

- Android builds resolve One UI and SESL from `android/vendor/m2`, because GitHub Packages needs credentials even for public packages. Vendor any new pinned version there.
- Keep Android code under `android/` and Apple code under `ios/` or `macos/`. Don't port Samsung One UI patterns or Android update installers to Apple platforms.
- New `ios/` files join the Mac build automatically. Keep shared views free of iOS-only APIs where a Mac equivalent exists; map the rest in `macos/Shared/IOSCompatibility.swift`, and use `#if os(iOS)` only for code with no Mac meaning.
- There's no emulator in cloud VMs. Validate Android changes with the checks above; signed APKs land in `android/dist/`.
