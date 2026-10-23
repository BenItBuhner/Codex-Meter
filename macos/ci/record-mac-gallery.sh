#!/usr/bin/env bash
# Runs the Mac demo tour (CodexMeterMacUITests/MacGalleryTests) and collects its stills.
# The tests write PNGs to /tmp/codex-meter-mac-gallery and also attach them to the result
# bundle; the attachments are exported as a fallback if the staging folder stays empty.
set -euo pipefail

GALLERY_DIR="${1:?gallery directory required}"
DERIVED_DATA="${2:-DerivedData}"
shift 2 || true
SIGNING_ARGS=("$@")

STAGING_DIR="/tmp/codex-meter-mac-gallery"
RESULT_BUNDLE="MacGalleryResults.xcresult"
rm -rf "$STAGING_DIR" "$RESULT_BUNDLE"
mkdir -p "$STAGING_DIR" "$GALLERY_DIR"

set +e
xcodebuild test-without-building \
  -project CodexMeterMac.xcodeproj -scheme CodexMeterMac \
  -destination 'platform=macOS' \
  -derivedDataPath "$DERIVED_DATA" \
  -resultBundlePath "$RESULT_BUNDLE" \
  -only-testing:CodexMeterMacUITests/MacGalleryTests \
  -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 600 \
  -maximum-test-execution-time-allowance 600 \
  "${SIGNING_ARGS[@]}"
STATUS=$?
set -e

cp -f "$STAGING_DIR"/*.png "$GALLERY_DIR" 2>/dev/null || true

if ! compgen -G "$GALLERY_DIR/*.png" >/dev/null; then
  echo "Staging folder was empty; exporting attachments from $RESULT_BUNDLE"
  xcrun xcresulttool export attachments --path "$RESULT_BUNDLE" --output-path "$GALLERY_DIR/attachments" || true
fi

if [[ "$STATUS" -ne 0 ]]; then
  echo "::warning::Mac gallery tour finished with xcodebuild status $STATUS; publishing whatever was captured"
fi
ls -1 "$GALLERY_DIR"
