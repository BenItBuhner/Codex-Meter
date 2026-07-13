#!/usr/bin/env bash
# End-to-end emulator demo driver for One UI <-> Material You.
set -euo pipefail
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
PKG=dev.bennett.codexmeter
OUT=/opt/cursor/artifacts/demo
mkdir -p "$OUT" /tmp/demo-frames

shot() {
  local name="$1"
  sleep 2
  adb shell screencap -p "/data/local/tmp/${name}.png" || true
  adb pull "/data/local/tmp/${name}.png" "$OUT/${name}.png" >/dev/null 2>&1 || true
  cp "$OUT/${name}.png" "/tmp/demo-frames/${name}.png" 2>/dev/null || true
  echo "SHOT $name"
}

wait_idle() {
  local secs="${1:-8}"
  sleep "$secs"
  # Dismiss common ANR buttons (Wait is usually on the right)
  for _ in 1 2 3; do
    adb shell input tap 700 1250 >/dev/null 2>&1 || true
    sleep 1
  done
}

tap_text() {
  local needle="$1"
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 || return 1
  adb pull /data/local/tmp/ui.xml /tmp/demo-ui.xml >/dev/null 2>&1 || return 1
  local coords
  coords=$(NEEDLE="$needle" python3 - <<'PY'
import os, re
xml=open('/tmp/demo-ui.xml').read()
needle=os.environ['NEEDLE']
pat=re.escape(needle)
for m in re.finditer(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'%pat, xml):
    x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); break
else:
  for m in re.finditer(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="%s"'%pat, xml):
    x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  if [[ -n "${coords:-}" ]]; then
    echo "TAP $needle -> $coords"
    adb shell input tap $coords
    return 0
  fi
  echo "MISS $needle"
  return 1
}

tap_switch_near() {
  local needle="$1"
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 || return 1
  adb pull /data/local/tmp/ui.xml /tmp/demo-ui.xml >/dev/null 2>&1 || return 1
  local coords
  coords=$(NEEDLE="$needle" python3 - <<'PY'
import os, re
xml=open('/tmp/demo-ui.xml').read()
needle=os.environ['NEEDLE']
# Find text, then nearest checkable=true after it
idx=xml.find('text="%s"'%needle)
if idx<0:
  raise SystemExit
window=xml[idx:idx+2500]
m=re.search(r'checkable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', window)
if not m:
  m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*checkable="true"', window)
if m:
  x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2)
PY
)
  if [[ -n "${coords:-}" ]]; then
    echo "SWITCH $needle -> $coords"
    adb shell input tap $coords
    return 0
  fi
  echo "MISS SWITCH $needle"
  return 1
}

echo "== install =="
adb install -r /workspace/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell cmd notification allow_listener "$PKG" 2>/dev/null || true

echo "== seed One UI =="
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.DemoSeedActivity" \
  --es demo_style one_ui \
  --ez demo_send_notification true \
  --ez demo_open_main true
wait_idle 25
shot 01_oneui_dashboard_seeded

echo "== One UI Settings =="
adb shell am start -n "$PKG/.SettingsActivity"
wait_idle 20
shot 02_oneui_settings
# scroll settings a bit
adb shell input swipe 540 1600 540 700 400
wait_idle 4
shot 03_oneui_settings_scrolled
# Send test notification preference if visible
tap_text "Send test notification" || true
wait_idle 6
shot 04_oneui_after_test_notification
# open shade
adb shell cmd statusbar expand-notifications || adb shell service call statusbar 1 >/dev/null 2>&1 || true
wait_idle 4
shot 05_oneui_notification_shade
adb shell cmd statusbar collapse || adb shell service call statusbar 2 >/dev/null 2>&1 || true
wait_idle 2

echo "== One UI About =="
adb shell am start -n "$PKG/.AboutActivity"
wait_idle 15
shot 06_oneui_about

echo "== One UI Widget customize =="
# Launch config with synthetic id; if invalid it finishes — also try pin from main
adb shell am start -n "$PKG/.MainActivity"
wait_idle 12
tap_text "Add widget" || true
wait_idle 8
shot 07_oneui_pin_widget_prompt
# Accept pin dialog (ALLOW / ADD / OK variants)
tap_text "ADD" || tap_text "Add" || tap_text "OK" || tap_text "Allow" || true
wait_idle 10
shot 08_oneui_home_or_widget

# If a widget exists, open config by long-press is hard; launch config for id 1..5
for id in 1 2 3 4 5 6 7 8 9 10; do
  if adb shell am start -n "$PKG/.WidgetConfigActivity" --ei appWidgetId "$id" >/dev/null 2>&1; then
    wait_idle 12
    if adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; then
      adb pull /data/local/tmp/ui.xml /tmp/demo-ui.xml >/dev/null 2>&1 || true
      if rg -q 'Customize widget|Opacity|Accent|Theme' /tmp/demo-ui.xml 2>/dev/null; then
        shot 09_oneui_widget_config
        tap_text "Save" || true
        wait_idle 6
        break
      fi
    fi
  fi
done

echo "== Switch to Material You via Settings =="
adb shell am start -n "$PKG/.SettingsActivity"
wait_idle 18
adb shell input swipe 540 1600 540 900 350
wait_idle 3
tap_switch_near "Material You" || tap_text "Material You" || true
wait_idle 22
shot 10_material_settings_after_toggle
# Fallback seed if toggle miss
adb shell am start -n "$PKG/.DemoSeedActivity" \
  --es demo_style material \
  --ez demo_send_notification false \
  --ez demo_open_main true
wait_idle 18
shot 10b_material_seeded_dashboard

echo "== Material Settings + notification =="
adb shell am start -n "$PKG/.SettingsActivity"
wait_idle 18
adb shell input swipe 540 1700 540 600 400
wait_idle 3
tap_text "Send test notification" || true
wait_idle 6
adb shell cmd statusbar expand-notifications || true
wait_idle 4
shot 12_material_notification_shade
adb shell cmd statusbar collapse || true

echo "== Material About =="
adb shell am start -n "$PKG/.AboutActivity"
wait_idle 15
shot 13_material_about

echo "== Material widget config =="
for id in 1 2 3 4 5 6 7 8 9 10; do
  adb shell am start -n "$PKG/.WidgetConfigActivity" --ei appWidgetId "$id" >/dev/null 2>&1 || true
  wait_idle 10
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 || true
  adb pull /data/local/tmp/ui.xml /tmp/demo-ui.xml >/dev/null 2>&1 || true
  if rg -q 'Customize widget|Opacity|Accent|Theme' /tmp/demo-ui.xml 2>/dev/null; then
    shot 14_material_widget_config
    break
  fi
done

echo "== Home screen widgets =="
adb shell input keyevent KEYCODE_HOME
wait_idle 8
shot 15_home_material_widgets
adb shell input swipe 200 1100 900 1100 350
wait_idle 4
shot 16_home_swipe

echo "== Switch back to One UI =="
adb shell am start -n "$PKG/.DemoSeedActivity" \
  --es demo_style one_ui \
  --ez demo_send_notification false \
  --ez demo_open_main true
wait_idle 22
shot 17_oneui_restored_dashboard
adb shell input keyevent KEYCODE_HOME
wait_idle 6
shot 18_home_oneui_widgets

echo "== DONE =="
ls -la "$OUT"
