#!/usr/bin/env bash
# Publishes a self-contained build for one architecture into linux/out/publish-<arch>.
# Usage: packaging/publish.sh x64|arm64
set -euo pipefail
ARCH="${1:?usage: publish.sh x64|arm64}"
LINUX="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$("$LINUX/packaging/version.sh")"
OUT="$LINUX/out/publish-$ARCH"
rm -rf "$OUT"
dotnet publish "$LINUX/src/CodexMeter.Linux/CodexMeter.Linux.csproj" -c Release -r "linux-$ARCH" --self-contained \
  -p:Version="$VERSION" -p:DebugType=none -o "$OUT"
echo "$VERSION" > "$OUT/VERSION"
# Optional LTTng tracing hook: it needs liblttng-ust, which most desktops lack, and .NET runs without it.
rm -f "$OUT/libcoreclrtraceptprovider.so"
