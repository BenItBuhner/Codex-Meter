#!/usr/bin/env bash
# iPhone simulator for CI. Boot it after the build: a boot running alongside xcodebuild makes the
# build 2-2.5x slower on a 3-core runner, and a first boot spends 2-6 minutes in "Data Migration".
# (Caching the booted data folder was tried in Nov 2026: boots took ~50 s, but installing and
# launching the app afterwards took ~4 minutes longer, so it saved nothing.)
#
#   macos/ci/boot-simulator.sh pick       picks an iPhone; prints its UDID and writes `udid=` to
#                                         $GITHUB_OUTPUT
#   macos/ci/boot-simulator.sh boot UDID  boots it in the background; wait on it later with
#                                         `xcrun simctl bootstatus UDID` (no -b: it is booting)
#   macos/ci/boot-simulator.sh            pick, then boot
set -euo pipefail

pick() {
  local udid
  udid="$(xcrun simctl list devices available -j | jq -r '
    [.devices | to_entries[] | select(.key | test("iOS")) | .value[]
     | select(.name | startswith("iPhone"))] | first | .udid // empty')"
  [[ -n "$udid" ]] || { echo "::error::No iPhone simulator on this runner" >&2; exit 1; }
  xcrun simctl list devices available | grep "$udid" >&2
  [[ -n "${GITHUB_OUTPUT:-}" ]] && echo "udid=$udid" >> "$GITHUB_OUTPUT"
  echo "$udid"
}

boot() {
  nohup xcrun simctl boot "$1" > "${RUNNER_TEMP:-/tmp}/simulator-boot.log" 2>&1 &
}

case "${1:-}" in
  pick) pick ;;
  boot) boot "${2:?UDID required}" ;;
  "") boot "$(pick)" ;;
  *) echo "usage: $0 [pick | boot UDID]" >&2; exit 2 ;;
esac
