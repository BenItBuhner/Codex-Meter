#!/usr/bin/env bash
set -euo pipefail

DEVICE_NAME="${1:?device name required}"
GALLERY_DIR="${2:?gallery directory required}"
DERIVED_DATA="${3:-DerivedData}"

mkdir -p "$GALLERY_DIR"

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
xcrun simctl io "$UDID" recordVideo --codec=h264 --force --display=internal "$VIDEO" &
RECORD_PID=$!

cleanup() {
  if kill -0 "$RECORD_PID" 2>/dev/null; then
    kill -INT "$RECORD_PID" 2>/dev/null || true
    wait "$RECORD_PID" 2>/dev/null || true
  fi
  xcrun simctl status_bar "$UDID" clear >/dev/null 2>&1 || true
}
trap cleanup EXIT

sleep 2

set +e
xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeter \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED_DATA" \
  -resultBundlePath GalleryResults.xcresult \
  -parallel-testing-enabled NO \
  -only-testing:CodexMeterUITests/DemoGalleryTests \
  TEST_RUNNER_GALLERY_OUTPUT="$GALLERY_DIR" \
  test
STATUS=$?
set -e

cleanup
trap - EXIT

{
  echo "Codex Meter iOS demo gallery"
  echo
  echo "Open codex-meter-demo.mp4 for the full tour."
  echo "Numbered PNGs are stills of each screen, in tour order."
  echo
  echo "Stills:"
  ls -1 "$GALLERY_DIR"/*.png 2>/dev/null | xargs -n1 basename || true
} > "$GALLERY_DIR/HOW_TO_VIEW.txt"

python3 - "$GALLERY_DIR" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
stills = sorted(root.glob("*.png"))
video = root / "codex-meter-demo.mp4"
rows = []
for still in stills:
    rows.append(
        f'<figure><img src="{still.name}" alt="{still.stem}">'
        f"<figcaption>{still.stem}</figcaption></figure>"
    )
video_tag = (
    f'<p><video src="{video.name}" controls playsinline></video></p>'
    if video.exists()
    else "<p>Video was not produced.</p>"
)
root.joinpath("index.html").write_text(
    """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Codex Meter iOS demo gallery</title>
  <style>
    body { font-family: ui-sans-serif, system-ui, sans-serif; margin: 24px; max-width: 920px; }
    video, img { width: 100%; max-width: 390px; height: auto; border-radius: 12px; }
    figure { display: inline-block; margin: 0 16px 24px 0; vertical-align: top; }
    figcaption { font-size: 13px; color: #444; margin-top: 6px; }
  </style>
</head>
<body>
  <h1>Codex Meter iOS demo gallery</h1>
  <p>Screen recording of the offline demo tour, then stills in visit order.</p>
  %s
  <h2>Stills</h2>
  %s
</body>
</html>
"""
    % (video_tag, "\n  ".join(rows)),
    encoding="utf-8",
)
PY

if [[ ! -s "$VIDEO" ]]; then
  echo "::error::Demo recording was not written to $VIDEO"
  exit 1
fi
if ! compgen -G "$GALLERY_DIR/*.png" >/dev/null; then
  echo "::error::No gallery stills were written to $GALLERY_DIR"
  exit 1
fi
exit "$STATUS"
