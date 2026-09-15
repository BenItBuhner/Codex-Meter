#!/usr/bin/env bash
# Records the offline demo tour (CodexMeterUITests/DemoGalleryTests) on an
# iPhone simulator. The test writes its stills and `.tour-started` /
# `.tour-finished` markers to /tmp/codex-meter-gallery on the host; recording
# runs only between the markers, so the video shows the app rather than the
# simulator booting or xcodebuild tearing down.
set -euo pipefail

DEVICE_NAME="${1:?device name required}"
GALLERY_DIR="${2:?gallery directory required}"
DERIVED_DATA="${3:-DerivedData}"

STAGING_DIR="/tmp/codex-meter-gallery"
START_MARKER="$STAGING_DIR/.tour-started"
END_MARKER="$STAGING_DIR/.tour-finished"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR" "$GALLERY_DIR"

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

echo "Recording gallery on $DEVICE_NAME ($UDID)"
xcrun simctl boot "$UDID" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl ui "$UDID" appearance light
xcrun simctl status_bar "$UDID" override \
  --time "9:41" \
  --dataNetwork wifi \
  --wifiMode active \
  --wifiBars 3 \
  --cellularMode active \
  --cellularBars 4 \
  --operatorName "CI" \
  --batteryState charged \
  --batteryLevel 100

VIDEO="$GALLERY_DIR/codex-meter-demo.mp4"
RECORD_PID=""
XCODEBUILD_PID=""

stop_recording() {
  if [[ -n "$RECORD_PID" ]] && kill -0 "$RECORD_PID" 2>/dev/null; then
    kill -INT "$RECORD_PID" 2>/dev/null || true
    wait "$RECORD_PID" 2>/dev/null || true
  fi
  RECORD_PID=""
}

cleanup() {
  stop_recording
  if [[ -n "$XCODEBUILD_PID" ]] && kill -0 "$XCODEBUILD_PID" 2>/dev/null; then
    kill "$XCODEBUILD_PID" 2>/dev/null || true
  fi
  xcrun simctl status_bar "$UDID" clear >/dev/null 2>&1 || true
}
trap cleanup EXIT

xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED_DATA" \
  -resultBundlePath GalleryResults.xcresult \
  -parallel-testing-enabled NO \
  -only-testing:CodexMeterUITests/DemoGalleryTests \
  test &
XCODEBUILD_PID=$!

while [[ ! -f "$START_MARKER" ]] && kill -0 "$XCODEBUILD_PID" 2>/dev/null; do
  sleep 1
done

if [[ -f "$START_MARKER" ]]; then
  xcrun simctl io "$UDID" recordVideo --codec=h264 --force --display=internal "$VIDEO" &
  RECORD_PID=$!
  while [[ ! -f "$END_MARKER" ]] && kill -0 "$XCODEBUILD_PID" 2>/dev/null; do
    sleep 1
  done
  sleep 1
  stop_recording
else
  echo "::warning::The gallery tour never started; nothing was recorded"
fi

set +e
wait "$XCODEBUILD_PID"
STATUS=$?
set -e
XCODEBUILD_PID=""

cp -f "$STAGING_DIR"/*.png "$GALLERY_DIR" 2>/dev/null || true

if [[ ! -s "$VIDEO" ]]; then
  echo "::error::Demo recording was not written to $VIDEO"
  exit 1
fi
if ! compgen -G "$GALLERY_DIR/*.png" >/dev/null; then
  echo "::warning::No gallery stills were written; the recording is still available"
fi
if [[ "$STATUS" -ne 0 ]]; then
  echo "::warning::Gallery tour finished with xcodebuild status $STATUS; publishing whatever was captured"
fi
