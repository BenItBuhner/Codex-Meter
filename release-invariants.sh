#!/usr/bin/env bash
# Release invariants: things that must agree across files for a build to ship, but that no test of
# app behavior can see. The version comes from android/app/build.gradle.kts and every other
# touchpoint is checked against it, so release prep edits the touchpoints and never this script.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
A="$ROOT/android"
fail=0
bad() { echo "release-invariants: $*" >&2; fail=1; }
has() { grep -Fq -- "$2" "$1" || bad "$1 should contain: $2"; }

NAME="$(awk -F'"' '/versionName = "/ { print $2; exit }' "$A/app/build.gradle.kts")"
CODE="$(awk '/versionCode = / { print $3; exit }' "$A/app/build.gradle.kts")"
[[ -n "$NAME" && -n "$CODE" ]] || { echo "release-invariants: no versionName/versionCode in app/build.gradle.kts" >&2; exit 1; }

# One version everywhere it is written down.
has "$A/wear/build.gradle.kts" "versionName = \"$NAME\""
has "$A/wear/build.gradle.kts" "versionCode = $CODE"
CONSTANTS="$A/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
has "$CONSTANTS" "VERSION_NAME = \"$NAME\""
has "$CONSTANTS" "VERSION_CODE = $CODE"
has "$CONSTANTS" "codex-meter-android/$NAME"
has "$A/build.sh" "VERSION_NAME=\"$NAME\""
for props in "$ROOT/windows/Directory.Build.props" "$ROOT/linux/Directory.Build.props"; do
  has "$props" "<Version>$NAME</Version>"
done
# The Apple apps: numbers-only short version (2.9.0), plus the full version for About and the updater.
for project in "$ROOT/ios/CodexMeter.xcodeproj/project.pbxproj" "$ROOT/macos/CodexMeterMac.xcodeproj/project.pbxproj"; do
  if grep -E 'MARKETING_VERSION = ' "$project" | grep -vqF "MARKETING_VERSION = ${NAME%%-*};"; then
    bad "$project has a MARKETING_VERSION other than ${NAME%%-*}"
  fi
  if grep -E 'CODEX_METER_RELEASE_VERSION = ' "$project" | grep -vqF "CODEX_METER_RELEASE_VERSION = $NAME;" \
      || ! grep -qF "CODEX_METER_RELEASE_VERSION = $NAME;" "$project"; then
    bad "$project should set CODEX_METER_RELEASE_VERSION = $NAME everywhere"
  fi
done
grep -q "^## $NAME\b" "$ROOT/CHANGELOG.md" || bad "CHANGELOG.md has no '## $NAME' section; the release job needs its notes"

# The in-app updater reads one page of this repository's releases.
has "$A/app/build.gradle.kts" "BenItBuhner/Codex-Meter/releases?per_page=30" # pragma: allowlist secret

# One Glassé build everywhere, and it is vendored so builds never fetch it.
GLASSE_APP="$(sed -n 's/.*Glasse:glasse-android:\([0-9a-f]*\)".*/\1/p' "$A/app/build.gradle.kts")"
GLASSE_KIT="$(sed -n 's/^val glasse = "\([0-9a-f]*\)"/\1/p' "$A/glass/build.gradle.kts")"
if [[ -z "$GLASSE_APP" || "$GLASSE_APP" != "$GLASSE_KIT" ]]; then
  bad "Glassé pins differ: app $GLASSE_APP, glass $GLASSE_KIT"
else
  for m in glasskit glasse-assets glasse-android glasse-demo-kt; do
    [[ -d "$A/vendor/m2/com/github/BenItBuhner/Glasse/$m/$GLASSE_APP" ]] || bad "Glassé $m $GLASSE_APP is not vendored"
  done
fi

# Every pinned SESL artifact is vendored, since GitHub Packages needs credentials CI doesn't have.
while IFS='"' read -r _ module _ version _; do
  group="${module%%:*}"; artifact="${module#*:}"
  dir="$A/vendor/m2/${group//.//}/$artifact/$version"
  [[ -d "$dir" ]] || bad "SESL $module $version is not vendored in vendor/m2"
done < <(sed -n '/^val sesl9 = mapOf(/,/^)/p' "$A/app/build.gradle.kts" | grep ' to "')
ONEUI="$(sed -n 's/.*"io.github.tribalfs:oneui-design:\([^"]*\)".*/\1/p' "$A/app/build.gradle.kts")"
[[ -f "$A/vendor/m2/io/github/tribalfs/oneui-design/$ONEUI/oneui-design-$ONEUI.aar" ]] || bad "oneui-design $ONEUI is not vendored"

# CI installs the platforms the modules compile against, and ships the Wear APK.
WORKFLOW="$ROOT/.github/workflows/build-apk.yml"
for module in app wear; do
  level="$(sed -n 's/.*version = release(\([0-9]*\)).*/\1/p' "$A/$module/build.gradle.kts")"
  minor="$(sed -n 's/.*minorApiLevel = \([0-9]*\).*/\1/p' "$A/$module/build.gradle.kts")"
  has "$WORKFLOW" "\"platforms;android-$level.${minor:-0}\""
done
has "$WORKFLOW" 'release-dist/CodexMeter-Wear-$VERSION_NAME.apk'
has "$WORKFLOW" '--prerelease="$PRERELEASE"'
has "$A/build.sh" ':wear:assembleRelease'
has "$A/lint.sh" ':wear:lintRelease'

[[ "$fail" == 0 ]] || exit 1
echo "Release invariants hold for $NAME ($CODE)."
