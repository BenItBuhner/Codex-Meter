#!/usr/bin/env bash
# The CI emulator (API 35, google_apis, x86_64, Pixel 7, software GPU), started in the background so
# a job can build its APKs while it installs and boots, then waited on before the tests.
#
#   android/ci/emulator.sh start   # returns at once; installs the image, creates the AVD, boots it
#   android/ci/emulator.sh wait    # blocks until booted, then turns off animations
#
# The install and boot log goes to $RUNNER_TEMP/emulator.log, printed if the boot times out.
set -euo pipefail

IMAGE="system-images;android-35;google_apis;x86_64"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?no Android SDK}}"
LOG="${RUNNER_TEMP:-/tmp}/emulator.log"
PIDFILE="${RUNNER_TEMP:-/tmp}/emulator.pid"
export PATH="$SDK/platform-tools:$PATH"
# avdmanager and the emulator disagree on the default AVD folder on the runner image; pin it.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"

boot() {
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
    | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
  local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "$sdkmanager" ]] || sdkmanager="$(command -v sdkmanager)"
  "$sdkmanager" --install emulator "$IMAGE" > /dev/null
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd --force -n ci -k "$IMAGE" -d pixel_7
  echo "Starting the emulator at $(date -u +%T)"
  exec "$SDK/emulator/emulator" -avd ci -port 5554 -cores 2 -no-window -gpu swiftshader_indirect \
    -no-snapshot -noaudio -no-boot-anim -no-metrics
}

case "${1:-}" in
  start)
    # Later steps (perf-budgets.sh, shrunk-smoke.sh) call adb by name.
    [[ -n "${GITHUB_PATH:-}" ]] && echo "$SDK/platform-tools" >> "$GITHUB_PATH"
    export -f boot
    export SDK IMAGE ANDROID_AVD_HOME
    nohup bash -c boot > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    echo "Emulator install and boot started in the background (log: $LOG)"
    ;;
  wait)
    SECONDS=0
    booted() { [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; }
    until booted; do
      if ! kill -0 "$(cat "$PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null \
          && ! pgrep -f -- '-avd ci' >/dev/null; then
        echo "::error::The emulator exited before it booted"
        tail -n 80 "$LOG" || true
        exit 1
      fi
      if (( SECONDS > 600 )); then
        echo "::error::The emulator did not boot in 10 minutes"
        tail -n 80 "$LOG" || true
        exit 1
      fi
      sleep 2
    done
    echo "Emulator booted; waited ${SECONDS}s here"
    adb -s emulator-5554 shell settings put global window_animation_scale 0
    adb -s emulator-5554 shell settings put global transition_animation_scale 0
    adb -s emulator-5554 shell settings put global animator_duration_scale 0
    adb -s emulator-5554 shell settings put system screen_off_timeout 2147483647
    adb -s emulator-5554 logcat -G 2M || true
    ;;
  *)
    echo "usage: $0 start|wait" >&2
    exit 2
    ;;
esac
