#!/usr/bin/env bash
# Measures the published Linux app and fails past a ceiling:
#   - first frame: median time from process start to the window's first frame, over several
#     launches after one warm-up
#   - memory: median working set (RSS) five seconds after the first frame
# Each launch runs `codex-meter --perf-budget FILE`, which starts in demo mode, writes both numbers
# to FILE and quits. Run it under a display (xvfb-run) and a session bus (dbus-run-session).
# Prints a Markdown table to the log, and to the job summary when run in GitHub Actions.
#
#   linux/ci/perf-budgets.sh path/to/codex-meter
#
# Ceilings are set from measured CI runs (ubuntu-24.04 x64, Xvfb, cairo renderer) with headroom for
# runner noise. Raise one only with a reason in the PR that needs it; lower them as the app gets
# leaner.
set -euo pipefail

APP="${1:?path to the codex-meter executable required}"
LAUNCHES="${LAUNCHES:-7}"

# First measured 2026-11-17: 7 first frames between 516 and 551 ms (median 536 ms), working set
# 176.0-177.2 MB. 70 MB of that was Mesa's software OpenGL, which GTK loaded although the app drew
# with cairo (the job now sets GDK_DEBUG=gl-disable), and much of the start-up was the JIT
# compiling the GTK bindings (the release build is now ReadyToRun). Measured 2026-11-19: 211-233 ms
# (median 221 ms), 114.6-115.3 MB. The ceilings leave about 70% and 20% headroom over the worst
# launch: a shared runner's first frames swing more, in proportion, than its memory does.
MAX_FIRST_FRAME_MS="${MAX_FIRST_FRAME_MS:-400}"
MAX_WORKING_SET_MB="${MAX_WORKING_SET_MB:-140}"

report="$(mktemp)"
launch() {
  rm -f "$report"
  timeout 60 "$APP" --perf-budget "$report" >/dev/null 2>&1 || true
  [[ -s "$report" ]] || { echo "::error::The app wrote no performance report" >&2; exit 1; }
}
value() { awk -F= -v k="$1" '$1 == k { print $2 }' "$report"; }
median() { sort -n | sed -n "$(( (LAUNCHES + 1) / 2 ))p"; }

launch # warm-up: the first launch also fills the font and icon caches
frames=(); memory=()
for _ in $(seq "$LAUNCHES"); do
  launch
  frames+=("$(value first_frame_ms)")
  memory+=("$(value working_set_mb)")
done
frame_median="$(printf '%s\n' "${frames[@]}" | median)"
memory_median="$(printf '%s\n' "${memory[@]}" | median)"

exec > >(tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}")

failed=0
row() { # name value ceiling unit
  local verdict="pass"
  if awk -v v="$2" -v c="$3" 'BEGIN { exit !(v > c) }'; then verdict="**over**"; failed=1; fi
  echo "| $1 | $2 $4 | $3 $4 | $verdict |"
}
echo "### Linux performance budgets"
echo
echo "| Budget | Measured | Ceiling | Result |"
echo "|---|---:|---:|---|"
row "First frame (median of $LAUNCHES)" "$frame_median" "$MAX_FIRST_FRAME_MS" "ms"
row "Memory after launch (working set)" "$memory_median" "$MAX_WORKING_SET_MB" "MB"
echo
echo "First frames: $(printf '%s\n' "${frames[@]}" | sort -n | tr '\n' ' ')ms; working sets: $(printf '%s\n' "${memory[@]}" | sort -n | tr '\n' ' ')MB"

if (( failed )); then
  echo "::error::A performance budget was exceeded. See the table in the job summary." >&2
  exit 1
fi
