#!/usr/bin/env bash
# Opens every design and screen of a debug phone app built with the release R8 rules
# (`./gradlew :app:assembleDebug -PshrinkDebug=true`) and fails if the app crashes, so a missing keep
# rule shows up in CI instead of on a phone. Saves a screenshot of each screen and the device log.
#
#   android/ci/shrunk-smoke.sh <shrunk debug APK> <output dir>
set -euo pipefail

APK="${1:?shrunk debug APK required}"
OUT="${2:?output directory required}"
PKG=dev.bennett.codexmeter
DEMO="$PKG/.UsagePaceDemoActivity"
mkdir -p "$OUT"

adb wait-for-device
adb shell 'while [[ "$(getprop sys.boot_completed)" != "1" ]]; do sleep 1; done'
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
# The release build signed with another key may be installed already (perf-budgets.sh).
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" >/dev/null
adb logcat -c || true
adb logcat -v time > "$OUT/logcat.txt" 2>/dev/null &
LOGCAT=$!
trap 'kill "$LOGCAT" 2>/dev/null || true' EXIT

failed=0
check() { # name, then demo extras
  local name="$1"; shift
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$DEMO" --ez demo_providers true "$@" >/dev/null 2>&1 || true
  sleep 4
  # Liquid Glass's first frame can take several seconds in a debuggable build on a software-GPU
  # emulator; a nearly black screen compresses to under 40 KB, so look again a few times.
  local k
  for k in 1 2 3 4; do
    adb exec-out screencap -p > "$OUT/$name.png" || true
    (( $(stat -c %s "$OUT/$name.png" 2>/dev/null || echo 0) > 40000 )) && break
    sleep 3
  done
  if [[ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]]; then
    echo "::error::$name: the app is not running (crashed?)"
    failed=1
  else
    echo "ok  $name"
  fi
}

for design in classic one_ui_9 liquid_glass material_3_expressive; do
  for theme in light dark; do
    check "$design-$theme-dashboard" --es design "$design" --es theme "$theme"
  done
  check "$design-priority" --es design "$design" --ez open_priority true --es demo_new_models relevant \
    --ez demo_cursor_used_up true
  check "$design-claude" --es design "$design" --es claude_scenario tester
  for page in root accounts appearance refresh_usage reset_credits notifications now_bar app_sessions \
      updates transfer privacy diagnostics; do
    check "$design-settings-$page" --es design "$design" --es open_settings_page "$page"
  done
  check "$design-usage-history" --es design "$design" --ez open_usage_history true
  check "$design-widget-config" --es design "$design" --es open_widget_metric five_hour
  for screen in ProvidersActivity AboutActivity ResetCreditActivity DashboardReorderActivity \
      UpdateActivity ReleaseHistoryActivity LockWidgetsActivity OnboardingActivity; do
    check "$design-$screen" --es design "$design" --es open_activity "$screen"
  done
done

# Priority after a dismiss: swipe rows off the Priority page and log how long each page rebuild
# took (GlassBuild, Liquid Glass) and any main-thread stall (MainThreadWatch, debug builds).
read -r W H < <(adb shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical/ { print $3, $4 }')
for design in liquid_glass one_ui_9; do
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$DEMO" --ez demo_providers true --es design "$design" --ez open_priority true \
    --es demo_new_models relevant --ez demo_cursor_used_up true --ez demo_refill_notification true >/dev/null 2>&1
  sleep 8
  adb exec-out screencap -p > "$OUT/$design-priority-before-dismiss.png" || true
  adb logcat -c || true
  for y in 85 75 65 55 45; do
    adb shell input swipe $((W * 85 / 100)) $((H * y / 100)) $((W * 10 / 100)) $((H * y / 100)) 250
    sleep 2
    adb exec-out screencap -p > "$OUT/$design-priority-swipe-$y.png" || true
  done
  adb exec-out screencap -p > "$OUT/$design-priority-after-dismiss.png" || true
  adb logcat -d -s GlassBuild:I MainThreadWatch:W > "$OUT/$design-dismiss-timing.txt" || true
  python3 - "$OUT/$design-dismiss-timing.txt" "$design" <<'PY' | tee -a "$OUT/dismiss-summary.txt"
import re, sys
text = open(sys.argv[1], errors="replace").read()
builds = [int(m) for m in re.findall(r"priority page built in (\d+) ms", text)]
stalls = [int(m) for m in re.findall(r"stall (\d+)ms", text)]
print(f"{sys.argv[2]}: {len(builds)} Priority rebuilds {builds} ms; {len(stalls)} main-thread stalls {stalls} ms")
PY
done
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && { echo "### Priority after a dismiss"; sed 's/^/- /' "$OUT/dismiss-summary.txt"; } >> "$GITHUB_STEP_SUMMARY"

sleep 1
# Any crash of ours in the log fails the run, including one in a screen that recovered.
if grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PKG"; then
  echo "::error::The app crashed; see logcat.txt"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80
  failed=1
fi
# R8 trouble that is caught and logged instead of crashing: listed for review, not failed on, since
# the system and other apps log these too.
grep -E "ClassNotFoundException|NoSuchMethodException|NoSuchFieldException|NoClassDefFoundError" \
  "$OUT/logcat.txt" > "$OUT/reflection-errors.txt" || true
if [[ -s "$OUT/reflection-errors.txt" ]]; then
  echo "::warning::$(wc -l < "$OUT/reflection-errors.txt") reflection errors in the log; see reflection-errors.txt"
  head -20 "$OUT/reflection-errors.txt"
fi
exit "$failed"
