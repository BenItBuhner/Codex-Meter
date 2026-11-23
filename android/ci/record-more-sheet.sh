#!/usr/bin/env bash
# More on a real phone (Liquid Glass, five providers): open More from the tab bar, then close it with
# its X in several ways a thumb taps (a clean tap, a tap that drifts a few pixels, a slow press, a
# press that slides), screenshotting each step with pointer location on so the taps show.
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

open_more() { adb shell input tap "$MORE_X" "$MORE_Y"; shot "$1" 1.5; }

i=0
for how in tap drift slow slide; do
  i=$((i + 1))
  open_app
  shot "$i-$how-0-page" 0.5
  open_more "$i-$how-1-open"
  python3 "$(dirname "$0")/more-sheet-x.py" "$OUT/$i-$how-1-open.png" > "$OUT/x.txt" || true
  read -r XX XY < "$OUT/x.txt" || { XX=$((W - $(px 46))); XY=0; }
  echo "$how: X at $XX,$XY"
  adb shell screenrecord --time-limit 6 /sdcard/$how.mp4 & REC=$!
  sleep 0.8
  case $how in
    tap) adb shell input tap "$XX" "$XY" ;;
    drift) adb shell input swipe "$XX" "$XY" $((XX - $(px 4))) $((XY + $(px 5))) 120 ;;
    slow) adb shell input swipe "$XX" "$XY" "$XX" "$XY" 600 ;;
    slide) adb shell input swipe "$XX" "$XY" $((XX - $(px 18))) $((XY + $(px 22))) 260 ;;
  esac
  shot "$i-$how-2-after-x" 1.5
  # a second tap, as Bennett kept tapping
  adb shell input tap "$XX" "$XY"
  shot "$i-$how-3-after-second" 1.5
  wait $REC || true
  adb pull /sdcard/$how.mp4 "$OUT/$how.mp4" >/dev/null || true
done
adb logcat -d > "$OUT/logcat.txt" || true
grep -E "FATAL|AndroidRuntime" "$OUT/logcat.txt" && exit 1 || true
