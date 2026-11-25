#!/usr/bin/env bash
# Cache key and data folders for simulators a CI job uses, so actions/cache can keep their data
# after one boot. A first boot on a fresh runner spends 2-6 minutes in "Data Migration"; a device
# whose data folder was cached after an earlier boot on the same runner image skips it.
#
#   macos/ci/simulator-data.sh <job name> <simulator name or UDID>...
#
# Writes `key=` and a multi-line `paths=` to $GITHUB_OUTPUT (and prints them). Names that match
# no available simulator are skipped. Shut the simulators down before saving the cache.
set -euo pipefail

JOB="${1:?job name required}"
shift
devices="$(xcrun simctl list devices available -j)"
udids=()
for want in "$@"; do
  [[ -n "$want" ]] || continue
  udid="$(jq -r --arg want "$want" '
    [.devices[][] | select(.name == $want or .udid == $want) | .udid] | first // empty' <<< "$devices")"
  [[ -n "$udid" ]] && udids+=("$udid") || echo "::warning::No simulator named $want" >&2
done
(( ${#udids[@]} )) || { echo "::error::No simulators to cache" >&2; exit 1; }

root="$HOME/Library/Developer/CoreSimulator/Devices"
key="simulator-${ImageOS:-macos}-${ImageVersion:-local}-$JOB-$(printf '%s\n' "${udids[@]}" | sort | shasum | cut -c1-12)-v1"
{
  echo "key=$key"
  echo "paths<<PATHS_END"
  for udid in "${udids[@]}"; do echo "$root/$udid/data"; done
  echo "PATHS_END"
} | tee -a "${GITHUB_OUTPUT:-/dev/null}"
