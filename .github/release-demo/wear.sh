#!/usr/bin/env bash
# Records the Wear OS clips for the 3.0.0 demo video, with demo data from wear-demo-prefs.py.
set -uo pipefail
APK="$1"
OUT="$GITHUB_WORKSPACE/demo/wear"
PREFS="$GITHUB_WORKSPACE/android/ci/wear-demo-prefs.py"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
adb shell settings put global window_animation_scale 1 || true
adb shell settings put global transition_animation_scale 1 || true
adb shell settings put global animator_duration_scale 1 || true
adb install -r "$APK"

seed() {
  python3 "$PREFS" "${1:-}" > /tmp/wear-prefs.xml
  adb shell am force-stop "$PKG"
  adb push /tmp/wear-prefs.xml /data/local/tmp/wear-prefs.xml >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/wear-prefs.xml shared_prefs/codex_meter_wear_v1.xml'"
}
open_app() {
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell am start -W -n "$PKG/.WearMainActivity" >/dev/null
  for _ in $(seq 1 30); do
    adb shell dumpsys window | grep -q "mCurrentFocus=.*WearMainActivity" && break
    sleep 1
  done
}
scroll_down() { adb shell input swipe 225 360 225 140 700; sleep 1.8; }
scroll_up() { adb shell input swipe 225 140 225 360 700; sleep 1.4; }
REC=
rec() { adb shell "screenrecord --bit-rate 6000000 /sdcard/$1.mp4" & REC=$!; CLIP=$1; sleep 1.5; }
stop() {
  sleep 1
  adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 1.5
  adb pull /sdcard/$CLIP.mp4 "$OUT/$CLIP.mp4" >/dev/null && echo "recorded $CLIP"
  adb shell rm -f /sdcard/$CLIP.mp4
}

seed
open_app; sleep 5; open_app; sleep 6
adb exec-out screencap -p > "$OUT/start.png"
rec 01-wear-codex
sleep 2.5
scroll_down; scroll_down; scroll_down; scroll_down
scroll_up; scroll_up; scroll_up; scroll_up
stop
for p in claude_code cursor copilot; do
  seed $p; open_app; sleep 8
  rec 02-wear-$p
  sleep 3; scroll_down; scroll_up
  stop
done
adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
ls -la "$OUT"
