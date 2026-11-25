#!/usr/bin/env bash
# The CI emulator (API 35, google_apis, x86_64, Pixel 7, software GPU).
#
#   android/ci/emulator.sh start      # returns at once; boots in the background
#   android/ci/emulator.sh wait       # blocks until booted, then turns off animations
#   android/ci/emulator.sh snapshot   # first run on a runner image only: see below
#
# A cold boot costs about 3 minutes of CPU and the post-boot package compile (bg-dexopt-job) about
# 2.5 more, both competing with the Gradle build on a 4-core runner. So CI keeps a quick-boot
# snapshot of a booted, compiled emulator in the Actions cache (keyed on the runner image), and
# `start` resumes it in seconds. On a cache miss `start` cold-boots, `snapshot` compiles the
# packages and saves the snapshot on shutdown, the workflow caches it, and `start` resumes it.
#
# The install and boot log goes to $RUNNER_TEMP/emulator.log, printed if the boot fails.
set -euo pipefail

IMAGE="system-images;android-35;google_apis;x86_64"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?no Android SDK}}"
LOG="${RUNNER_TEMP:-/tmp}/emulator.log"
PIDFILE="${RUNNER_TEMP:-/tmp}/emulator.pid"
export PATH="$SDK/platform-tools:$PATH"
# avdmanager and the emulator disagree on the default AVD folder on the runner image; pin it.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
SNAPSHOT="$ANDROID_AVD_HOME/ci.avd/snapshots/default_boot"
ADB=(adb -s emulator-5554)

launch() { # extra emulator flags
  echo "Starting the emulator at $(date -u +%T) with $*"
  nohup "$SDK/emulator/emulator" -avd ci -port 5554 -cores 2 -no-window -gpu swiftshader_indirect \
    -noaudio -no-boot-anim -no-metrics "$@" >> "$LOG" 2>&1 &
  echo $! > "$PIDFILE"
}

cold_boot() {
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
    | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
  if [[ ! -e "$SNAPSHOT/ram.bin" ]]; then
    local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
    [[ -x "$sdkmanager" ]] || sdkmanager="$(command -v sdkmanager)"
    "$sdkmanager" --install "$IMAGE" > /dev/null
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd --force -n ci -k "$IMAGE" -d pixel_7
    # A full boot that saves a snapshot when `snapshot` shuts it down.
    launch -no-snapshot-load
  else
    # Resume the cached snapshot and never write it back.
    launch -no-snapshot-save
  fi
}

wait_boot() {
  SECONDS=0
  until [[ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if ! pgrep -f -- '-avd ci' >/dev/null \
        && ! kill -0 "$(cat "$PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null; then
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
}

case "${1:-}" in
  start)
    # Later steps (perf-budgets.sh, shrunk-smoke.sh) call adb by name.
    [[ -n "${GITHUB_PATH:-}" ]] && echo "$SDK/platform-tools" >> "$GITHUB_PATH"
    if [[ -e "$SNAPSHOT/ram.bin" ]]; then echo "Resuming the cached emulator snapshot"; else echo "No cached snapshot: cold boot"; fi
    export -f cold_boot launch
    export SDK IMAGE LOG PIDFILE SNAPSHOT
    nohup bash -c cold_boot >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    ;;
  wait)
    wait_boot
    "${ADB[@]}" shell settings put global window_animation_scale 0
    "${ADB[@]}" shell settings put global transition_animation_scale 0
    "${ADB[@]}" shell settings put global animator_duration_scale 0
    "${ADB[@]}" shell settings put system screen_off_timeout 2147483647
    "${ADB[@]}" logcat -G 2M || true
    ;;
  snapshot)
    [[ -e "$SNAPSHOT/ram.bin" ]] && { echo "Snapshot already cached"; exit 0; }
    wait_boot
    SECONDS=0
    timeout 400 "${ADB[@]}" shell cmd package bg-dexopt-job >/dev/null 2>&1 || true
    echo "Compiled the system's packages in ${SECONDS}s"
    "${ADB[@]}" shell settings put global window_animation_scale 0
    "${ADB[@]}" shell settings put global transition_animation_scale 0
    "${ADB[@]}" shell settings put global animator_duration_scale 0
    "${ADB[@]}" shell settings put system screen_off_timeout 2147483647
    SECONDS=0
    "${ADB[@]}" emu kill || true
    while pgrep -f -- '-avd ci' >/dev/null; do
      (( SECONDS < 180 )) || { echo "::warning::The emulator did not exit after saving"; pkill -9 -f -- '-avd ci' || true; break; }
      sleep 1
    done
    echo "Saved the snapshot in ${SECONDS}s ($(du -sh "$ANDROID_AVD_HOME" | cut -f1))"
    # Left shut down so the workflow can cache the files while nothing writes them; `start` then
    # resumes the snapshot (or cold-boots if none was written).
    [[ -e "$SNAPSHOT/ram.bin" ]] || echo "::warning::No snapshot was written; the next start cold-boots"
    ;;
  *)
    echo "usage: $0 start|wait|snapshot" >&2
    exit 2
    ;;
esac
