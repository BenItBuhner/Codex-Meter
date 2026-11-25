#!/usr/bin/env bash
# Picks an iPhone simulator and boots it in the background, so a job can build while it boots (a
# cold boot on a fresh runner takes 3-5 minutes). Prints the UDID, and writes `udid=` to
# $GITHUB_OUTPUT in Actions. Wait on it later with `xcrun simctl bootstatus <udid> -b`.
set -euo pipefail

UDID="$(xcrun simctl list devices available -j | jq -r '
  [.devices | to_entries[] | select(.key | test("iOS")) | .value[]
   | select(.name | startswith("iPhone"))] | first | .udid // empty')"
[[ -n "$UDID" ]] || { echo "::error::No iPhone simulator on this runner" >&2; exit 1; }
xcrun simctl list devices available | grep "$UDID" >&2
[[ -n "${GITHUB_OUTPUT:-}" ]] && echo "udid=$UDID" >> "$GITHUB_OUTPUT"
nohup bash -c "xcrun simctl boot '$UDID'; xcrun simctl bootstatus '$UDID' -b" \
  > "${RUNNER_TEMP:-/tmp}/simulator-boot.log" 2>&1 &
echo "$UDID"
