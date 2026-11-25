#!/usr/bin/env bash
# Back gestures on a real phone (Android 16, gesture navigation, predictive back), in each design:
#   Settings › Providers, then Add account on top, swiped back half way (shot) and all the way;
#   sign-in over Providers, swiped back the same way; What's new in 3.0, swiped back.
# After each back, Providers is scrolled to prove it still draws (the old freeze), and the activity in
# front is checked (What's new must close on a swipe, Add account must not be an activity of its own
# in Liquid Glass). Writes PNGs, a screen recording, checks.txt and logcat to the output directory.
set -euo pipefail
APK="${1:?phone APK required}"
OUT="${2:?output directory required}"
DESIGNS="${3:-liquid_glass one_ui_9 material_3_expressive}"
PKG=dev.bennett.codexmeter
mkdir -p "$OUT"
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 2; done
adb shell settings put system screen_off_timeout 1800000 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
# gesture navigation: the back swipe from the screen edge, with Android 16's predictive back
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural || \
  adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell settings put global enable_back_animation 1 || true
sleep 3
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
SIZE=$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'); W=${SIZE%x*}; H=${SIZE#*x}
echo "screen ${W}x${H}"
adb logcat -c || true
python3 -m pip install --quiet --user pillow >/dev/null 2>&1 || true
FAIL=0
check() { echo "$*" | tee -a "$OUT/checks.txt"; }
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$OUT/$1.png"; }
demo() { adb shell am start -W -n "$PKG/.UsagePaceDemoActivity" "$@" >/dev/null; }
front() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "$PKG/[.A-Za-z0-9]*" || true; }

# A back swipe from the left edge, held half way for a screenshot of the predictive back preview.
back_swipe() {
  local name="$1" y=$((H / 2)) x
  adb shell input motionevent DOWN 2 "$y"
  for x in $((W / 20)) $((W / 10)) $((W / 6)) $((W / 4)) $((W / 3)); do
    adb shell input motionevent MOVE "$x" "$y"; sleep 0.05
  done
  adb exec-out screencap -p > "$OUT/$name-mid.png"
  adb shell input motionevent MOVE $((W / 2)) "$y"; sleep 0.05
  adb shell input motionevent UP $((W / 2)) "$y"
}

# The page in front still draws: a scroll must change what is on screen.
alive() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/$name-a.png"
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 300; sleep 1.5
  adb exec-out screencap -p > "$OUT/$name-b.png"
  adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 300; sleep 1
  if ! cmp -s "$OUT/$name-a.png" "$OUT/$name-b.png"; then check "PASS $name: Providers scrolls after back"; else check "FAIL $name: Providers did not change on scroll (frozen?)"; FAIL=1; fi
}

# A mid-swipe frame that is almost all black is the "background turns black" bug. With "info" the
# result is only reported (sign-in is a second activity: the system draws that preview).
not_black() {
  local f="$OUT/$1-mid.png" soft="${2:-}"
  if python3 - "$f" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('L').resize((54, 120))
px = list(im.getdata())
dark = sum(1 for v in px if v < 6) / len(px)
print(f"black share {dark:.2f}")
sys.exit(1 if dark > 0.6 else 0)
PY
  then check "PASS $1: predictive back preview is not black"
  elif [[ -n "$soft" ]]; then check "NOTE $1: predictive back preview is black"
  else check "FAIL $1: predictive back preview is black"; FAIL=1; fi
}

adb shell screenrecord --time-limit 170 /sdcard/back.mp4 & REC=$!
for design in $DESIGNS; do
  for theme in light; do
    tag="$design"
    check "== $tag"
    adb shell am force-stop "$PKG"
    demo --es design "$design" --es theme "$theme" --ez demo_providers true; sleep 5
    demo --es top_activity ProvidersActivity; shot "$tag-01-providers" 3
    demo --es top_activity AddAccountActivity; shot "$tag-02-add-account" 3
    f=$(front); check "front with Add account: $f"
    if [[ "$design" == liquid_glass && "$f" == *AddAccountActivity* ]]; then
      check "FAIL $tag: Add account opened as its own activity"; FAIL=1
    fi
    back_swipe "$tag-03-add-account-back"; shot "$tag-04-after-add-account-back" 2
    not_black "$tag-03-add-account-back"
    f=$(front); check "front after back: $f"
    [[ "$f" == *AddAccountActivity* ]] && { check "FAIL $tag: still on Add account after back"; FAIL=1; }
    alive "$tag-05-providers-after-add-account"

    demo --es top_activity signin:openrouter; shot "$tag-06-signin" 3
    back_swipe "$tag-07-signin-back"; shot "$tag-08-after-signin-back" 2
    not_black "$tag-07-signin-back" info
    f=$(front); check "front after sign-in back: $f"
    [[ "$f" == *SignInActivity* ]] && { check "FAIL $tag: still on sign-in after back"; FAIL=1; }
    alive "$tag-09-providers-after-signin"
    # Providers itself, swiped back to Settings: the preview shows Settings, never a black page
    back_swipe "$tag-09c-providers-back"; shot "$tag-09d-after-providers-back" 2
    not_black "$tag-09c-providers-back"

    demo --es top_activity WhatsNewActivity; shot "$tag-10-whats-new" 3
    back_swipe "$tag-11-whats-new-back"; shot "$tag-12-after-whats-new-back" 2
    not_black "$tag-11-whats-new-back" info
    f=$(front); check "front after What's new back: $f"
    [[ "$f" == *WhatsNewActivity* ]] && { check "FAIL $tag: What's new did not close on a back swipe"; FAIL=1; }
  done
done
kill -INT $REC 2>/dev/null || true; sleep 3
adb pull /sdcard/back.mp4 "$OUT/back.mp4" >/dev/null || true
adb logcat -d > "$OUT/logcat.txt" || true
if grep -E "FATAL EXCEPTION|Glassé stopped" "$OUT/logcat.txt"; then check "FAIL crash in logcat"; FAIL=1; fi
cat "$OUT/checks.txt"
exit $FAIL
