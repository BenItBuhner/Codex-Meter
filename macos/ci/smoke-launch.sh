#!/usr/bin/env bash
# Launches the built Debug app directly with several argument sets and reports whether it
# settles: memory after 8s and 20s, CPU, and the app's own frames on the main thread when
# it does not. Prints `healthy=true|false` (baseline variant) to $GITHUB_OUTPUT when set.
set -uo pipefail

APP_BIN="${1:?path to the app executable required}"
OUT_DIR="${2:-smoke}"
mkdir -p "$OUT_DIR"

BASE=(-ui-testing-reset-settings -ui-testing-demo -ui-testing-appearance light)
declare -a NAMES=(baseline no-commands no-menubar no-sidebar no-dashboard no-root)
healthy_baseline=false

for name in "${NAMES[@]}"; do
  extra=()
  [[ "$name" != baseline ]] && extra=("-mac-diag-$name")
  "$APP_BIN" "${BASE[@]}" ${extra[@]+"${extra[@]}"} >"$OUT_DIR/$name.stdout.log" 2>&1 &
  pid=$!
  sleep 8
  rss1=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')
  sleep 12
  rss2=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')
  cpu=$(ps -o %cpu= -p "$pid" 2>/dev/null | tr -d ' ')
  status=settled
  if [[ -z "$rss2" ]]; then
    status=exited
  elif (( rss2 > 400000 )) || (( rss2 - rss1 > 100000 )); then
    status=runaway
  fi
  printf '%-14s rss@8s=%8s KB rss@20s=%8s KB cpu=%6s%% %s\n' "$name" "${rss1:-?}" "${rss2:-?}" "${cpu:-?}" "$status" | tee -a "$OUT_DIR/summary.txt"
  if [[ "$status" != settled && -n "$rss2" ]]; then
    sample "$pid" 3 -file "$OUT_DIR/$name.sample.txt" >/dev/null 2>&1 || true
    if [[ -f "$OUT_DIR/$name.sample.txt" ]]; then
      echo "  top app frames ($name):" | tee -a "$OUT_DIR/summary.txt"
      grep -E "Codex Meter.debug.dylib|CodexMeterCore" "$OUT_DIR/$name.sample.txt" \
        | sed -E 's/^[ +!:|]*//; s/\(in Codex Meter.debug.dylib\).*//' \
        | sort | uniq -c | sort -rn | head -25 | tee -a "$OUT_DIR/summary.txt"
    fi
  fi
  if [[ "$name" == baseline ]]; then
    screencapture -x "$OUT_DIR/baseline-screen.png" 2>/dev/null || true
    [[ "$status" == settled ]] && healthy_baseline=true
  fi
  kill "$pid" 2>/dev/null; sleep 1; kill -9 "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
done

echo "baseline healthy: $healthy_baseline"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "healthy=$healthy_baseline" >> "$GITHUB_OUTPUT"
fi
