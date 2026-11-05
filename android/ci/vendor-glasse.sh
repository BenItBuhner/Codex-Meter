#!/usr/bin/env bash
# Builds Glassé (github.com/BenItBuhner/Glasse, packages/android) at a commit and copies the
# modules the Liquid Glass design uses into vendor/m2, where the app build resolves them:
#   glasskit       the Skia UI kit the glass screens are written with (:glass)
#   glasse-assets  its fonts and images
#   glasse-android the Android host that draws the kit (GlasseHost)
#   glasse-demo-kt the demo app and capture harness, for the :glass screenshot tests only
# Glassé is a private repository, so JitPack can't build it for us.
#
#   android/ci/vendor-glasse.sh <commit> [path to a Glassé checkout]
#
# The version is the commit's short hash, as JitPack would name it. After running, set the same
# version in app/build.gradle.kts and glass/build.gradle.kts and drop the old vendored copies.
# Needs JDK 17+ and an Android SDK (ANDROID_SDK_ROOT) with platform 37.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT="${1:?Glasse commit to build}"
SRC="${2:-}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ -z "$SRC" ]]; then
  git clone --quiet https://github.com/BenItBuhner/Glasse "$WORK/glasse"
  SRC="$WORK/glasse"
fi
git -C "$SRC" checkout --quiet "$COMMIT"
VERSION="$(git -C "$SRC" rev-parse --short=10 HEAD)"

ANDROID="$SRC/packages/android"
[[ -f "$ANDROID/local.properties" ]] || echo "sdk.dir=${ANDROID_SDK_ROOT:?set ANDROID_SDK_ROOT}" > "$ANDROID/local.properties"
REPO="$WORK/m2"
MODULES=(glasskit glasse-assets glasse-android glasse-demo-kt)
TASKS=()
for module in "${MODULES[@]}"; do TASKS+=(":$module:publishToMavenLocal"); done
(cd "$ANDROID" && VERSION="$VERSION" ./gradlew -q "${TASKS[@]}" -x lint -Dmaven.repo.local="$REPO")

for module in "${MODULES[@]}"; do
  FROM="$REPO/com/github/BenItBuhner/Glasse/$module/$VERSION"
  DEST="$ROOT/vendor/m2/com/github/BenItBuhner/Glasse/$module/$VERSION"
  mkdir -p "$DEST"
  cp "$FROM"/* "$DEST"/
  echo "Vendored com.github.BenItBuhner.Glasse:$module:$VERSION"
done
