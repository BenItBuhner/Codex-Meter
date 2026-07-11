#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "Set ANDROID_SDK_ROOT to an Android SDK containing Platform 36 and current command-line tools." >&2
  exit 2
fi
LINT="$SDK_ROOT/cmdline-tools/latest/bin/lint"
CLASSES="$ROOT/build/release/classes.jar"
if [[ ! -x "$LINT" ]]; then
  echo "Missing Android lint at $LINT." >&2
  exit 2
fi
if [[ ! -f "$CLASSES" ]]; then
  echo "Run ./build.sh first so build/release/classes.jar exists." >&2
  exit 2
fi

PROJECT="$ROOT/build/lint-project"
REPORT="$ROOT/dist/lint-report.txt"
rm -rf "$PROJECT/src" "$PROJECT/res"
mkdir -p "$PROJECT/src" "$PROJECT/res" "$PROJECT/libs" "$ROOT/dist"
cp -a "$ROOT/app/src/main/java/." "$PROJECT/src/"
cp -a "$ROOT/app/src/main/res/." "$PROJECT/res/"
cp "$ROOT/app/src/main/AndroidManifest.xml" "$PROJECT/AndroidManifest.xml"
cp "$CLASSES" "$PROJECT/libs/app-classes.jar"
printf 'target=android-36\n' > "$PROJECT/project.properties"
rm -f "$REPORT"

ANDROID_SDK_ROOT="$SDK_ROOT" "$LINT" \
  --quiet --exitcode \
  --sdk-home "$SDK_ROOT" \
  --compile-sdk-version 36 \
  --java-language-level 8 \
  --classpath "$PROJECT/libs/app-classes.jar" \
  --text "$REPORT" \
  "$PROJECT"
cat "$REPORT"
