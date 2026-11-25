#!/usr/bin/env bash
# iPhone simulator for CI. A first boot on a fresh runner spends 2-6 minutes in "Data Migration";
# a device whose data folder was booted once before (cached per runner image) skips that.
#
#   macos/ci/boot-simulator.sh pick       picks an iPhone; prints its UDID and writes `udid=`,
#                                         `data=` (its data folder) and `key=` (a cache key for
#                                         that folder on this runner image) to $GITHUB_OUTPUT
#   macos/ci/boot-simulator.sh boot UDID  boots it in the background; wait on it later with
#                                         `xcrun simctl bootstatus UDID` (no -b: it is booting)
#   macos/ci/boot-simulator.sh stop UDID  shuts it down, so its data folder can be cached
#   macos/ci/boot-simulator.sh            pick, then boot
set -euo pipefail

pick() {
  local udid
  udid="$(xcrun simctl list devices available -j | jq -r '
    [.devices | to_entries[] | select(.key | test("iOS")) | .value[]
     | select(.name | startswith("iPhone"))] | first | .udid // empty')"
  [[ -n "$udid" ]] || { echo "::error::No iPhone simulator on this runner" >&2; exit 1; }
  xcrun simctl list devices available | grep "$udid" >&2
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      echo "udid=$udid"
      echo "data=$HOME/Library/Developer/CoreSimulator/Devices/$udid/data"
      echo "key=simulator-${ImageOS:-macos}-${ImageVersion:-local}-$udid-v1"
    } >> "$GITHUB_OUTPUT"
  fi
  echo "$udid"
}

boot() {
  nohup xcrun simctl boot "$1" > "${RUNNER_TEMP:-/tmp}/simulator-boot.log" 2>&1 &
}

case "${1:-}" in
  pick) pick ;;
  boot) boot "${2:?UDID required}" ;;
  stop)
    SECONDS=0
    xcrun simctl shutdown "${2:?UDID required}" || true
    echo "Shut down in ${SECONDS}s; data folder $(du -sh "$HOME/Library/Developer/CoreSimulator/Devices/$2/data" | cut -f1)"
    ;;
  "") boot "$(pick)" ;;
  *) echo "usage: $0 [pick | boot UDID | stop UDID]" >&2; exit 2 ;;
esac
