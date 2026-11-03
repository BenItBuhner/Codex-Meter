#!/usr/bin/env bash
# Builds Glasse's Liquid Glass library (github.com/BenItBuhner/Glasse, packages/android/liquidglass)
# at a commit and copies it into vendor/m2, where the app build resolves it for the Liquid Glass
# design. Glasse is a private repository, so JitPack can't build it for us.
#
#   android/ci/vendor-liquidglass.sh <commit> [path to a Glasse checkout]
#
# The version is the commit's short hash, as JitPack would name it. After running, set the same
# version on the liquidglass dependency in app/build.gradle.kts and drop the old vendored copy.
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
(cd "$ANDROID" && VERSION="$VERSION" ./gradlew -q :liquidglass:publishToMavenLocal -x lint \
  -Dmaven.repo.local="$REPO")

SRC_DIR="$REPO/com/github/BenItBuhner/Glasse/liquidglass/$VERSION"
DEST="$ROOT/vendor/m2/com/github/BenItBuhner/Glasse/liquidglass/$VERSION"
mkdir -p "$DEST"
cp "$SRC_DIR"/liquidglass-"$VERSION".{aar,pom,module} "$DEST"/
echo "Vendored com.github.BenItBuhner.Glasse:liquidglass:$VERSION"
