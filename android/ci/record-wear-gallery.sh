#!/usr/bin/env bash
# Installs the debug Wear app on a running Wear OS emulator, seeds demo data from
# wear-demo-prefs.py and saves one screenshot per screen and tile.
set -euo pipefail

APK="${1:?wear APK required}"
OUT="${2:?gallery directory required}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
adb install -r "$APK"

seed() {
  python3 "$HERE/wear-demo-prefs.py" "${1:-}" > /tmp/wear-prefs.xml
  adb shell am force-stop "$PKG"
  adb push /tmp/wear-prefs.xml /data/local/tmp/wear-prefs.xml >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/wear-prefs.xml shared_prefs/codex_meter_wear_v1.xml'"
}

shot() {
  sleep "${2:-4}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

open_app() {
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell am start -W -n "$PKG/.WearMainActivity" >/dev/null
}

scroll_down() { adb shell input swipe 225 380 225 120 300; }

seed
open_app;    shot 01-app-codex 12
scroll_down; shot 02-app-details
scroll_down; shot 03-app-providers
scroll_down; shot 04-app-providers-more

seed claude_code; open_app; shot 05-app-claude-code 12
seed cursor;      open_app; shot 06-app-cursor 12
seed copilot;     open_app; shot 07-app-copilot 12
seed kimi;        open_app; shot 08-app-signed-out 12

# Tiles follow the same choice; show them for Codex and Claude Code.
show_tiles() {
  local tag="$1" n=0
  for tile in UsageOverviewTileService FiveHourTileService WeeklyTileService \
      ResetCountdownTileService MonitorStatusTileService; do
    n=$((n + 1))
    local result index
    result="$(adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
      --es operation add-tile --ecn component "$PKG/$PKG.$tile")"
    index="$(sed -n 's/.*Index=\[\([0-9]*\)\].*/\1/p' <<<"$result")"
    echo "$tile: $result"
    if [[ -z "$index" ]]; then
      echo "Could not add $tile: $result"
      continue
    fi
    adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
      --es operation show-tile --ei index "$index"
    shot "$(printf '%s-tile-%d-%s' "$tag" "$n" "$tile")" 10
  done
  adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
    --es operation clear-tiles >/dev/null || true
}

seed codex;       show_tiles 10-codex
seed claude_code; show_tiles 20-claude-code

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
fi
