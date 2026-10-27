#!/usr/bin/env bash
# Runs CodexMeterUITests/IPadLayoutTests on one iPad simulator from an existing
# build-for-testing in DerivedData, then copies the stills the test wrote to
# /tmp/codex-meter-ipad-gallery into GALLERY_DIR/<slug>/.
#
#   ci/record-ipad-gallery.sh "iPad Pro 13-inch (M5)" ci-ipad-gallery light DerivedData
set -euo pipefail

DEVICE_NAME="${1:?device name required}"
GALLERY_DIR="${2:?gallery directory required}"
APPEARANCE="${3:-light}"
DERIVED_DATA="${4:-DerivedData}"

STAGING_DIR="/tmp/codex-meter-ipad-gallery"
SLUG="$(printf '%s-%s' "$DEVICE_NAME" "$APPEARANCE" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/-*$//')"
OUT_DIR="$GALLERY_DIR/$SLUG"
RESULT_BUNDLE="iPadResults-$SLUG.xcresult"
rm -rf "$STAGING_DIR" "$RESULT_BUNDLE"
mkdir -p "$STAGING_DIR" "$OUT_DIR"

UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
want = sys.argv[1]
data = json.load(sys.stdin)
for devices in data.get("devices", {}).values():
    for device in devices:
        if device.get("isAvailable") and device.get("name") == want:
            print(device["udid"])
            raise SystemExit(0)
raise SystemExit(f"No available simulator named {want!r}")
' "$DEVICE_NAME")"

echo "Running iPad layout tests on $DEVICE_NAME ($UDID), $APPEARANCE appearance"
xcrun simctl boot "$UDID" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl ui "$UDID" appearance "$APPEARANCE"
xcrun simctl status_bar "$UDID" override \
  --time "9:41" \
  --dataNetwork wifi \
  --wifiMode active \
  --wifiBars 3 \
  --batteryState charged \
  --batteryLevel 100

set +e
xcodebuild test-without-building \
  -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED_DATA" \
  -resultBundlePath "$RESULT_BUNDLE" \
  -parallel-testing-enabled NO \
  -only-testing:CodexMeterUITests/IPadLayoutTests \
  -retry-tests-on-failure -test-iterations 2 \
  -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 420 \
  -maximum-test-execution-time-allowance 420
STATUS=$?
set -e

cp -f "$STAGING_DIR"/*.png "$OUT_DIR" 2>/dev/null || true
# XCUIScreen captures the panel in its native portrait orientation; turn the
# landscape stills upright so they read like the device.
for still in "$OUT_DIR"/*landscape*.png "$OUT_DIR"/14-*.png; do
  [[ -f "$still" ]] || continue
  width="$(sips -g pixelWidth "$still" | awk '/pixelWidth/ {print $2}')"
  height="$(sips -g pixelHeight "$still" | awk '/pixelHeight/ {print $2}')"
  if [[ "$height" -gt "$width" ]]; then
    sips -r 270 "$still" >/dev/null
  fi
done
# The iPhone Duo stills show a simulated phone window centered on the iPad screen
# (466 x 678 pt folded, 951 x 669 pt open, at 2x); crop them to the window plus a
# 24 pt margin so they read like the phone.
for still in "$OUT_DIR"/*duo-folded*.png; do
  [[ -f "$still" ]] && sips -c 1452 1028 "$still" >/dev/null
done
for still in "$OUT_DIR"/*duo-open*.png; do
  [[ -f "$still" ]] && sips -c 1434 1998 "$still" >/dev/null
done
xcrun simctl status_bar "$UDID" clear >/dev/null 2>&1 || true
xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true

if ! compgen -G "$OUT_DIR/*.png" >/dev/null; then
  echo "::warning::No iPad stills were written for $DEVICE_NAME"
fi
exit "$STATUS"
