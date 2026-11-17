#!/usr/bin/env bash
# Measures the Release iPhone app (in a simulator) or Mac app and fails past a ceiling:
#   - first frame: median time from process start to the first screen, over several launches
#     after one warm-up (warm launches: purging the file cache also evicts the system frameworks,
#     which made Mac launches swing from 3.7 to 12 s)
#   - memory: physical footprint (what Xcode's memory gauge shows) five seconds after the first
#     frame of the last launch
# The app is put in demo mode first, so the dashboard shows sample usage. Launched with
# `-perf-budget`, it logs `codexmeter.first_frame_ms=N` (PerfBudgetProbe): the Mac app's standard
# error carries it, and on the simulator it is read from a file in the app's tmp directory.
# Prints a Markdown table to the log, and to the job summary when run in GitHub Actions.
#
#   macos/ci/perf-budgets.sh ios "path/to/Codex Meter.app" "iPhone 17"
#   macos/ci/perf-budgets.sh mac "path/to/Codex Meter.app"
#
# Ceilings are set from measured CI runs (macos-26 runner) with headroom for runner noise. Raise
# one only with a reason in the PR that needs it; lower them as the app gets leaner.
set -Eeuo pipefail
trap 'echo "::error::perf-budgets.sh line $LINENO: \"$BASH_COMMAND\" exited $?" >&2' ERR

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
    MARKER_FILE="$(xcrun simctl get_app_container "$DEVICE" "$BUNDLE_ID" data)/tmp/codexmeter-first-frame.txt"
    ;;
  mac)
    # First measured 2026-11-17 (warm launches): medians 2917 and 3121 ms, single launches
    # 2.8-5.7 s on the VM runner; footprint 43.8-45.4 MB. The first frame is slow for a Mac app
    # and worth a profile; the ceiling keeps it from getting worse meanwhile.
    MAX_FIRST_FRAME_MS="${MAX_FIRST_FRAME_MS:-4500}"
    MAX_FOOTPRINT_MB="${MAX_FOOTPRINT_MB:-65}"
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
  : > "$log"
  if [[ "$PLATFORM" == ios ]]; then
    rm -f "$MARKER_FILE"
    pid="$(xcrun simctl launch --stderr="$log" "$DEVICE" "$BUNDLE_ID" -perf-budget | awk -F': ' '{ print $2 }')"
  else
    "$BIN" -perf-budget 2>"$log" >/dev/null &
    pid=$!
  fi
  for _ in $(seq 120); do
    [[ "$PLATFORM" == ios && -f "$MARKER_FILE" ]] && cat "$MARKER_FILE" >> "$log"
    # NSLog prefixes a timestamp and the process name, so match the marker anywhere.
    if grep -qE "$MARKER-?[0-9]+" "$log"; then
      frame="$(grep -oE "$MARKER-?[0-9]+" "$log" | head -n 1 | cut -d= -f2)"
      return
    fi
    sleep 0.5
  done
  echo "::error::The app printed no first-frame time within 60 s" >&2
  echo "pid: ${pid:-none}; app output:" >&2
  cat "$log" >&2
  ps -p "${pid:-0}" -o pid,stat,etime,command >&2 || echo "the app is not running" >&2
  if [[ "$PLATFORM" == ios ]]; then
    xcrun simctl io "$DEVICE" screenshot "${RUNNER_TEMP:-/tmp}/perf-budget-ios.png" >&2 || true
    xcrun simctl spawn "$DEVICE" log show --last 2m --style compact \
      --predicate 'process == "CodexMeter"' 2>&1 | tail -n 60 >&2 || true
  else
    screencapture -x "${RUNNER_TEMP:-/tmp}/perf-budget-mac.png" >&2 || true
  fi
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

echo "First frames: $(tr '\n' ' ' <<< "$sorted")ms"

# Runs a command for at most $1 seconds (macOS has no `timeout`), printing its output.
bounded() {
  local seconds="$1" out
  shift
  out="$(mktemp)"
  "$@" >"$out" 2>/dev/null &
  local cmd=$!
  for _ in $(seq $(( seconds * 2 ))); do
    kill -0 "$cmd" 2>/dev/null || break
    sleep 0.5
  done
  kill "$cmd" 2>/dev/null || true
  wait "$cmd" 2>/dev/null || true
  cat "$out"
}

# Memory once the last launch's dashboard has settled. vmmap can stall for minutes on a
# simulator process, so it gets a minute, then `footprint` (which reports the same number) does.
sleep 5
footprint="$(bounded 60 vmmap --summary "$pid" | awk '/^Physical footprint:/ { print $3; exit }')"
if [[ -z "$footprint" ]]; then
  echo "vmmap gave no footprint within 60 s; trying footprint" >&2
  footprint="$(bounded 60 footprint "$pid" \
    | awk 'tolower($0) ~ /footprint:/ { for (i = 1; i < NF; i++) if (tolower($i) ~ /footprint:/) {
        v = $(i + 1); u = $(i + 2); sub(/B$/, "", u); print v u; exit } }')"
fi
[[ -n "$footprint" ]] || { echo "::error::Neither vmmap nor footprint gave a physical footprint for $pid" >&2; exit 1; }
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
