#!/usr/bin/env bash
# Drives the Codex Meter debug build (Scheduled reset) on a booted emulator, in demo mode, and
# captures stills + screenrecords. Every tap target comes from a uiautomator dump (uia.py); the
# only fixed coordinates are the generic scroll / pull gestures in the middle of the screen.
# Sections run as separate bash processes so one failing section does not abort the others.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$PWD/out}"
APK="${APK:?path to the debug apk}"
PKG=dev.bennett.codexmeter
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
export PATH="$SDK/platform-tools:$PATH"
mkdir -p "$OUT/stills" "$OUT/video" "$OUT/logs" "$OUT/uia"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/logs/drive.log"; }
devnow() { adb shell date +%s | tr -d '\r'; }
devclock() { adb shell date '+%H:%M:%S' | tr -d '\r'; }
set_device_clock() { # <epoch>; needs adb root. toybox accepts @UNIXTIME positionally or via -s.
  adb shell "date @$1" >/dev/null 2>&1 || true
  local got; got=$(devnow)
  if (( got < $1 - 5 || got > $1 + 5 )); then
    adb shell "date -s @$1" >/dev/null 2>&1 || true
    got=$(devnow)
  fi
  if (( got < $1 - 5 || got > $1 + 5 )); then
    log "set_device_clock: wanted $1, device reports $got"
    return 1
  fi
  log "device clock set to $got"
}

# --- capture primitives -----------------------------------------------------------------------
shot() { # name [settle-seconds]  -> still + uiautomator dump
  sleep "${2:-1.5}"
  adb exec-out screencap -p > "$OUT/stills/$1.png"
  adb shell uiautomator dump /sdcard/uia.xml >/dev/null 2>&1 \
    && adb exec-out cat /sdcard/uia.xml > "$OUT/uia/$1.xml" || true
  log "shot $1 (device $(devclock))"
}
fastshot() { sleep "${2:-1}"; adb exec-out screencap -p > "$OUT/stills/$1.png"; log "fastshot $1 (device $(devclock))"; }
rec_start() { # name [time-limit]
  adb shell "screenrecord --size 720x1600 --bit-rate 3500000 --time-limit ${2:-90} /sdcard/$1.mp4" &
  REC_PID=$!
  sleep 2
  log "rec_start $1 (device $(devclock))"
}
rec_stop() { # name
  adb shell pkill -l INT screenrecord >/dev/null 2>&1 || adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
  wait "$REC_PID" 2>/dev/null || true
  sleep 3
  adb pull "/sdcard/$1.mp4" "$OUT/video/$1.mp4" >/dev/null
  log "rec_stop $1 (device $(devclock))"
}

# --- interaction primitives --------------------------------------------------------------------
tap() { python3 "$HERE/uia.py" tap "$@" | tee -a "$OUT/logs/drive.log"; sleep 1.2; }
has() { python3 "$HERE/uia.py" find "$@" >/dev/null 2>&1; }
uitext() { python3 "$HERE/uia.py" text "$@" 2>/dev/null || true; }
dump_ui() { python3 "$HERE/uia.py" dump > "$OUT/uia/$1.txt" 2>&1 || true; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }
home() { adb shell input keyevent KEYCODE_HOME; sleep 1.5; }
scroll_down() { adb shell input swipe 540 1900 540 700 500; sleep 1.5; }
scroll_top() { for _ in 1 2 3 4 5; do adb shell input swipe 540 600 540 2000 300; done; sleep 1.5; }
# SwipeRefreshLayout only arms at scroll position 0, so return to the top first; start the pull
# inside the card column, below the expanded collapsing app bar (which otherwise consumes the
# drag). Demo refreshes are local, so they finish within the sleep.
pull_refresh() { scroll_top; adb shell input swipe 540 1300 540 2250 800; sleep 3; }
scroll_n() { local _; for _ in $(seq 1 "$1"); do scroll_down; done; }
# From the top, count scroll_downs until a node is on screen (max 8). Measured before a
# recording so the recorded pass can scroll without slow uiautomator dumps in between.
measure_scrolls_to() {
  local count=0
  scroll_top
  while (( count < 8 )); do
    if has "$@"; then echo "$count"; return 0; fi
    scroll_down; count=$((count + 1))
  done
  echo "$count"
}
launch() { adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true; sleep 3; }
stop_app() { adb shell am force-stop "$PKG"; sleep 1; }
relaunch() { stop_app; launch; }
install() { # <apk path>; -g grants POST_NOTIFICATIONS so outcome notifications can post
  adb push "$1" /data/local/tmp/demo.apk >/dev/null
  adb shell pm install -r -g --install-reason 4 /data/local/tmp/demo.apk >/dev/null
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  log "installed $(basename "$1")"
  sleep 2
}
night() { adb shell cmd uimode night "$1"; sleep 3; }
# A uiMode change recreates MainActivity with its scroll position restored under an expanded
# app bar, where scroll_top's swipes land in the app bar. Relaunching starts clean at the top.
retheme() { night "$1"; relaunch; expect_demo; }
scroll_to() { # bring a node on screen, scrolling down up to 6 times; fails if never found
  local _
  for _ in 0 1 2 3 4 5 6; do
    if has "$@"; then return 0; fi
    scroll_down
  done
  echo "scroll_to: not found: $*" >&2
  return 1
}
tap_scroll() { scroll_to "$@" && tap "$@"; }
expect() { # assert a node is visible; logs the whole hierarchy when it is not
  if ! has "$@"; then
    log "EXPECTED but missing: $*"
    python3 "$HERE/uia.py" dump | tee -a "$OUT/logs/drive.log" >/dev/null || true
    return 1
  fi
}
finish_onboarding() { # walk the onboarding steps when a fresh install shows them
  local _
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if has "Open Codex Meter"; then tap "Open Codex Meter"; sleep 2; return 0; fi
    if has "Not now"; then tap "Not now"; continue; fi
    if has --exact "Continue"; then tap --exact "Continue"; continue; fi
    if has "STEP "; then scroll_down; continue; fi
    return 0
  done
}
open_settings() { scroll_top; tap_scroll "Demo data"; sleep 1.5; }   # the demo banner opens Settings
# The demo banner sits at the top of the dashboard, and MainActivity is singleTask, so a
# relaunch keeps whatever scroll position the previous section left behind: scroll first.
expect_demo() { scroll_top; expect "Demo data"; }
ensure_demo() { # from a fresh install or the signed-out dashboard, end up on the demo dashboard
  launch
  finish_onboarding
  if has "Explore demo"; then tap_scroll "Explore demo"; sleep 3.5; fi
  expect_demo
}
reenter_demo() { # Leave demo, then Explore demo again: a fresh seed (2 credits, 38% / 64% used)
  ensure_demo
  open_settings
  tap_scroll "Leave demo"; sleep 3
  back; sleep 2
  tap_scroll "Explore demo"; sleep 3.5
  expect_demo
}
# The dashboard card's title is not clickable itself, so the tap lands on the card's listener.
open_reset_screen() { scroll_top; tap_scroll --exact "Reset credits"; sleep 2; expect "Credit expirations"; }
# The Scheduled reset section is the last thing on the reset screen, below the fold on a Pixel 7.
reset_screen_bottom() { sleep 2.5; scroll_down; }   # the sleep outlasts the "armed" toast

# The soft keyboard can cover dialog buttons; BACK only dismisses it when it is actually shown.
ime_shown() { adb shell dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q "mInputShown=true"; }
hide_ime() { if ime_shown; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi; }

# Notifications: detect through dumpsys on a phrase unique to each outcome's body text, so an
# earlier notification with the same title cannot satisfy a later wait; show through the shade.
notif_dump() { adb shell dumpsys notification --noredact > "$OUT/logs/notification-dump.txt" 2>&1 || true; }
notif_present() { notif_dump; grep -q -F "$1" "$OUT/logs/notification-dump.txt"; }
wait_notif() { # <body phrase> <timeout seconds>; prints elapsed seconds, fails on timeout
  local start; start=$(date +%s)
  while (( $(date +%s) - start < $2 )); do
    if notif_present "$1"; then echo $(( $(date +%s) - start )); return 0; fi
    sleep 5
  done
  echo "wait_notif timed out: $1" >&2
  return 1
}
notif_text() { grep -F -B6 -A6 "$1" "$OUT/logs/notification-dump.txt" | grep -E "android.title=|android.text=|android.bigText=" | sort -u | head -3 || true; }
shade_open() { adb shell cmd statusbar expand-notifications; sleep 2.5; }
shade_close() { adb shell cmd statusbar collapse; sleep 1.5; }
clear_notifs() { # SystemUI's own Clear all button; falls back to leaving them (waits key on body text)
  shade_open
  if has "Clear all"; then tap "Clear all"; sleep 1.5; fi
  shade_close
}

# Clean status bar for stills: SystemUI demo mode (fixed clock, full battery, wifi).
sysui_demo_on() {
  adb shell settings put global sysui_demo_allowed 1
  local b="am broadcast -a com.android.systemui.demo -e command"
  adb shell "$b enter" >/dev/null
  adb shell "$b clock -e hhmm 1000" >/dev/null
  adb shell "$b battery -e level 100 -e plugged false" >/dev/null
  adb shell "$b network -e wifi show -e level 4 -e fully true" >/dev/null
  adb shell "$b network -e mobile hide" >/dev/null
  adb shell "$b notifications -e visible false" >/dev/null
  adb shell "$b status -e bluetooth hide -e alarm hide -e volume hide -e location hide" >/dev/null
  sleep 1
}
sysui_demo_off() { adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null; sleep 1; }

# --- Scheduled reset flows ------------------------------------------------------------------------
# Opens the trigger chooser from the dashboard tile's own "Schedule reset" button, which sits
# beside "Use 1 reset". Every arming in this script starts there.
open_scheduler() { scroll_top; tap_scroll --exact "Schedule reset"; expect "At a date and time"; }
# The same chooser from the reset screen's row (its summary text is unique on that screen).
open_scheduler_reset_screen() { tap "Use a credit automatically"; expect "At a date and time"; }
arm_threshold() { # <five|weekly> <preset percent | custom:NN> <still name for the confirmation>
  open_scheduler
  if [[ "$1" == five ]]; then tap "When 5-hour reaches"; else tap "When weekly reaches"; fi
  if [[ "$2" == custom:* ]]; then
    tap "Custom"
    type_percent "${2#custom:}"
  else
    tap --exact "$2% remaining"
  fi
  expect "Schedule a Codex reset?"
  shot "$3" 1
  dump_ui "$3"
  tap --id button1                        # Schedule
  sleep 1.5
}
type_percent() { # the custom-value dialog: focus its (unnamed) EditText, type, dismiss IME, Next
  tap --class EditText
  adb shell input text "$1"; sleep 0.5
  hide_ime
  tap --id button1
}
# "When 5-hour reaches (now 62% remaining)" -> 62; empty when the window is unavailable.
current_remaining_from_title() { uitext "reaches (now" | sed -n 's/.*(now \([0-9]*\)% remaining).*/\1/p'; }
# Types HH and MM into the platform TimePicker's text-input mode (24-hour clock set in setup).
set_time_field() { # <hour|minute> <value>
  tap --id "input_$1"
  adb shell input keyevent KEYCODE_MOVE_END
  adb shell input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
  adb shell input text "$2"; sleep 0.5
}
device_hhmm_at() { # <epoch> -> HH MM in the device's time zone
  local tz; tz=$(adb shell getprop persist.sys.timezone | tr -d '\r')
  python3 - "$1" "${tz:-UTC}" <<'PY'
import sys, datetime, zoneinfo
at = datetime.datetime.fromtimestamp(int(sys.argv[1]), zoneinfo.ZoneInfo(sys.argv[2]))
print(at.strftime("%H %M"))
PY
}
arm_datetime() { # <minutes ahead> <condition label or "No condition"> <still name for confirmation>
  local now target hh mm
  now=$(devnow)
  target=$(( (now / 60 + $1 + 1) * 60 ))          # next full minute at least $1 minutes out
  read -r hh mm < <(device_hhmm_at "$target")
  ARMED_TARGET_EPOCH=$target
  log "arming date/time trigger for device epoch $target ($hh:$mm); device now $(devclock)"
  open_scheduler
  tap "At a date and time"
  expect --id button1
  tap --id button1                        # DatePicker OK (today)
  expect --id toggle_mode
  tap --id toggle_mode                    # TimePicker: switch to text input
  set_time_field hour "$hh"
  set_time_field minute "$mm"
  hide_ime
  tap --id button1                        # TimePicker OK
  expect "Condition"
  tap --exact "$2"
  expect "Schedule a Codex reset?"
  shot "$3" 1
  dump_ui "$3"
  tap --id button1                        # Schedule
  sleep 1.5
  ARMED_AT_EPOCH=$(devnow)
  log "armed at device epoch $ARMED_AT_EPOCH for $target"
}
use_one_reset() { # from the reset screen; the activity finishes itself on success
  tap --exact "Use 1 reset"
  expect "Use one Codex reset?"
  tap --id button1
  sleep 3
}

# --- sections ------------------------------------------------------------------------------------
section_setup() {
  adb wait-for-device
  adb shell settings put global window_animation_scale 1
  adb shell settings put global transition_animation_scale 1
  adb shell settings put global animator_duration_scale 1
  adb shell settings put system screen_off_timeout 2147483647
  adb shell settings put system time_12_24 24
  adb shell svc power stayon true
  adb shell locksettings set-disabled true || true
  adb shell wm dismiss-keyguard || true
  night no
  install "$APK"
  adb shell appops get "$PKG" SCHEDULE_EXACT_ALARM | tee "$OUT/logs/appops-exact-alarm-initial.txt" || true
  sysui_demo_on
  home
  shot sr-setup-00-home 2
  ensure_demo
  shot sr-setup-01-demo-dashboard 2
}

# The tile with both actions side by side, light and dark, before anything is armed.
section_tile_stills() {
  reenter_demo
  scroll_to --exact "Schedule reset"
  shot sr-tile-buttons-light 1.5
  dump_ui sr-tile-buttons-light
  retheme yes
  scroll_to --exact "Schedule reset"
  shot sr-tile-buttons-dark 1.5
  dump_ui sr-tile-buttons-dark
  retheme no
}

# (1) Threshold schedule on the 5-hour window, one continuous recording that starts on the
# dashboard tile's "Schedule reset" button. A fresh demo seed sits at 62% remaining and every
# pull-to-refresh nudges usage up 1%, so the custom threshold is set two points below whatever
# the picker reports as current: the second pull crosses it.
section_threshold_video() {
  reenter_demo
  local scrolls; scrolls=$(measure_scrolls_to --exact "Schedule reset")
  log "reset credits tile is $scrolls scroll(s) below the top"
  shot sr-threshold-00-tile-before 1.5
  dump_ui sr-threshold-tile-before
  rec_start sr-threshold-flow 150
  tap --exact "Schedule reset"
  fastshot sr-threshold-01-trigger-menu 0.8
  tap "When 5-hour reaches"
  local remaining threshold
  remaining=$(current_remaining_from_title)
  threshold=$(( ${remaining:-62} - 2 ))
  log "picker reports ${remaining:-?}% remaining; arming custom threshold ${threshold}%"
  echo "remaining_at_arm=${remaining:-unknown} threshold=$threshold scrolls_to_card=$scrolls" > "$OUT/logs/threshold-choice.txt"
  fastshot sr-threshold-02-threshold-menu 0.6
  tap "Custom"
  type_percent "$threshold"
  fastshot sr-threshold-03-confirmation 1.2
  tap --id button1
  sleep 2.5                                  # the "armed" toast
  scroll_n "$scrolls"                        # the tile is the last card, so this cannot overshoot it
  fastshot sr-threshold-04-tile-armed 1
  pull_refresh
  scroll_n "$scrolls"
  fastshot sr-threshold-05-after-pull-1 1
  pull_refresh
  sleep 1.5
  scroll_n "$scrolls"
  fastshot sr-threshold-06-after-pull-2-fired 1
  shade_open
  fastshot sr-threshold-07-fired-notification 1.5
  shade_close
  fastshot sr-threshold-08-tile-disarmed-one-credit 1.5
  rec_stop sr-threshold-flow
  wait_notif "so 1 reset credit was used" 30 >/dev/null || log "threshold notification not found"
  notif_text "so 1 reset credit was used" | tee "$OUT/logs/threshold-notification.txt"
  shot sr-threshold-09-dashboard-after 1.5
  dump_ui sr-threshold-dashboard-after
  open_reset_screen
  scroll_down
  shot sr-threshold-10-reset-screen-last-run 1.5
  dump_ui sr-threshold-reset-screen-after
  back
  clear_notifs
}

# (4) Cancel flow plus light/dark stills of the armed tile (threshold 5%, far from firing).
# Arming starts on the tile; Cancel is shown both on the tile and on the reset screen.
section_cancel_theme() {
  reenter_demo
  arm_threshold five 5 sr-cancel-00-confirmation-5-percent
  sleep 2.5                                  # the "armed" toast
  scroll_to "Scheduled"
  shot sr-armed-card-light 1.5
  dump_ui sr-armed-card-light
  open_reset_screen
  reset_screen_bottom
  shot sr-armed-reset-screen-light 1
  dump_ui sr-armed-reset-screen-light
  back
  retheme yes
  scroll_to "Scheduled"
  shot sr-armed-card-dark 1.5
  dump_ui sr-armed-card-dark
  open_reset_screen
  reset_screen_bottom
  shot sr-armed-reset-screen-dark 1
  back
  retheme no
  scroll_to "Scheduled"
  shot sr-cancel-01-tile-armed 1
  tap --exact Cancel
  sleep 2.5                                  # the "cancelled" toast
  shot sr-cancel-02-tile-after-cancel 1
  dump_ui sr-cancel-dashboard-after
  arm_threshold five 5 sr-cancel-03-confirmation-again
  sleep 2.5
  open_reset_screen
  reset_screen_bottom
  shot sr-cancel-04-reset-screen-armed 1
  tap --exact Cancel
  sleep 2.5
  shot sr-cancel-05-reset-screen-after-cancel 1
  dump_ui sr-cancel-reset-after
  back
  expect_demo
  scroll_to --exact "Schedule reset"
  shot sr-cancel-06-tile-after-cancel-from-reset-screen 1
}

# (2) Date/time schedule a couple of minutes out, with Alarms & reminders left at the API 35
# default (denied), so the inexact while-idle fallback is what fires. Real status-bar clock.
section_datetime() {
  reenter_demo
  sysui_demo_off
  arm_datetime 2 "No condition" sr-datetime-00-confirmation
  sleep 2.5
  scroll_to "Scheduled"
  shot sr-datetime-02-dashboard-armed 1
  dump_ui sr-datetime-dashboard-armed
  open_reset_screen
  reset_screen_bottom
  shot sr-datetime-01-reset-screen-armed-inexact 1
  dump_ui sr-datetime-armed
  back
  local elapsed
  elapsed=$(wait_notif "used at the scheduled time" 900)
  local fired; fired=$(devnow)
  log "date/time trigger fired: target $ARMED_TARGET_EPOCH, armed $ARMED_AT_EPOCH, notification seen at $fired (+${elapsed}s after arming, $((fired - ARMED_TARGET_EPOCH))s after the scheduled minute)"
  echo "target=$ARMED_TARGET_EPOCH armed=$ARMED_AT_EPOCH seen=$fired late_by_s=$((fired - ARMED_TARGET_EPOCH)) poll_step_s=5" > "$OUT/logs/datetime-timing.txt"
  notif_text "used at the scheduled time" | tee -a "$OUT/logs/datetime-timing.txt"
  shot sr-datetime-03-dashboard-after-fire 1
  dump_ui sr-datetime-dashboard-after
  shade_open
  shot sr-datetime-04-fired-notification 1
  shade_close
  open_reset_screen
  expect "Last run"
  shot sr-datetime-05-reset-screen-last-run 1.5
  dump_ui sr-datetime-last-run
  back
  clear_notifs
  sysui_demo_on
}

# (3a) Skipped: no credit. Exact alarms allowed (as if the user tapped Allow exact timing), arm
# with one credit left, then spend that credit by hand before the alarm fires. The dashboard
# card stays visible at zero credits while a schedule is armed, which is the route back in.
section_skip_no_credit() {
  reenter_demo
  sysui_demo_off
  adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow
  adb shell appops get "$PKG" SCHEDULE_EXACT_ALARM | tee "$OUT/logs/appops-exact-alarm-allowed.txt"
  open_reset_screen
  use_one_reset                              # 2 -> 1; the reset screen finishes itself
  expect_demo
  scroll_to --exact "Schedule reset"
  expect "1 reset available"
  arm_datetime 3 "No condition" sr-skip-nocredit-00-confirmation
  sleep 2.5
  open_reset_screen
  reset_screen_bottom
  shot sr-skip-nocredit-01-armed-exact-one-credit 1
  dump_ui sr-skip-nocredit-armed
  scroll_top
  use_one_reset                              # 1 -> 0 while the schedule is armed
  expect_demo
  scroll_to "Scheduled"
  shot sr-skip-nocredit-02-dashboard-armed-no-credits 1.5
  dump_ui sr-skip-nocredit-dashboard-zero
  open_reset_screen
  expect "No resets available"
  reset_screen_bottom
  shot sr-skip-nocredit-02-armed-no-credits 1
  dump_ui sr-skip-nocredit-zero
  local elapsed; elapsed=$(wait_notif "No reset credit was available" 600)
  local fired; fired=$(devnow)
  log "no-credit skip fired: target $ARMED_TARGET_EPOCH, seen at $fired (+${elapsed}s, $((fired - ARMED_TARGET_EPOCH))s after the scheduled minute)"
  echo "target=$ARMED_TARGET_EPOCH armed=$ARMED_AT_EPOCH seen=$fired late_by_s=$((fired - ARMED_TARGET_EPOCH)) poll_step_s=5" > "$OUT/logs/skip-nocredit-timing.txt"
  notif_text "No reset credit was available" | tee -a "$OUT/logs/skip-nocredit-timing.txt"
  scroll_down
  shot sr-skip-nocredit-03-reset-screen-last-run 1.5
  dump_ui sr-skip-nocredit-last-run
  shade_open
  shot sr-skip-nocredit-04-notification 1
  shade_close
  back
  expect_demo
  shot sr-skip-nocredit-05-dashboard-card-gone 1.5     # zero credits and nothing armed: card hidden again
  dump_ui sr-skip-nocredit-dashboard-after
  clear_notifs
  sysui_demo_on
}

# (3b) Skipped: natural reset imminent. The demo 5-hour window resets 2h17m after entering the
# demo, so the device clock is moved 2h05m ahead (root, auto time off) and a threshold that is
# already met is armed; the next pull evaluates it 12 minutes before the natural reset.
section_skip_imminent() {
  reenter_demo
  sysui_demo_off
  adb root >/dev/null 2>&1 || true; adb wait-for-device; sleep 3
  adb shell settings put global auto_time 0
  local before jump
  before=$(devnow)
  jump=$(( before + 2 * 3600 + 5 * 60 ))
  set_device_clock "$jump" | tee "$OUT/logs/clock-jump.txt"
  sleep 2
  log "clock moved from $before to $(devnow) (+2h05m)"
  relaunch
  expect_demo
  arm_threshold five custom:99 sr-skip-imminent-00-confirmation-already-met
  sleep 2.5
  scroll_to "Scheduled"
  shot sr-skip-imminent-02-dashboard-armed 1
  dump_ui sr-skip-imminent-dashboard-armed
  open_reset_screen
  reset_screen_bottom
  shot sr-skip-imminent-01-armed 1
  dump_ui sr-skip-imminent-armed
  back
  pull_refresh
  sleep 2
  wait_notif "resets on its own" 60 >/dev/null
  notif_text "resets on its own" | tee "$OUT/logs/skip-imminent-notification.txt"
  shot sr-skip-imminent-03-dashboard-after 1
  shade_open
  shot sr-skip-imminent-04-notification 1
  shade_close
  open_reset_screen
  shot sr-skip-imminent-05-reset-screen-last-run 1.5
  dump_ui sr-skip-imminent-last-run
  back
  clear_notifs
  local elapsed; elapsed=$(( $(devnow) - jump ))
  set_device_clock "$(( before + elapsed ))" || true
  adb shell settings put global auto_time 1
  log "clock restored to $(devnow)"
  adb unroot >/dev/null 2>&1 || true; adb wait-for-device; sleep 3
  sysui_demo_on
}

section_finish() {
  night no
  reenter_demo
  shot sr-finish-00-demo-dashboard 2
}

# --- runner ----------------------------------------------------------------------------------------
if [[ $# -eq 1 ]]; then
  "section_$1"
  exit 0
fi

for s in setup tile_stills threshold_video cancel_theme datetime skip_no_credit skip_imminent finish; do
  log "=== section $s ==="
  if ! bash -x "$0" "$s" >>"$OUT/logs/section-$s.log" 2>&1; then
    log "SECTION $s FAILED (see logs/section-$s.log)"
    echo "$s" >> "$OUT/logs/failures.txt"
    adb exec-out screencap -p > "$OUT/stills/zz-failure-$s.png" || true
    python3 "$HERE/uia.py" dump > "$OUT/uia/zz-failure-$s.txt" 2>&1 || true
    adb shell cmd statusbar collapse >/dev/null 2>&1 || true
    adb shell cmd uimode night no || true
    adb shell am force-stop "$PKG" || true
    adb shell input keyevent KEYCODE_HOME || true
  fi
done
log "done"
ls -la "$OUT/stills" "$OUT/video" | tee -a "$OUT/logs/drive.log"
