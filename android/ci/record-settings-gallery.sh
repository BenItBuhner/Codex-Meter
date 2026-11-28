#!/usr/bin/env bash
# Every Settings page in every design, on a Samsung-sized phone (1440x3088 at 600 dpi), light
# and dark. Seeds Claude Code Pro, Cursor and Codex Pro through the debug-only
# UsagePaceDemoActivity, so Reset credits shows both providers' sections.
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
W=1440
H=3088

shot() {
  sleep "${2:-5}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

open_page() {
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_WAKEUP
  # Reset credits opens with running-low use on and one use held back for a refill (#214).
  local extra=()
  [ "$2" = reset_credits ] && extra=(--ez reset_refill_wait true)
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es claude_scenario tester \
    --es codex_plan pro --es design "$1" --es claude_open "settings:$2" "${extra[@]}" >/dev/null
}

scroll_down() {
  adb shell input swipe $((W / 2)) $((H * 78 / 100)) $((W / 2)) $((H * 30 / 100)) 450
  sleep "${1:-2}"
}

PAGES="root notifications now_bar reset_credits refresh_usage appearance privacy"

for mode in no yes; do
  adb shell cmd uimode night "$mode"; sleep 2
  theme=$([ "$mode" = yes ] && echo dark || echo light)
  for design in one_ui_9 liquid_glass material_3_expressive classic; do
    # Dark mode for the two designs testers use most; light for all four.
    if [ "$theme" = dark ] && [ "$design" != one_ui_9 ] && [ "$design" != liquid_glass ]; then
      continue
    fi
    n=1
    for page in $PAGES; do
      open_page "$design" "$page"
      shot "$design-$theme-$(printf %02d $n)-$page" 7
      scroll_down; shot "$design-$theme-$(printf %02d $n)-$page-scrolled" 2
      n=$((n + 1))
    done
  done
done

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
