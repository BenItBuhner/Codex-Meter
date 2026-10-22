#!/usr/bin/env bash
# Builds the Apple Watch app, installs it on a watch simulator and saves one screenshot per
# screen: the first-run state, each demo provider page, a signed-out provider and the
# complication gallery. Demo data comes from the app's `-watch-demo` launch argument.
set -euo pipefail

GALLERY_DIR="${1:?gallery directory required}"
DERIVED_DATA="${2:-DerivedDataWatch}"
BUNDLE_ID="com.bukovinafilip.CodexMeter.watchkitapp"
mkdir -p "$GALLERY_DIR"

pick_watch() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
data = json.load(sys.stdin)
best = None
for runtime, devices in data.get("devices", {}).items():
    if "watchOS" not in runtime:
        continue
    for device in devices:
        if not device.get("isAvailable"):
            continue
        name = device.get("name", "")
        score = ("Ultra" in name, "46mm" in name or "49mm" in name, runtime)
        if best is None or score > best[0]:
            best = (score, device["udid"], name)
if best:
    print(best[1] + "|" + best[2])
'
}

WATCH="$(pick_watch || true)"
if [[ -z "$WATCH" ]]; then
  echo "No watch simulator found; downloading the watchOS platform"
  xcodebuild -downloadPlatform watchOS
  RUNTIME="$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r.get("platform") == "watchOS" and r.get("isAvailable")]
print(runtimes[-1]["identifier"] if runtimes else "")
')"
  DEVICE_TYPE="$(xcrun simctl list devicetypes -j | python3 -c '
import json, sys
types = [t for t in json.load(sys.stdin)["devicetypes"] if "Apple Watch" in t["name"]]
print(types[-1]["identifier"] if types else "")
')"
  xcrun simctl create "CI Watch" "$DEVICE_TYPE" "$RUNTIME" >/dev/null
  WATCH="$(pick_watch)"
fi
UDID="${WATCH%%|*}"
NAME="${WATCH#*|}"
echo "Using watch simulator: $NAME ($UDID)"

xcodebuild -project CodexMeter.xcodeproj -scheme CodexMeterWatch \
  -destination "platform=watchOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= \
  build

APP="$(find "$DERIVED_DATA/Build/Products" -maxdepth 2 -name CodexMeterWatch.app -path '*watchsimulator*' | head -n 1)"
test -d "$APP"
test -d "$APP/PlugIns/CodexMeterWatchWidgets.appex"

xcrun simctl boot "$UDID" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time "9:41" >/dev/null 2>&1 || true
xcrun simctl install "$UDID" "$APP"

shot() {
  local name="$1"; shift
  xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$UDID" "$BUNDLE_ID" "$@" >/dev/null
  sleep 6
  xcrun simctl io "$UDID" screenshot "$GALLERY_DIR/$name.png" >/dev/null
  echo "Saved $name.png"
}

shot 01-waiting-for-iphone
shot 02-codex -watch-demo -watch-provider codex
shot 03-claude-code -watch-demo -watch-provider claude_code
shot 04-cursor -watch-demo -watch-provider cursor
shot 05-copilot -watch-demo -watch-provider copilot
shot 06-signed-out -watch-demo -watch-provider kimi
shot 07-complications-codex -watch-demo -watch-provider codex -watch-complications
shot 08-complications-claude -watch-demo -watch-provider claude_code -watch-complications
xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
