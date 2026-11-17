#!/usr/bin/env bash
# Measures the release phone app on a running emulator and fails past a ceiling:
#   - APK size
#   - cold start: median `am start -W` TotalTime over several launches, after one warm-up
#   - memory: total PSS a few seconds after the first screen settles
# Prints a Markdown table to the log, and to the job summary when run in GitHub Actions.
#
#   android/ci/perf-budgets.sh path/to/app-release.apk [path/to/shipped.apk]
#
# The first APK is installed and launched, so it must match the emulator's ABI. The size budget
# applies to the second, the APK users install, when it is given (default: the first).
#
# Ceilings are set from measured CI runs (swiftshader x86_64 emulator, API 35) with headroom for
# runner noise. Raise one only with a reason in the PR that needs it; lower them as the app gets
# leaner. The median of several launches keeps one slow launch from failing the check.
set -euo pipefail

APK="${1:?apk path required}"
SIZE_APK="${2:-$APK}"
PACKAGE="dev.bennett.codexmeter"
ACTIVITY="$PACKAGE/.MainActivity"
LAUNCHES="${LAUNCHES:-7}"

# First measured 2026-11-17 on alpha: APK 74.8 MB (R8 is off and skiko ships x86_64 and arm64
# libraries), cold start median 3607 ms (single launches 2258-4085 ms on the software-rendered
# emulator), PSS 55.9 MB. The ceilings stop regressions; bringing the first two down is v3 work.
MAX_APK_MB="${MAX_APK_MB:-80}"
MAX_COLD_START_MS="${MAX_COLD_START_MS:-5000}"
MAX_PSS_MB="${MAX_PSS_MB:-90}"

adb wait-for-device
adb shell 'while [[ "$(getprop sys.boot_completed)" != "1" ]]; do sleep 1; done'
adb install -r -g "$APK" >/dev/null

apk_bytes="$(stat -c %s "$SIZE_APK")"

cold_start() {
  adb shell am force-stop "$PACKAGE"
  # Drop the page cache so every launch reads the APK from disk, as after a reboot.
  adb shell 'echo 3 > /proc/sys/vm/drop_caches' 2>/dev/null || true
  adb shell am start -W -n "$ACTIVITY" | tr -d '\r' | awk -F': ' '$1 == "TotalTime" { print $2 }'
}

cold_start >/dev/null # warm-up: first launch also runs dexopt and first-run setup
times=()
for _ in $(seq "$LAUNCHES"); do
  t="$(cold_start)"
  [[ "$t" =~ ^[0-9]+$ ]] || { echo "::error::am start -W printed no TotalTime" >&2; exit 1; }
  times+=("$t")
done
sorted="$(printf '%s\n' "${times[@]}" | sort -n)"
median="$(sed -n "$(( (LAUNCHES + 1) / 2 ))p" <<< "$sorted")"

# Memory once the first screen has settled.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 5
pss_kb="$(adb shell dumpsys meminfo "$PACKAGE" | tr -d '\r' \
  | awk '$1 == "TOTAL" && $2 ~ /^[0-9]+$/ { print $2; exit }')"
[[ "$pss_kb" =~ ^[0-9]+$ ]] || { echo "::error::dumpsys meminfo printed no TOTAL" >&2; exit 1; }

apk_mb="$(awk -v b="$apk_bytes" 'BEGIN { printf "%.1f", b / 1048576 }')"
pss_mb="$(awk -v k="$pss_kb" 'BEGIN { printf "%.1f", k / 1024 }')"

# Everything from here on goes to the log and, in Actions, the job summary.
exec > >(tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}")

failed=0
row() { # name value ceiling unit
  local verdict="pass"
  if awk -v v="$2" -v c="$3" 'BEGIN { exit !(v > c) }'; then verdict="**over**"; failed=1; fi
  echo "| $1 | $2 $4 | $3 $4 | $verdict |"
}
echo "### Android performance budgets"
echo
echo "| Budget | Measured | Ceiling | Result |"
echo "|---|---:|---:|---|"
row "APK size" "$apk_mb" "$MAX_APK_MB" "MB"
row "Cold start (median of $LAUNCHES)" "$median" "$MAX_COLD_START_MS" "ms"
row "Memory after launch (total PSS)" "$pss_mb" "$MAX_PSS_MB" "MB"
echo
echo "Cold starts: $(tr '\n' ' ' <<< "$sorted")ms"

if (( failed )); then
  echo "::error::A performance budget was exceeded. See the table in the job summary." >&2
  exit 1
fi
