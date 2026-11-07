#!/usr/bin/env bash
# Prints the app version: the tag (v2.9.0-alpha.10 -> 2.9.0-alpha.10) on a tag build, otherwise the
# Android versionName, which is the version every platform releases under.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${GITHUB_REF_TYPE:-}" == "tag" && "${GITHUB_REF_NAME:-}" == v* ]]; then
  echo "${GITHUB_REF_NAME#v}"
else
  awk -F'"' '/versionName = "/ { print $2; exit }' "$ROOT/android/app/build.gradle.kts"
fi
