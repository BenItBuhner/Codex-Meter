#!/usr/bin/env bash
# Screens for the alpha tester's Nov 10-14 reports, on a Samsung-sized phone (1440x3088 at 600
# dpi, One UI's 384 dp width). Seeds the tester's account (Claude Code Pro + Cursor, no Codex)
# through the debug-only UsagePaceDemoActivity and records each fixed screen.
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

open_tester() {
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_WAKEUP
  local open_args=""
  if [ -n "${2:-}" ]; then open_args="--es claude_open $2"; fi
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es claude_scenario tester \
    --es design "$1" $open_args >/dev/null
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

for design in one_ui_9 classic; do
  # 3. Claude Code tab: the idle 5-hour chart after a reset (no spike at "reset").
  open_tester "$design";                shot "$design-01-claude-dashboard" 8
  scroll_down;                          shot "$design-02-claude-dashboard-scrolled" 3
  # 1, 2 and 3. Claude Code history: idle 5-hour note, earlier windows, Claude Pro value card.
  open_tester "$design" history;        shot "$design-03-claude-history" 8
  scroll_down; scroll_down;             shot "$design-04-claude-history-scrolled" 3
  scroll_down; scroll_down; scroll_down; shot "$design-05-claude-history-value-card" 3
  # 2. Cursor history: Cursor's own included usage.
  open_tester "$design" history_cursor; shot "$design-06-cursor-history" 8
  scroll_down; scroll_down; scroll_down; scroll_down; shot "$design-07-cursor-history-value-card" 3
  # 4. Priority: new models say which provider they are for.
  open_tester "$design" priority;       shot "$design-08-priority" 8
  scroll_down;                          shot "$design-09-priority-scrolled" 3
  # 7. Reset credits settings without Codex.
  open_tester "$design" settings:reset_credits; shot "$design-10-reset-credits-settings" 6
  # 8. Now Bar settings, scrolled: the title area stays clear.
  open_tester "$design" settings:now_bar; shot "$design-11-now-bar-settings" 6
  scroll_down 1;                        shot "$design-12-now-bar-scrolled-1" 2
  scroll_down 1;                        shot "$design-13-now-bar-scrolled-2" 2
  scroll_down 1;                        shot "$design-14-now-bar-scrolled-3" 2
done

# 6. Now Bar for a Claude-only account: turn on Live monitor, then show the notification.
open_tester one_ui_9 settings:now_bar; sleep 6
# Tap the switch row by its summary: the first "Live monitor" in the tree is the category header.
if tap_text "Show remaining usage until the next[^\"]*"; then
  shot "one_ui_9-15-live-monitor-started" 4
  adb shell cmd statusbar expand-notifications || true
  shot "one_ui_9-16-live-monitor-notification" 4
  adb shell cmd statusbar collapse || true
fi

# 5. Update check against the live releases listing.
open_tester one_ui_9 settings:updates; sleep 6
if tap_text "Check for updates"; then shot "one_ui_9-17-update-check" 12; fi

# Liquid Glass versions of the history, value card and Priority.
open_tester liquid_glass history;  shot "liquid_glass-18-claude-history" 8
scroll_down; scroll_down; scroll_down; shot "liquid_glass-19-claude-history-value-card" 3
open_tester liquid_glass priority; shot "liquid_glass-20-priority" 8

adb shell cmd uimode night yes; sleep 2
open_tester one_ui_9 history;      shot "one_ui_9-dark-03-claude-history" 8
scroll_down; scroll_down; scroll_down; scroll_down; scroll_down; shot "one_ui_9-dark-05-claude-history-value-card" 3
open_tester one_ui_9 settings:now_bar; sleep 6
scroll_down 1; scroll_down 1;      shot "one_ui_9-dark-13-now-bar-scrolled-2" 2

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
