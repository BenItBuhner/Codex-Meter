#!/usr/bin/env bash
# Works out which parts of CI a change needs, from the files it changes, so a pull request that only
# touches one app doesn't wait on the others.
#
#   .github/scripts/ci-areas.sh gate < changed-files   android, apple, windows, linux (CI gate jobs)
#   .github/scripts/ci-areas.sh ios  < changed-files   core, app, gallery, ipad, watch (iOS CI jobs)
#   .github/scripts/ci-areas.sh <gate|ios> --all       every area (pushes, manual runs, "full CI")
#
# Prints `area=true|false` lines, and appends them to $GITHUB_OUTPUT when it is set. It fails
# open: an empty list, a shared file or any path it doesn't know turns every area on. Only a list
# it fully understands turns anything off. Self-test: .github/scripts/ci-areas-test.sh.
set -uo pipefail

MODE="${1:?gate or ios}"
case "$MODE" in
  gate) AREAS=(android apple windows linux) ;;
  ios) AREAS=(core app gallery ipad watch) ;;
  *) echo "usage: $0 gate|ios [--all] < changed-files" >&2; exit 2 ;;
esac

declare -A on
for area in "${AREAS[@]}"; do on[$area]=false; done
reason=""

emit() {
  local area out=""
  [[ -n "$reason" ]] && echo "Running every area: $reason" >&2
  for area in "${AREAS[@]}"; do out+="$area=${on[$area]}"$'\n'; done
  printf '%s' "$out"
  [[ -n "${GITHUB_OUTPUT:-}" ]] && printf '%s' "$out" >> "$GITHUB_OUTPUT"
  exit 0
}
everything() {
  local area
  reason="$1"
  for area in "${AREAS[@]}"; do on[$area]=true; done
  emit
}
turn_on() { local area; for area in "$@"; do on[$area]=true; done; }

[[ "${2:-}" == --all ]] && everything "full run requested"

files="$(cat)"
[[ -n "${files//[[:space:]]/}" ]] || everything "no changed files listed"

while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  if [[ "$MODE" == gate ]]; then
    case "$f" in
      # The gate's own definition, and files every stack's checks read.
      .github/workflows/ci.yml|.github/scripts/ci-areas*.sh) everything "CI definition $f" ;;
      release-invariants.sh|header-case.sh|run-tests.sh|lint.sh|build.sh|.editorconfig) everything "shared file $f" ;;
      android/tests/fixtures/*) everything "shared fixture $f" ;;
      # Other workflows and their data, and the macOS wait script, run outside the gate.
      .github/workflows/*|.github/app-session-shots/*|.github/open-routing/*|.github/update-proof/*) ;;
      .github/scripts/wait-for-gate-macos.sh|.github/dependabot.yml) ;;
      .github/scripts/payload.sh) turn_on android ;;
      .github/*) everything "unclassified workflow file $f" ;;
      .swiftlint.yml) turn_on apple ;;
      # The iOS core fixtures are read by the desktop core tests too.
      ios/CodexMeterCore/Tests/CodexMeterCoreTests/Fixtures/*) turn_on apple windows linux ;;
      # The desktop core and the provider icons are shared by the Windows and Linux apps.
      windows/src/CodexMeter.Core/*|windows/tests/CodexMeter.Core.Tests/*|windows/src/CodexMeter.App/Assets/ProviderIcons.g.cs)
        turn_on windows linux ;;
      # The release job reads CHANGELOG.md; no build or test reads any other Markdown file.
      CHANGELOG.md) turn_on android ;;
      *.md|LICENSE|.gitignore|.gitattributes|docs/*) ;;
      android/*) turn_on android ;;
      windows/*) turn_on windows ;;
      linux/*) turn_on linux ;;
      ios/*|macos/*) turn_on apple ;;
      *) everything "unclassified path $f" ;;
    esac
  else
    case "$f" in
      .github/workflows/ios-ci.yml|.github/scripts/ci-areas*.sh) everything "iOS CI definition $f" ;;
      *.md|ios/LICENSE) ;;
      ios/CodexMeterWatch/*|ios/CodexMeterWatchWidgets/*|ios/ci/record-watch-gallery.sh) turn_on watch ;;
      ios/ci/record-demo-gallery.sh) turn_on gallery ;;
      ios/ci/record-ipad-gallery.sh) turn_on ipad ;;
      ios/CodexMeterTests/*) turn_on app ;;
      # The app, its widgets and its UI tests: every job that runs the iPhone or iPad app.
      ios/CodexMeter/*|ios/CodexMeterWidgets/*|ios/CodexMeterUITests/*) turn_on app gallery ipad ;;
      # The core package reads the shared provider fixtures.
      android/tests/fixtures/*) turn_on core ;;
      # The core package, the shared watch code and the project itself feed everything.
      ios/*) everything "shared iOS path $f" ;;
      # Everything outside ios/ is checked by the gate and the other workflows.
      *) ;;
    esac
  fi
done <<< "$files"
emit
