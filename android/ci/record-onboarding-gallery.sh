#!/usr/bin/env bash
# First-run setup and sign-in in Material 3 Expressive, light and dark, on a Samsung-sized phone
# (1440x3088 at 600 dpi), with One UI 9's setup in light for comparison. Each scene starts from a
# cleared install and seeds its step through the debug-only UsagePaceDemoActivity.
set -euo pipefail

APK="${1:?phone APK required}"
OUT="${2:?gallery directory required}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb shell wm size 1440x3088
adb shell wm density 600
sleep 3
adb install -r "$APK"
# The scenes clear the app between shots, and Pixel Launcher stopped responding under that
# churn: its "isn't responding" dialog covered every page. Nothing here needs a home screen.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null || true
W=1440
H=3088

shot() {
  sleep "${2:-5}"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "Saved $1.png"
}

# setup <design> <step> <providers> [codex signed in: yes]
setup() {
  adb shell am force-stop "$PKG"
  adb shell pm clear "$PKG" >/dev/null
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb shell input keyevent KEYCODE_WAKEUP
  local extra=()
  [ -n "$3" ] && extra+=(--es onboarding_providers "$3")
  [ "${4:-}" = yes ] && extra+=(--ez onboarding_codex_signed_in true)
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design "$1" \
    --es onboarding_step "$2" "${extra[@]}" --es open_activity OnboardingActivity >/dev/null
}

signin() {
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" --es design "$1" \
    --es signin_provider "$2" >/dev/null
}

scroll_down() {
  adb shell input swipe $((W / 2)) $((H * 78 / 100)) $((W / 2)) $((H * 30 / 100)) 450
  sleep "${1:-2}"
}

scenes() {
  local design="$1" theme="$2" p="$1-$2"
  setup "$design" 0 ""; shot "$p-01-welcome" 7
  setup "$design" 1 ""; shot "$p-02-providers-none"
  setup "$design" 1 "codex,claude_code,copilot"; shot "$p-03-providers-picked"
  scroll_down; shot "$p-04-providers-scrolled" 2
  setup "$design" 2 "codex,claude_code,copilot"; shot "$p-05-sign-in-codex"
  setup "$design" 2 "codex,claude_code,copilot" yes; shot "$p-06-codex-connected"
  setup "$design" 5 "codex,claude_code,copilot" yes; shot "$p-07-all-set"
  signin "$design" claude_code; shot "$p-08-sign-in-claude-code"
  signin "$design" copilot; shot "$p-09-sign-in-copilot"
}

for mode in no yes; do
  adb shell cmd uimode night "$mode"; sleep 2
  theme=$([ "$mode" = yes ] && echo dark || echo light)
  scenes material_3_expressive "$theme"
  # One UI 9 in light, as the before picture.
  [ "$theme" = light ] && scenes one_ui_9 light
done

adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true
if [[ -s "$OUT/logcat-crash.txt" ]]; then
  echo "Crashes during the gallery run:"
  cat "$OUT/logcat-crash.txt"
  exit 1
fi
