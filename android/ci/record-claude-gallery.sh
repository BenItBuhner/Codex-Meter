#!/usr/bin/env bash
# Installs the debug phone app on a running emulator, seeds Claude Code states through the
# debug-only UsagePaceDemoActivity and saves light and dark screenshots of each.
set -euo pipefail

APK="${1:?phone APK required}"
OUT="${2:?gallery directory required}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

shot() {
  sleep "${2:-5}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

open_claude() {
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_WAKEUP
  # adb shell joins its arguments into one string, so an empty value would vanish and leave
  # "--es claude_open" without an argument: only pass the extra when there is a page to open.
  local open_args=""
  if [ -n "${3:-}" ]; then open_args="--es claude_open $3"; fi
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es claude_scenario "$1" \
    --es design "${2:-classic}" $open_args >/dev/null
}

W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1)
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)

for theme in light dark; do
  if [[ "$theme" == dark ]]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  open_claude ultra;         shot "$theme-01-ultra" 8
  open_claude legacy_badge;  shot "$theme-02-saved-max20x-badge-renamed"
  open_claude sign_in_again; shot "$theme-03-sign-in-again"
  open_claude expired;       shot "$theme-04-reset-passed"
  open_claude ultra liquid_glass;         shot "$theme-05-glass-ultra" 8
  open_claude sign_in_again liquid_glass; shot "$theme-06-glass-sign-in-again"
  open_claude expired liquid_glass;       shot "$theme-07-glass-reset-passed"
  # Saved resets of three kinds: the reset screen to pick one, and Priority's Recent activity
  # (a Fable reset used, the Fable refill put down to it, a Weekly reset added).
  for design in liquid_glass classic material_3_expressive one_ui_9; do
    open_claude resets "$design" reset;    shot "$theme-08-$design-reset-choose" 8
    open_claude resets "$design" priority; shot "$theme-09-$design-priority-activity" 8
  done
  # Liquid Glass: swipe the first activity row left, then touch and hold the next one.
  open_claude resets liquid_glass priority; sleep 8
  ROW_Y=$((H * 27 / 100))
  adb shell input swipe $((W * 9 / 10)) $ROW_Y $((W / 10)) $ROW_Y 250
  shot "$theme-10-glass-priority-swiped" 3
  adb shell input swipe $((W / 2)) $ROW_Y $((W / 2)) $ROW_Y 1200
  shot "$theme-11-glass-priority-hold" 3
done

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
