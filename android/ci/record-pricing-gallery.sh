#!/usr/bin/env bash
# Estimated value cards with the sourced figures from docs/pricing-sources.md, on a Samsung-sized
# phone (1440x3088 at 600 dpi). Seeds the tester's account through the debug-only
# UsagePaceDemoActivity, swapping the Claude plan and adding a Codex plan, and records each card.
set -euo pipefail

APK="${1:?phone APK required}"
OUT="${2:?gallery directory required}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb shell wm size 1440x3088
adb shell wm density 600
sleep 3
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell cmd uimode night no
W=1440
H=3088

shot() {
  sleep "${2:-5}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

# open_plan <design> <claude_open> <claude_plan> [codex_plan]
open_plan() {
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_WAKEUP
  local codex_args=""
  if [ -n "${4:-}" ]; then codex_args="--es codex_plan $4"; fi
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es claude_scenario tester \
    --es design "$1" --es claude_open "$2" --es claude_plan "$3" $codex_args >/dev/null
}

# Scrolls to the bottom of the history page, where the Estimated value card sits.
to_value_card() {
  for _ in 1 2 3 4 5 6 7 8; do scroll_down 1; done
  sleep 2
}

scroll_down() {
  adb shell input swipe $((W / 2)) $((H * 78 / 100)) $((W / 2)) $((H * 30 / 100)) 450
  sleep "${1:-2}"
}

# Taps the centre of the first view whose text or description is $1 (needs an accessibility tree).
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local bounds
  bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 -E "(text|content-desc)=\"$1\"" \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ') || true
  [ -n "$bounds" ] || { echo "No view with text $1"; return 1; }
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

for design in one_ui_9 liquid_glass; do
  for plan in Pro Max Ultra; do
    open_plan "$design" history "$plan"; to_value_card; shot "$design-claude-$plan-value-card" 2
  done
  for plan in plus prolite pro pro_500; do
    open_plan "$design" history_codex Pro "$plan"; to_value_card; shot "$design-codex-$plan-value-card" 2
  done
  open_plan "$design" history_cursor Pro; to_value_card; shot "$design-cursor-value-card" 2
done

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
