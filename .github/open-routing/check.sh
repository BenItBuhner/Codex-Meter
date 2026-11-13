#!/usr/bin/env bash
# Open-routing check, run on an emulator by .github/workflows/open-routing-check.yml.
#
# Widget taps: a Claude Code home widget opens the app as it was left (cold: its usual first
# screen; warm: the same tab or Settings page), never jumping to the Claude Code tab.
# Live notification taps: open the dashboard tab of the provider the notification shows.
#
#   check.sh <debug APK> <output dir> <design...>
set -uo pipefail

APK="${1:?debug APK}"
OUT="${2:?output dir}"
shift 2
DESIGNS=("$@")
[[ ${#DESIGNS[@]} -gt 0 ]] || DESIGNS=(one_ui_9 liquid_glass)
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell cmd connectivity airplane-mode enable || true
adb logcat -c || true
adb logcat -v time > "$OUT/logcat.txt" 2>/dev/null &
W=1080; H=2400
read -r W H <<<"$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
at() {
  adb shell input tap "$(python3 -c "print(round($1 * $W))")" "$(python3 -c "print(round($2 * $H))")"
}
home() { adb shell input keyevent HOME; sleep 2; }
demo() {
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.UsagePaceDemoActivity --es design "$DESIGN" --es theme light \
    --ez demo_providers true "$@" >/dev/null
  sleep 6
}
harness() { adb shell am start -W -n $PKG/.RoutingHarnessActivity --es do "$@" >/dev/null; sleep 2; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }

# Taps the first view whose resource-id, text or content description is $1.
tap_view() {
  local xy
  xy=$(dump | python3 -c '
import re, sys
want = sys.argv[1]
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    attrs = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", node))
    if want in (attrs.get("resource-id"), attrs.get("text"), attrs.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", attrs["bounds"]))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1")
  [[ -n "$xy" ]] || { echo "no view for $1" >&2; return 1; }
  adb shell input tap $xy
}

# Taps the middle of the home-screen widget (the first view the app draws on the launcher).
tap_widget() {
  local xy
  xy=$(dump | python3 -c '
import re, sys
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    attrs = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", node))
    if attrs.get("package") == "com.google.android.apps.nexuslauncher" \
            and attrs.get("class") != "android.appwidget.AppWidgetHostView":
        continue
    if attrs.get("class") == "android.appwidget.AppWidgetHostView" \
            or attrs.get("package") == "dev.bennett.codexmeter":
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", attrs["bounds"]))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
')
  if [[ -z "$xy" ]]; then
    # The launcher's view tree is often not readable (the dump comes back empty); the pinned
    # widget sits in the top-left cells of the first home page (see 01-home-with-claude-widget).
    adb exec-out cat /sdcard/ui.xml > "$OUT/home-dump.xml" 2>/dev/null || true
    echo "widget not in the view dump; tapping its home-screen position" >&2
    at 0.27 0.15
    return 0
  fi
  adb shell input tap $xy
}

# Taps the live notification showing provider $1 (its display name) in the open shade.
tap_notification() {
  local xy
  xy=$(dump | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
name = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2
rows = [n for n in root.iter("node") if n.get("resource-id", "").endswith("expandableNotificationRow")]
for row in rows:
    texts = [n.get("text") for n in row.iter("node")]
    if name not in texts:
        continue
    for n in row.iter("node"):
        if n.get("resource-id") in ("android:id/title", "android:id/text"):
            print(*bounds(n)); sys.exit()
    print(*bounds(row)); sys.exit()
' "$1")
  [[ -n "$xy" ]] || { echo "no live notification for $1" >&2; return 1; }
  adb shell input tap $xy
}

selected() {
  adb shell run-as $PKG cat shared_prefs/codex_meter_providers_v1.xml 2>/dev/null \
    | sed -n 's/.*name="selected_provider">\([^<]*\)<.*/\1/p'
}
top_activity() {
  adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" \
    | grep -oE "[a-z0-9_.]+/[A-Za-z0-9_.$]+" | head -1
}
log_step() { adb shell log -t RoutingCheck "'$*'"; }

RESULTS="$OUT/results.md"
echo "| Design | Step | Expected | Seen | Result |" > "$RESULTS"
echo "|---|---|---|---|---|" >> "$RESULTS"
FAILS=0
expect() {
  local label="$1" want="$2" seen="$3" ok=PASS
  [[ "$seen" == $want ]] || { ok=FAIL; FAILS=$((FAILS + 1)); }
  echo "| $DESIGN | $label | $want | ${seen:-nothing} | $ok |" | tee -a "$RESULTS"
}

open_settings_appearance() {
  if [[ "$DESIGN" == liquid_glass ]]; then
    # Glassé draws on one surface, so taps are by position (see record-design-gallery.sh).
    at 0.906 0.091; sleep 3
    at 0.4 0.745; sleep 3
  else
    tap_view "Settings"; sleep 3
    tap_view "Appearance" || true; sleep 3
  fi
}

PINNED=0
for DESIGN in "${DESIGNS[@]}"; do
  demo --es open_provider codex
  home
  if [[ $PINNED == 0 ]]; then
    harness pin; sleep 2
    tap_view "Add to home screen" || tap_view "ADD TO HOME SCREEN" || tap_view "Add automatically" \
      || tap_view "Add"
    sleep 3; home
    PINNED=1
  fi
  harness widget_provider --es id claude_code
  sleep 2; home

  adb shell "screenrecord --time-limit 175 --size 540x1200 /sdcard/widget-$DESIGN.mp4" & REC=$!
  sleep 2
  # 1. Cold: the app was on Codex and is no longer running; a Claude Code widget opens it.
  log_step "widget cold $DESIGN"
  shot "$DESIGN-01-home-with-claude-widget" 1
  harness clear_tasks; home
  tap_widget; sleep 6
  shot "$DESIGN-02-widget-cold-open" 0
  expect "Widget tap opens the app, app not running" "*MainActivity*" "$(top_activity)"
  expect "Widget tap, app not running" codex "$(selected)"

  # 2. Warm: the user was on the Cursor tab with Settings › Appearance open, then went home.
  log_step "widget warm $DESIGN"
  demo --es open_provider cursor
  open_settings_appearance
  shot "$DESIGN-03-settings-open-before-home" 0
  home
  tap_widget; sleep 4
  shot "$DESIGN-04-widget-warm-open" 0
  if [[ "$DESIGN" == liquid_glass ]]; then
    expect "Widget tap resumes the app" "*MainActivity*" "$(top_activity)"
  else
    expect "Widget tap resumes the open screen" "*SettingsActivity*" "$(top_activity)"
  fi
  expect "Widget tap keeps the tab" cursor "$(selected)"
  adb shell input keyevent BACK; sleep 3
  adb shell input keyevent BACK; sleep 3
  shot "$DESIGN-05-back-to-dashboard" 0
  sleep 1
  adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 2
  adb pull "/sdcard/widget-$DESIGN.mp4" "$OUT/widget-$DESIGN.mp4" >/dev/null || true

  adb shell "screenrecord --time-limit 175 --size 540x1200 /sdcard/live-$DESIGN.mp4" & REC=$!
  sleep 2
  # 3. Live notification for Claude Code while the app sits on Cursor with Settings open.
  log_step "live claude $DESIGN"
  demo --es open_provider cursor
  open_settings_appearance
  home
  adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 2
  harness live --es id claude_code
  adb shell cmd statusbar expand-notifications; sleep 2
  shot "$DESIGN-06-claude-live-notification" 0
  tap_notification "Claude Code"; sleep 5
  shot "$DESIGN-07-claude-notification-opened" 0
  expect "Claude Code notification tap" claude_code "$(selected)"
  expect "Lands on the dashboard" "*MainActivity*" "$(top_activity)"

  # 4. Live notification for Codex, tapped from another app.
  log_step "live codex $DESIGN"
  adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 2
  harness live --es id codex
  adb shell cmd statusbar expand-notifications; sleep 2
  shot "$DESIGN-08-codex-live-notification" 0
  tap_notification "Codex"; sleep 5
  shot "$DESIGN-09-codex-notification-opened" 0
  expect "Codex notification tap" codex "$(selected)"
  harness stop_live
  home
  adb shell pkill -INT screenrecord; wait $REC 2>/dev/null; sleep 2
  adb pull "/sdcard/live-$DESIGN.mp4" "$OUT/live-$DESIGN.mp4" >/dev/null || true
done

cat "$RESULTS"
echo "$FAILS failed" | tee -a "$RESULTS"
[[ $FAILS == 0 ]]
