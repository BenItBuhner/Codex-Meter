#!/usr/bin/env bash
# Records the phone clips for the 3.0.0 demo video on a Pixel 6 emulator (API 36).
# Each scene is its own screen recording in $OUT, plus a still and a UI dump per step.
set -uo pipefail
PKG=dev.bennett.codexmeter
HERE="$GITHUB_WORKSPACE/.github/release-demo"
OUT="$GITHUB_WORKSPACE/demo/phone"
DBG="$OUT/debug"
mkdir -p "$OUT" "$DBG"

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r "$RUNNER_TEMP/claude.apk"
adb install -r "$RUNNER_TEMP/chatgpt.apk"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell cmd connectivity airplane-mode enable || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
# Clean status bar: fixed clock, full battery and signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb shell cmd uimode night no

STEP=0
snap() { STEP=$((STEP + 1)); adb exec-out screencap -p > "$DBG/$(printf %03d $STEP)-$1.png"; }
dump() {
  timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$RUNNER_TEMP/ui.xml" >/dev/null 2>&1
  cp "$RUNNER_TEMP/ui.xml" "$DBG/$(printf %03d $STEP)-ui.xml" 2>/dev/null || true
}
# tap_text TEXT [INDEX]: taps a node by its text; returns 1 when it isn't on screen.
tap_text() {
  dump
  local xy; xy=$(python3 "$HERE/find.py" "$RUNNER_TEMP/ui.xml" "$1" "${2:-0}")
  if [[ -z "$xy" ]]; then echo "not found: $1"; return 1; fi
  adb shell input tap $xy; echo "tapped $1 at $xy"
}
seed() { # seed OPEN [extra am args...]
  local open=$1; shift
  adb shell am force-stop $PKG; sleep 1
  adb shell am start -W -n $PKG/dev.bennett.codexmeter.DebugSeedActivity --es open "$open" "$@" >/dev/null
  sleep 4
}
open_page() { # open_page OPEN [extra]: same as seed but keeps data
  local open=$1; shift
  adb shell am start -W -n $PKG/dev.bennett.codexmeter.DebugSeedActivity --es open "$open" --es reseed no "$@" >/dev/null
  sleep 3
}
scroll() { adb shell input swipe 540 1850 540 $((1850 - ${1:-900})) ${2:-900}; sleep "${3:-1.6}"; }
scroll_up() { adb shell input swipe 540 700 540 $((700 + ${1:-900})) ${2:-900}; sleep "${3:-1.6}"; }
REC=
rec() {
  adb shell "screenrecord --size 1080x2400 --bit-rate 12000000 /sdcard/$1.mp4" & REC=$!
  CLIP=$1; sleep 1.5
}
stop() {
  sleep 1.2
  adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 1.5
  adb pull /sdcard/$CLIP.mp4 "$OUT/$CLIP.mp4" >/dev/null && echo "recorded $CLIP"
  adb shell rm -f /sdcard/$CLIP.mp4
}

# 1. One UI 9 dashboard tour: Codex with reset credits and the monthly credit limit, then tabs.
seed main --es sessions off
snap dash-start; dump
rec 01-dashboard
sleep 2.5
scroll 700 1100 2; snap dash-scrolled-1
scroll 700 1100 2.2; snap dash-scrolled-2
scroll 700 1100 2.2; snap dash-scrolled-3
scroll_up 2100 900 1.6
tap_text "Claude Code" && sleep 2.6; snap claude
scroll 700 1100 2; scroll_up 700 900 1.2
tap_text "Cursor" && sleep 2.4; snap cursor
tap_text "Copilot" && sleep 2.4; snap copilot
tap_text "Grok" && sleep 2.2; snap grok
tap_text "Codex" && sleep 2; snap codex-again
stop

# 2. New providers: the searchable Add account picker.
open_page add_account
snap add-account; dump
rec 02-providers
sleep 2
scroll 900 1400 1.4
scroll 900 1400 1.4
scroll 900 1400 1.4
scroll_up 2700 900 1.2
if tap_text "Search"; then
  sleep 1; adb shell input text "Kiro"; sleep 2.2; snap search
  adb shell input keyevent KEYCODE_BACK; sleep 0.6
fi
stop

# 3. Settings cleanup: the regrouped main list, Now Bar, AI apps, Reset credits.
open_page root
snap settings-root; dump
rec 03-settings
sleep 2.4
scroll 800 1300 2
scroll_up 800 900 1.2
if tap_text "Now Bar"; then
  sleep 2.6; snap nowbar
  scroll 700 1200 1.8
  if tap_text "While using AI apps"; then sleep 2.6; snap ai-apps; adb shell input keyevent BACK; sleep 1.4; fi
  adb shell input keyevent BACK; sleep 1.4
fi
if tap_text "Reset credits"; then
  sleep 2.6; snap resets
  adb shell input keyevent BACK; sleep 1.4
fi
stop

# 4. Design: classic, then the switch to One UI 9 in Appearance, then One UI 9 in dark.
seed main --es design classic --es sessions off
rec 04-classic
sleep 3; scroll 700 1100 2; scroll_up 700 900 1.5
stop
open_page appearance
snap appearance; dump
rec 05-design-switch
sleep 2
tap_text "One UI 9 design"
sleep 6; snap after-switch
stop
seed main --es sessions off
adb shell cmd uimode night yes; sleep 2
adb shell am force-stop $PKG; open_page main
rec 06-oneui9-dark
sleep 3; scroll 700 1100 2; scroll_up 700 900 1.4
tap_text "Claude Code" && sleep 2.4
tap_text "Codex" && sleep 1.6
stop
adb shell cmd uimode night no; sleep 1

# 5. Live notification while an AI app is open (last: uiautomator would unbind the service).
seed none --es sessions on
adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow || true
adb shell settings put secure enabled_accessibility_services $PKG/dev.bennett.codexmeter.AppSessionService
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible true >/dev/null
adb shell input keyevent HOME; sleep 2
rec 07-live-notification
sleep 2
adb shell am start -n com.anthropic.claude/android.app.Activity >/dev/null; sleep 3.5; snap in-claude
adb shell cmd statusbar expand-notifications; sleep 3.5; snap claude-shade
adb shell cmd statusbar collapse; sleep 1.2
adb shell am start -n com.openai.chatgpt/android.app.Activity >/dev/null; sleep 3.5; snap in-chatgpt
adb shell cmd statusbar expand-notifications; sleep 3.5; snap chatgpt-shade
adb shell cmd statusbar collapse; sleep 1.2
adb shell input keyevent HOME; sleep 3; snap home-after
stop

adb logcat -d -v threadtime | grep -E "DebugSeed|CodexAppSession|FATAL|AndroidRuntime" > "$DBG/logcat.txt" || true
ls -la "$OUT"
