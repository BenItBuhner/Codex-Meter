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
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es claude_scenario "$1" >/dev/null
}

for theme in light dark; do
  if [[ "$theme" == dark ]]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  open_claude ultra;         shot "$theme-01-ultra" 8
  open_claude legacy_badge;  shot "$theme-02-saved-max20x-badge-renamed"
  open_claude sign_in_again; shot "$theme-03-sign-in-again"
  open_claude expired;       shot "$theme-04-reset-passed"
done

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
