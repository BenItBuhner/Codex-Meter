#!/usr/bin/env bash
# Runs on the emulator (see .github/workflows/upgrade-check.yml): installs the old release, seeds it
# through its own code, upgrades in place to the current build, opens it the way a user would and
# compares what both builds read. Screenshots, dumps and logcat land in $OUT.
set -uo pipefail
PKG=dev.bennett.codexmeter
PROBE=$PKG/dev.bennett.codexmeter.UpgradeProbeActivity
OLD_APK=${OLD_APK:?}
NEW_APK=${NEW_APK:?}
OUT=${OUT:?}
mkdir -p "$OUT"

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
probe() { adb shell am start -W -n "$PROBE" "$@" >/dev/null; sleep 3; }
pull_dump() {
  adb shell run-as $PKG cat "files/$1.json" > "$OUT/$1.json"
  python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$OUT/$1.json" \
    || { echo "::error::could not read $1.json from the device"; return 1; }
}

adb logcat -c || true
adb shell settings put global hide_error_dialogs 1 || true

echo "== install 2.8.0 and seed it"
adb install "$OLD_APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
probe --es action seed
adb shell am force-stop $PKG
# No network from here on, so nothing the old or new build fetches changes the seeded data.
adb shell svc wifi disable || true
adb shell svc data disable || true
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
shot 01-v2.8.0-dashboard 4
probe --es open history
shot 02-v2.8.0-history
adb shell am force-stop $PKG
probe --es action dump --es out before
pull_dump before || exit 1
adb shell am force-stop $PKG

echo "== upgrade in place"
adb install -r "$NEW_APK" || exit 1
adb shell dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -4 \
  | tee "$OUT/package.txt"
# Open it from the launcher, as a user does after updating.
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
shot 03-upgraded-first-open 5
adb shell input keyevent KEYCODE_BACK
shot 04-upgraded-after-back 3
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
shot 05-upgraded-dashboard 4
probe --es open history
shot 06-upgraded-history 3
probe --es open settings
shot 07-upgraded-settings 3
adb shell am force-stop $PKG
probe --es action dump --es out after
pull_dump after || exit 1

adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
if grep -E "FATAL EXCEPTION|UpgradeProbe.*failed" "$OUT/logcat.txt" | grep -q .; then
  grep -E -A20 "FATAL EXCEPTION|UpgradeProbe.*failed" "$OUT/logcat.txt" | head -80
  echo "::error::the app crashed or the probe failed during the upgrade check (see logcat.txt)"
  exit 1
fi
python3 .github/upgrade-check/compare.py "$OUT/before.json" "$OUT/after.json" \
  "${GITHUB_STEP_SUMMARY:-/dev/null}"
