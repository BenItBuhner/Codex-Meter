#!/usr/bin/env bash
# Measures the Release iPhone app (in a simulator) or Mac app and fails past a ceiling:
#   - first frame: median time from process start to the first screen, over several launches
#     after one warm-up, with the file cache purged before each launch
#   - memory: physical footprint (what Xcode's memory gauge shows) five seconds after the first
#     frame of the last launch
# The app is put in demo mode first, so the dashboard shows sample usage. Launched with
# `-perf-budget`, it writes `codexmeter.first_frame_ms=N` to standard error (PerfBudgetProbe).
# Prints a Markdown table to the log, and to the job summary when run in GitHub Actions.
#
#   macos/ci/perf-budgets.sh ios "path/to/Codex Meter.app" "iPhone 17"
#   macos/ci/perf-budgets.sh mac "path/to/Codex Meter.app"
#
# Ceilings are set from measured CI runs (macos-26 runner) with headroom for runner noise. Raise
# one only with a reason in the PR that needs it; lower them as the app gets leaner.
set -euo pipefail

PLATFORM="${1:?ios or mac}"
APP="${2:?path to the .app required}"
DEVICE="${3:-}"
BUNDLE_ID="com.bukovinafilip.CodexMeter"
MODE_KEY="codex-meter.session-mode-v1"
LAUNCHES="${LAUNCHES:-7}"
MARKER="codexmeter.first_frame_ms="

case "$PLATFORM" in
  ios)
    [[ -n "$DEVICE" ]] || { echo "::error::A simulator name is required for ios" >&2; exit 1; }
    MAX_FIRST_FRAME_MS="${MAX_FIRST_FRAME_MS:-3000}"
    MAX_FOOTPRINT_MB="${MAX_FOOTPRINT_MB:-300}"
    TITLE="iPhone (simulator)"
    xcrun simctl boot "$DEVICE" 2>/dev/null || true
    xcrun simctl bootstatus "$DEVICE" -b >/dev/null
    xcrun simctl install "$DEVICE" "$APP"
    xcrun simctl spawn "$DEVICE" defaults write "$BUNDLE_ID" "$MODE_KEY" demo
    ;;
  mac)
    MAX_FIRST_FRAME_MS="${MAX_FIRST_FRAME_MS:-3000}"
    MAX_FOOTPRINT_MB="${MAX_FOOTPRINT_MB:-300}"
    TITLE="Mac"
    BIN="$APP/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"
    defaults write "$BUNDLE_ID" "$MODE_KEY" demo
    ;;
  *) echo "::error::Unknown platform $PLATFORM" >&2; exit 1 ;;
esac

log="$(mktemp)"
pid=""
stop() {
  if [[ "$PLATFORM" == ios ]]; then
    xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1 || true
  elif [[ -n "$pid" ]]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  pid=""
}
trap stop EXIT

# Starts the app and sets `frame` to its first-frame time once the marker appears. Not run in a
# subshell, so `pid` stays set for stop and vmmap.
frame=""
launch() {
  stop
  sync && sudo purge 2>/dev/null || true
  : > "$log"
  if [[ "$PLATFORM" == ios ]]; then
    pid="$(xcrun simctl launch --stderr="$log" "$DEVICE" "$BUNDLE_ID" -perf-budget | awk -F': ' '{ print $2 }')"
  else
    "$BIN" -perf-budget 2>"$log" >/dev/null &
    pid=$!
  fi
  for _ in $(seq 120); do
    if grep -q "^$MARKER" "$log"; then
      frame="$(grep "^$MARKER" "$log" | head -n 1 | cut -d= -f2)"
      return
    fi
    sleep 0.5
  done
  echo "::error::The app printed no first-frame time within 60 s" >&2
  cat "$log" >&2
  exit 1
}

launch # warm-up: the first launch also builds caches the later ones reuse
frames=()
for _ in $(seq "$LAUNCHES"); do
  launch
  [[ "$frame" =~ ^[0-9]+$ ]] || { echo "::error::Unreadable first-frame time: $frame" >&2; exit 1; }
  frames+=("$frame")
done
sorted="$(printf '%s\n' "${frames[@]}" | sort -n)"
frame_median="$(sed -n "$(( (LAUNCHES + 1) / 2 ))p" <<< "$sorted")"

# Memory once the last launch's dashboard has settled.
sleep 5
footprint="$(vmmap --summary "$pid" 2>/dev/null | awk '/^Physical footprint:/ { print $3; exit }')"
[[ -n "$footprint" ]] || { echo "::error::vmmap printed no physical footprint for $pid" >&2; exit 1; }
footprint_mb="$(awk -v f="$footprint" 'BEGIN {
  n = f + 0; u = substr(f, length(f))
  if (u == "K") n /= 1024; else if (u == "G") n *= 1024; else if (u != "M") n /= 1048576
  printf "%.1f", n }')"
stop

exec > >(tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}")

failed=0
row() { # name value ceiling unit
  local verdict="pass"
  if awk -v v="$2" -v c="$3" 'BEGIN { exit !(v > c) }'; then verdict="**over**"; failed=1; fi
  echo "| $1 | $2 $4 | $3 $4 | $verdict |"
}
echo "### $TITLE performance budgets"
echo
echo "| Budget | Measured | Ceiling | Result |"
echo "|---|---:|---:|---|"
row "First frame (median of $LAUNCHES)" "$frame_median" "$MAX_FIRST_FRAME_MS" "ms"
row "Memory after launch (physical footprint)" "$footprint_mb" "$MAX_FOOTPRINT_MB" "MB"
echo
echo "First frames: $(tr '\n' ' ' <<< "$sorted")ms"

if (( failed )); then
  echo "::error::A performance budget was exceeded. See the table in the job summary." >&2
  exit 1
fi
