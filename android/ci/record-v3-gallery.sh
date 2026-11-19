#!/usr/bin/env bash
# The v3 polish pass across every design, on a Samsung-sized phone (1440x3088 at 600 dpi): the
# dashboard's real usage chart, Priority's one dismiss model (New models as swipeable rows), no
# subheader under any page title, and the Material 3 Expressive ports of the pages that used to
# open in One UI (history, reset credits, updates, About, Dashboard tabs, widget settings).
# Seeds the alpha tester's account (Claude Code Pro + Cursor) through the debug-only
# UsagePaceDemoActivity, saves screenshots and short screen recordings, and fails on any crash.
set -euo pipefail

APK="${1:?phone APK required}"
OUT="${2:?gallery directory required}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT/video"

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
adb logcat -c || true
W=1440
H=3088

# Closes a system "isn't responding" dialog (the emulator's launcher can stall while the app
# starts), which hide_error_dialogs no longer hides, by waiting on it.
dismiss_anr() {
  adb shell dumpsys window | grep -q "Application Not Responding" || return 0
  echo "::warning::A system isn't-responding dialog was on screen; waiting on it"
  tap_text Wait || adb shell input keyevent KEYCODE_BACK
  sleep 1
}

shot() {
  sleep "${2:-5}"
  dismiss_anr
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

# open <design> <theme> [claude_open] [extra am args...]
open() {
  local design="$1" theme="$2" target="${3:-}"
  shift 3 || shift $#
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_WAKEUP
  local args=(--es claude_scenario tester --es design "$design" --es theme "$theme")
  if [ -n "$target" ]; then args+=(--es claude_open "$target"); fi
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" "${args[@]}" "$@" >/dev/null
}

# screen <design> <theme> <ActivityClass>: an activity on the already seeded account.
screen() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design "$1" --es theme "$2" \
    --es open_activity "$3" >/dev/null
}

scroll_down() {
  adb shell input swipe $((W / 2)) $((H * 78 / 100)) $((W / 2)) $((H * 30 / 100)) 450
  sleep "${1:-2}"
}

# Bounds "l t r b" of the first view whose text or description matches $1.
bounds_of() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 -E "(text|content-desc)=\"$1\"" \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' '
}

# Swipes the row holding text $1 to the left, the one way to dismiss a Priority row.
swipe_away() {
  local b
  b=$(bounds_of "$1") || true
  [ -n "$b" ] || { echo "No row with text $1"; return 1; }
  set -- $b
  local y=$(( ($2 + $4) / 2 ))
  adb shell input swipe $((W * 85 / 100)) "$y" $((W * 10 / 100)) "$y" 260
}

record() {
  # Half size keeps the encoder within what the emulator can do; 3088 tall is past most limits.
  adb shell screenrecord --size 720x1544 --bit-rate 6000000 --time-limit "$2" "/sdcard/$1.mp4" &
  REC=$!
  sleep 1
}

stop_record() {
  wait "$REC" || true
  sleep 1
  adb pull "/sdcard/$1.mp4" "$OUT/video/$1.mp4" >/dev/null || echo "No video $1"
  adb shell rm -f "/sdcard/$1.mp4" || true
}

# Taps the first view whose text or description matches $1; fails when there is none (Liquid
# Glass draws its own controls, so its buttons aren't in the view tree).
tap_text() {
  local b
  b=$(bounds_of "$1") || true
  [ -n "$b" ] || return 1
  set -- $b
  # A view below the fold is in the tree too; tapping it would hit whatever is on screen there.
  (( ($2 + $4) / 2 < H * 93 / 100 )) || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# Taps $1, scrolling down up to three times to bring it on screen.
tap_text_scrolling() {
  local i
  for i in 0 1 2 3; do
    tap_text "$1" && return 0
    (( i < 3 )) && scroll_down
  done
  return 1
}

# Whether demo mode is on: its tabs carry the "Leave demo" button.
in_demo() { [ -n "$(bounds_of "Leave demo")" ]; }

# Logs what is on screen and the stored setup and demo flags, for a demo shot that looks wrong.
demo_state() {
  echo "-- $1: $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -s ' ')"
  adb shell run-as "$PKG" cat shared_prefs/codex_meter_settings_v1.xml 2>/dev/null \
    | grep -E 'onboarding|demo' | sed 's/^/   /' || true
  adb logcat -d -t 400 ActivityTaskManager:I '*:S' | grep -E 'START|Displayed' | tail -4 | sed 's/^/   /' || true
}

# Demo mode from a fresh install: setup's Explore demo, the sample dashboards with their notice,
# Leave demo back to the sign-in screen, and Explore demo from there.
run_demo() {
  local d="$1" p="demo-$1"
  adb shell pm clear "$PKG" >/dev/null
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design "$d" --es theme light \
    --es open_activity OnboardingActivity >/dev/null
  shot "$p-01-setup" 6
  if tap_text_scrolling "Explore demo"; then
    sleep 8
    [ "$d" = liquid_glass ] || in_demo || echo "::warning::Explore demo on the $d setup screen did not start demo mode"
  fi
  if ! in_demo; then
    # Clear the task: a plain start only brings setup's task, which this activity began, to the
    # front without running it again, so the shortcut never applied.
    adb shell am start -W --activity-clear-task -n "$PKG/.UsagePaceDemoActivity" --ez enter_demo true >/dev/null
    sleep 6
  fi
  shot "$p-02-codex" 6
  demo_state "$p-02-codex"
  scroll_down;                       shot "$p-03-codex-scrolled" 3
  adb shell am start -W -S -n "$PKG/.MainActivity" --es provider claude_code >/dev/null
  shot "$p-04-claude-code" 6
  demo_state "$p-04-claude-code"
  adb shell am start -W -S -n "$PKG/.MainActivity" --es provider cursor >/dev/null
  shot "$p-05-cursor" 6
  if ! tap_text_scrolling "Leave demo"; then
    adb shell am start -W --activity-clear-task -n "$PKG/.UsagePaceDemoActivity" --ez leave_demo true >/dev/null
  fi
  shot "$p-06-left-demo" 6
  if tap_text_scrolling "Explore demo"; then shot "$p-07-demo-again" 8; fi
}

run_design() {
  local d="$1" t="$2" p="$1-$2"
  # Dashboard: title alone (no subheader), the real Usage history chart instead of bars.
  open "$d" "$t" "";                 shot "$p-01-dashboard" 8
  scroll_down;                       shot "$p-02-dashboard-scrolled" 3
  scroll_down;                       shot "$p-03-dashboard-bottom" 3
  # Priority: one dismiss model, New models as two-line rows with the swipe/hold footnote.
  open "$d" "$t" priority;           shot "$p-04-priority" 8
  scroll_down;                       shot "$p-05-priority-scrolled" 3
  # History page, reset credits and settings.
  open "$d" "$t" history;            shot "$p-06-history" 8
  scroll_down; scroll_down;          shot "$p-07-history-scrolled" 3
  open "$d" "$t" reset;              shot "$p-08-reset-credits" 7
  open "$d" "$t" settings:root;      shot "$p-09-settings" 7
  open "$d" "$t" settings:appearance; shot "$p-10-settings-appearance" 6
  screen "$d" "$t" UpdateActivity;   shot "$p-11-updates" 8
  screen "$d" "$t" AboutActivity;    shot "$p-12-about" 6
  screen "$d" "$t" ProvidersActivity; shot "$p-13-dashboard-tabs" 6
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design "$d" --es theme "$t" \
    --es open_widget_metric five_hour >/dev/null
  shot "$p-14-widget-settings" 7
}

for design in liquid_glass one_ui_9 material_3_expressive; do
  run_demo "$design"
done
# The tester scenes below start from a clean install, not from the demo.
adb shell pm clear "$PKG" >/dev/null
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

for design in material_3_expressive liquid_glass one_ui_9 classic; do
  run_design "$design" light
done
for design in material_3_expressive liquid_glass one_ui_9; do
  run_design "$design" dark
done

# Videos: the dashboard scrolled top to bottom, then Priority with a New models row swiped away.
for design in material_3_expressive liquid_glass one_ui_9 classic; do
  open "$design" light ""; sleep 8
  record "$design-dashboard" 14
  scroll_down 2; scroll_down 2; scroll_down 2
  adb shell input swipe $((W / 2)) $((H * 30 / 100)) $((W / 2)) $((H * 80 / 100)) 450; sleep 2
  stop_record "$design-dashboard"

  open "$design" light priority; sleep 8
  record "$design-priority-dismiss" 14
  sleep 2
  swipe_away ".*For Claude.*" || swipe_away ".*Claude.*" || true
  sleep 3; scroll_down 2
  stop_record "$design-priority-dismiss"
  shot "$design-light-15-priority-after-swipe" 1
done

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
