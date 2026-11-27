#!/usr/bin/env bash
# Self-test for ci-areas.sh: each case lists changed files and the areas that must run.
#   .github/scripts/ci-areas-test.sh
set -uo pipefail
cd "$(dirname "$0")"
failed=0
check() { # mode, expected areas (space separated, "all" or "none"), files...
  local mode="$1" want="$2" got
  shift 2
  got="$(printf '%s\n' "$@" | GITHUB_OUTPUT= ./ci-areas.sh "$mode" 2>/dev/null | awk -F= '$2 == "true" { print $1 }' | xargs)"
  case "$want" in
    all) want="$(GITHUB_OUTPUT= ./ci-areas.sh "$mode" --all 2>/dev/null | cut -d= -f1 | xargs)" ;;
    none) want="" ;;
  esac
  if [[ "$got" == "$want" ]]; then
    echo "ok    $mode [$*] -> ${got:-nothing}"
  else
    echo "FAIL  $mode [$*] -> ${got:-nothing}, expected ${want:-nothing}"
    failed=1
  fi
}

check gate "android" android/app/src/main/java/dev/bennett/codexmeter/MainActivity.java
check gate "android" CHANGELOG.md android/ci/shrunk-smoke.sh
check gate "apple" ios/CodexMeter/ContentView.swift macos/ci/perf-budgets.sh
check gate "apple" .swiftlint.yml
check gate "windows" windows/src/CodexMeter.App/MainWindow.cs
check gate "linux" linux/src/CodexMeter.Linux/App.cs
check gate "windows linux" windows/src/CodexMeter.Core/Providers/Cursor.cs
check gate "windows linux" windows/src/CodexMeter.App/Assets/ProviderIcons.g.cs
check gate "apple windows linux" ios/CodexMeterCore/Tests/CodexMeterCoreTests/Fixtures/usage.json
check gate "android windows" android/app/build.gradle.kts windows/README.md windows/installer/setup.iss
check gate "none" README.md docs/ci.md LICENSE
check gate "none" android/README.md windows/README.md ios/README.md
check gate "none" .github/workflows/ios-ci.yml .github/scripts/wait-for-gate-macos.sh .github/scripts/release-tested.sh
check gate "all" .github/workflows/ci.yml
check gate "all" android/tests/fixtures/codex-usage.json
check gate "all" release-invariants.sh
check gate "all" somewhere/new.txt
check gate "all" .github/CODEOWNERS
check gate "all"

check ios "watch" ios/CodexMeterWatch/WatchApp.swift
check ios "app" ios/CodexMeterTests/AuthenticationTests.swift
check ios "app gallery ipad" ios/CodexMeter/Views/Dashboard.swift ios/CodexMeterUITests/IPadLayoutTests.swift
check ios "ipad" ios/ci/record-ipad-gallery.sh
check ios "gallery" ios/ci/record-demo-gallery.sh
check ios "core" android/tests/fixtures/status/openai.json
check ios "none" ios/README.md android/app/src/main/AndroidManifest.xml
check ios "all" ios/CodexMeterCore/Sources/CodexMeterCore/Usage.swift
check ios "all" ios/CodexMeter.xcodeproj/project.pbxproj
check ios "all" ios/CodexMeterWatchShared/Shared.swift
check ios "all" .github/workflows/ios-ci.yml
check ios "all"

exit "$failed"
