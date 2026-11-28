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

# Every device call is bounded, so a hung app or adb shows up as a failure with logs, not a
# job that runs until GitHub cancels it.
t() { timeout "${T:-60}" "$@"; local rc=$?; [[ $rc == 124 ]] && echo "::warning::timed out: $*"; return $rc; }
shot() { sleep "${2:-2}"; t adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
probe() { t adb shell am start -n "$PROBE" "$@" >/dev/null; sleep 4; }
pull_dump() {
  t adb shell run-as $PKG cat "files/$1.json" > "$OUT/$1.json"
  python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$OUT/$1.json" \
    || { echo "::error::could not read $1.json from the device"; return 1; }
}

adb logcat -c || true
t adb shell settings put global hide_error_dialogs 1 || true

# Whatever happens, keep the device log and the activity stack for the artifact.
collect() {
  timeout 60 adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  timeout 60 adb shell dumpsys activity activities > "$OUT/activities.txt" 2>/dev/null || true
}
trap collect EXIT

echo "== install 2.8.0 and seed it"
T=180 t adb install "$OLD_APK" || exit 1
t adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
probe --es action seed
t adb shell am force-stop $PKG
# No network from here on, so nothing the old or new build fetches changes the seeded data.
t adb shell svc wifi disable || true
t adb shell svc data disable || true
t adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
shot 01-v2.8.0-dashboard 4
probe --es open history
shot 02-v2.8.0-history
t adb shell am force-stop $PKG
probe --es action dump --es out before
pull_dump before || exit 1
t adb shell am force-stop $PKG

echo "== upgrade in place"
T=180 t adb install -r "$NEW_APK" || exit 1
t adb shell dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -4 \
  | tee "$OUT/package.txt"
# Open it from the launcher, as a user does after updating.
t adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
shot 03-upgraded-first-open 5
t adb shell input keyevent KEYCODE_BACK
shot 04-upgraded-after-back 3
t adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
shot 05-upgraded-dashboard 4
probe --es open history
shot 06-upgraded-history 3
probe --es open settings
shot 07-upgraded-settings 3
t adb shell am force-stop $PKG
probe --es action dump --es out after
pull_dump after || exit 1

collect
if grep -E "FATAL EXCEPTION|ANR in $PKG|UpgradeProbe.*failed" "$OUT/logcat.txt" | grep -q .; then
  grep -E -A20 "FATAL EXCEPTION|ANR in $PKG|UpgradeProbe.*failed" "$OUT/logcat.txt" | head -80
  echo "::error::the app crashed, stopped responding or the probe failed during the upgrade check (see logcat.txt)"
  exit 1
fi
python3 .github/upgrade-check/compare.py "$OUT/before.json" "$OUT/after.json" \
  "${GITHUB_STEP_SUMMARY:-/dev/null}"
