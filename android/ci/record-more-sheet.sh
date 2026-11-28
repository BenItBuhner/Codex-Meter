#!/usr/bin/env bash
# More on a real phone (Liquid Glass, five or more providers): More is iOS's pushed list. Open it from
# the tab bar, push a provider from it, and go back to the list each way a person does (the bar's
# Back, Android's back key, an edge swipe, tapping More again), screenshotting each step with
# pointer location on so the taps show. Back from a pushed provider must never leave the app.
set -euo pipefail
APK="${1:?phone APK required}"
OUT="${2:?output directory required}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb shell settings put global window_animation_scale 1 || true
adb shell settings put global transition_animation_scale 1 || true
adb shell settings put global animator_duration_scale 1 || true
sleep 2
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell cmd uimode night yes
adb shell settings put system pointer_location 1 || true
adb shell settings put system show_touches 1 || true
SIZE=$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'); W=${SIZE%x*}; H=${SIZE#*x}
DENS=$(adb shell wm density | tail -1 | grep -o '[0-9]*$')
echo "screen ${W}x${H} density $DENS"
adb logcat -c || true

open_app() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design liquid_glass --es theme dark \
    --ez demo_providers true --ez demo_more_tabs true --ez open_priority true >/dev/null
  sleep 6
}
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "Saved $1.png"; }

# px from dp
px() { python3 -c "print(round($1 * $DENS / 160))"; }
WD=$(python3 -c "print($W * 160 / $DENS)"); HD=$(python3 -c "print($H * 160 / $DENS)")
# the More tab is the fourth of four on the bar; the bar's centre sits ~69 dp over the bottom
MORE_X=$(python3 -c "print(round(($WD * 0.805) * $DENS / 160))")
MORE_Y=$(python3 -c "print(round(($HD - 69) * $DENS / 160))")

# the status bar's height, which moves More's list down by as much (the JVM tests use 32 dp)
STATUS_PX=$(adb shell dumpsys window | grep -oE "statusBars[^]]*frame=\[0,0\]\[[0-9]+,[0-9]+\]" | head -1 | grep -oE "[0-9]+\]$" | tr -d ']' || true)
[[ -n "$STATUS_PX" ]] || STATUS_PX=$(px 52)
T=$(python3 -c "print($STATUS_PX * 160 / $DENS)")
echo "status bar ${STATUS_PX}px = ${T} dp"
# More's list rows (Copilot, Antigravity, Grok on the demo) and the bar's Back, as in MoreTabTest
row() { python3 -c "print(round((178 + 52 * $1 + $T - 32) * $DENS / 160))"; }
ROW_X=$((W / 2))
BACK_X=$(px 38); BACK_Y=$(python3 -c "print(round(($T + 32) * $DENS / 160))")
t0=$(date +%s.%N)
mark() { echo "$(python3 -c "print(round($(date +%s.%N) - $t0, 2))")s $*" | tee -a "$OUT/timeline.txt"; }
in_app() { adb shell dumpsys activity activities | grep -m1 "topResumedActivity" | grep -q "$PKG"; }
open_app
adb shell screenrecord --time-limit 150 /sdcard/more.mp4 & REC=$!
t0=$(date +%s.%N); sleep 1
i=0
for how in button back-key swipe reselect button2; do
  i=$((i + 1))
  mark "$how: tap More"; adb shell input tap "$MORE_X" "$MORE_Y"
  sleep 2; adb exec-out screencap -p > "$OUT/$i-$how-1-list.png"
  mark "$how: push Antigravity"; adb shell input tap "$ROW_X" "$(row 1)"
  sleep 2; adb exec-out screencap -p > "$OUT/$i-$how-2-pushed.png"
  mark "$how: back"
  case $how in
    button|button2) adb shell input tap "$BACK_X" "$BACK_Y" ;;
    back-key) adb shell input keyevent KEYCODE_BACK ;;
    swipe) adb shell input swipe 2 $((H / 2)) $((W * 3 / 4)) $((H / 2)) 300 ;;
    reselect) adb shell input tap "$MORE_X" "$MORE_Y" ;;
  esac
  sleep 2; adb exec-out screencap -p > "$OUT/$i-$how-3-list-again.png"
  if ! in_app; then echo "::error::$how: back from a pushed provider left the app"; exit 1; fi
  # start the next round from Codex
  adb shell input tap "$(python3 -c "print(round(($WD * 0.2) * $DENS / 160))")" "$MORE_Y"; sleep 2
done
kill -INT $REC 2>/dev/null || true; sleep 3
adb pull /sdcard/more.mp4 "$OUT/more.mp4" >/dev/null || true
adb logcat -d > "$OUT/logcat.txt" || true
grep -E "FATAL|AndroidRuntime" "$OUT/logcat.txt" && exit 1 || true
