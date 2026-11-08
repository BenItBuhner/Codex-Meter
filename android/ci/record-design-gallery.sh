#!/usr/bin/env bash
# Installs the debug phone app on a running emulator, seeds demo usage for three providers
# (UsagePaceDemoActivity) and saves screenshots plus a screen recording of one design in light
# and dark: dashboard, scrolled, pull to refresh (checked in the log), another provider tab, Settings, Appearance and
# widget settings.
#
#   android/ci/record-design-gallery.sh <debug APK> <output dir> [design] [scenes]
#
# design: liquid_glass (default), liquid_glass_ios_26, liquid_glass_ios_27, material_3_expressive,
# one_ui_9 or classic. scenes: all (default), new_models for only the New models card (with a
# relevant model, then with only irrelevant ones), the Notifications toggle and the Refresh note, or
# priority for only the Priority tab (top to bottom, then a provider tab and back), or menus for a pop-up
# menu opened and closed on video.
set -euo pipefail

APK="${1:?debug APK required}"
OUT="${2:?gallery directory required}"
DESIGN="${3:-liquid_glass}"
SCENES="${4:-all}"
PKG=dev.bennett.codexmeter
DEMO="$PKG/.UsagePaceDemoActivity"
mkdir -p "$OUT"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
# A slow emulator's launcher sometimes hits an ANR, and its dialog covers every later shot.
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell settings put global window_animation_scale 1 || true
adb shell settings put global transition_animation_scale 1 || true
adb shell settings put global animator_duration_scale 1 || true
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb install -r "$APK"
# Keep the device log next to the pictures, so a crash (app or emulator) can be traced.
adb logcat -c || true
adb logcat -v time > "$OUT/logcat.txt" 2>/dev/null &
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

shot() {
  sleep "${2:-3}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

demo() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$DEMO" --es design "$DESIGN" --es theme "$THEME" \
    --ez demo_providers true "$@" >/dev/null
}

# Center of the first view whose class, content description or text matches $1.
center_of() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys
want = sys.argv[1]
xml = sys.stdin.read()
for node in re.findall(r"<node [^>]*>", xml):
    cls = re.search(r"class=\"([^\"]*)\"", node).group(1)
    desc = re.search(r"content-desc=\"([^\"]*)\"", node).group(1)
    text = re.search(r" text=\"([^\"]*)\"", node)
    if want in (cls, desc, text.group(1) if text else None):
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2, x1, x2)
        break
' "$1"
}

tap_desc() {
  read -r x y _ _ <<<"$(center_of "$1")"
  [[ -n "${x:-}" ]] || { echo "no view for $1" >&2; return 1; }
  adb shell input tap "$x" "$y"
}

# Taps tab $1 (0-based) of $2 tabs on the glass tab bar ("Providers": one view, so by position).
tap_tab() {
  read -r _ y x1 x2 <<<"$(center_of "Providers")"
  [[ -n "${y:-}" ]] || { echo "no tab bar" >&2; return 1; }
  local w=$(( (x2 - x1) / $2 ))
  adb shell input tap $(( x1 + w * $1 + w / 2 )) "$y"
}

# Pulls the dashboard down with a held finger (shots mid-pull and after letting go) and checks the
# app logged a refresh. Prints PULL-TO-REFRESH OK or FAILED with the shot name.
pull_refresh() {
  local x=$((W / 2)) y0=$((H / 3)) n0 n1
  n0="$(adb logcat -d -s CodexGlass:I | grep -c 'pull to refresh' || true)"
  adb shell input motionevent DOWN "$x" "$y0"
  for k in 1 2 3 4 5 6 7 8 9 10; do adb shell input motionevent MOVE "$x" $((y0 + H * k / 40)); done
  shot "$1-pulling" 0
  adb shell input motionevent UP "$x" $((y0 + H / 4))
  shot "$1-released" 0.6
  sleep 2
  n1="$(adb logcat -d -s CodexGlass:I | grep -c 'pull to refresh' || true)"
  if (( n1 > n0 )); then echo "PULL-TO-REFRESH OK: $1"; else echo "PULL-TO-REFRESH FAILED: $1"; fi
}

# Dashboard tabs: taps Edit (top right), shoots the handles, then touches and holds the first tab and drags
# it down two rows. Prints REORDER OK or FAILED from the app's log.
reorder_tabs() {
  local d n0 n1 x y
  d="$(adb shell wm density | grep -oE '[0-9]+' | tail -1)"
  n0="$(adb logcat -d -s CodexGlass:I | grep -c 'reorder tabs' || true)"
  adb shell input tap $(( W - 50 * d / 160 )) $(( 56 * d / 160 ))
  shot "$1-editing" 1.5
  adb shell input tap $(( W - 50 * d / 160 )) $(( 56 * d / 160 ))
  sleep 1
  x=$(( 150 * d / 160 )); y=$(( 245 * d / 160 ))
  adb shell input motionevent DOWN "$x" "$y"
  sleep 0.8
  for k in 1 2 3 4 5 6 7 8 9 10 11 12; do adb shell input motionevent MOVE "$x" $(( y + 160 * d / 160 * k / 12 )); done
  shot "$1-dragging" 0
  adb shell input motionevent UP "$x" $(( y + 160 * d / 160 ))
  shot "$1-dropped" 1.5
  n1="$(adb logcat -d -s CodexGlass:I | grep -c 'reorder tabs' || true)"
  if (( n1 > n0 )); then echo "REORDER OK: $1"; else echo "REORDER FAILED: $1"; fi
}

size="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
W="${size%x*}"; H="${size#*x}"

# The Priority view's New models card and its two settings. Seeded catalog records only; the
# app's own catalog check waits 30 minutes after the seeded success, so nothing is fetched.
new_models_scenes() {
  for THEME in light dark; do
    demo --es demo_new_models relevant
    shot "$THEME-30-new-models" 6
    for k in 1 2 3; do
      adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
      shot "$THEME-30-new-models-scroll-$k" 2
    done
    demo --es demo_new_models irrelevant
    shot "$THEME-31-irrelevant-hidden" 6
    for k in 1 2 3; do
      adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
      shot "$THEME-31-irrelevant-hidden-scroll-$k" 2
    done
    demo --ez demo_notifications_on true
    sleep 3
    demo --ez demo_notifications_on true --es open_settings_page notifications
    shot "$THEME-32-notifications" 5
    adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
    shot "$THEME-32-notifications-scrolled" 2
    demo --es open_settings_page refresh_usage
    shot "$THEME-33-refresh" 5
    for k in 1 2; do
      adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
      shot "$THEME-33-refresh-scrolled-$k" 2
    done
  done
}

# The Priority tab, first in the bar: its page top to bottom, a provider tab, and back.
priority_scenes() {
  for THEME in light dark; do
    demo --ez open_priority true --es demo_new_models relevant
    shot "$THEME-40-priority" 6
    for k in 1 2 3 4; do
      adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
      shot "$THEME-40-priority-scroll-$k" 2
    done
    # Liquid Glass's bar is one view, tapped by position; the other designs reopen on Codex.
    if tap_tab 1 4; then
      shot "$THEME-41-provider-without-priority-card" 4
      { tap_tab 0 4 && shot "$THEME-42-priority-again" 4; } || true
    else
      demo
      shot "$THEME-41-provider-without-priority-card" 6
    fi
  done
}

# A pop-up menu (Settings, Refresh usage) opened and closed three times on video, light and dark, for
# comparing menu motion. Saves menu-<theme>.mp4 and a shot with the menu open.
menu_scenes() {
  for THEME in light dark; do
    demo --es open_settings_page refresh_usage
    sleep 5
    adb shell screenrecord --time-limit 16 "/sdcard/menu-$THEME.mp4" &
    local rec=$!
    sleep 2
    for k in 1 2 3; do
      tap_desc "Refresh usage" || true
      if (( k == 3 )); then shot "$THEME-50-menu-open" 1.5; else sleep 1.5; fi
      adb shell input keyevent KEYCODE_BACK
      sleep 1.5
    done
    wait "$rec" || true
    sleep 2
    adb pull "/sdcard/menu-$THEME.mp4" "$OUT/menu-$THEME.mp4" >/dev/null && echo "Saved menu-$THEME.mp4"
  done
}

if [[ "$SCENES" == menus ]]; then
  menu_scenes
  adb logcat -d -b crash > "$OUT/crash-log.txt" || true
  exit 0
fi

if [[ "$SCENES" == priority ]]; then
  priority_scenes
  adb logcat -d -b crash > "$OUT/crash-log.txt" || true
  exit 0
fi

if [[ "$SCENES" == new_models ]]; then
  new_models_scenes
  adb logcat -d -b crash > "$OUT/crash-log.txt" || true
  exit 0
fi

for THEME in light dark; do
  demo
  shot "$THEME-00-launch" 2
  shot "$THEME-01-dashboard" 5
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 500
  shot "$THEME-02-scrolled" 2
  for _ in 1 2; do adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 4 / 5)) 300; sleep 1; done
  sleep 2
  pull_refresh "$THEME-02b-pull"
  sleep 2
  { tap_tab 2 4 && shot "$THEME-04-claude" 3; } || true
  { tap_tab 3 4 && shot "$THEME-05-cursor" 3; } || true
  pull_refresh "$THEME-05b-pull-short-page"
  demo --ez resume_live_settings true
  shot "$THEME-06-settings" 5
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 500
  shot "$THEME-07-settings-scrolled" 2
  demo --es open_settings_page appearance
  shot "$THEME-08-appearance" 5
  demo --es open_widget_metric five_hour
  shot "$THEME-09-widget-settings" 6
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 500
  shot "$THEME-10-widget-settings-scrolled" 2
  { tap_desc "Theme" && shot "$THEME-11-widget-picker" 4; } || true
  adb shell input keyevent KEYCODE_BACK
  demo --es open_settings_page refresh_usage
  shot "$THEME-12-refresh-settings" 5
  { tap_desc "Refresh usage" && shot "$THEME-13-list-picker" 4; } || true
  demo --es open_settings_page notifications
  shot "$THEME-14-notifications" 5
  demo --es open_activity ProvidersActivity
  shot "$THEME-15-providers" 5
  reorder_tabs "$THEME-15b-providers"
  demo --ez open_usage_history true
  shot "$THEME-16-usage-history" 5
  for _ in 1 2 3; do adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 5)) 300; sleep 1; done
  { tap_desc "Clear local history" && shot "$THEME-17-alert" 4; } || true
done

priority_scenes

# One take in light: dashboard, menu open and closed, the Priority tab, provider tabs, scrolling.
THEME=light
demo
sleep 5
adb shell screenrecord --time-limit 40 /sdcard/walk.mp4 &
REC=$!
sleep 2
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 700; sleep 2
adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 700; sleep 2
tap_desc "More" || true; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_tab 0 4 || true; sleep 3
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 700; sleep 2
tap_tab 2 4 || true; sleep 3
tap_tab 3 4 || true; sleep 3
tap_tab 1 4 || true; sleep 3
tap_desc "More" || true; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
wait "$REC" || true
sleep 2
adb pull /sdcard/walk.mp4 "$OUT/walk-light.mp4" >/dev/null && echo "Saved walk-light.mp4"
adb logcat -d -b crash > "$OUT/crash-log.txt" || true
