#!/usr/bin/env bash
# Builds out/codex-meter_<version>_<amd64|arm64>.deb from out/publish-<arch>.
# The .NET runtime is bundled; GTK 4 and libadwaita come from the system (Ubuntu 24.04+, Debian 13+).
# Usage: packaging/build-deb.sh x64|arm64
set -euo pipefail
ARCH="${1:?usage: build-deb.sh x64|arm64}"
LINUX="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISH="$LINUX/out/publish-$ARCH"
VERSION="$(cat "$PUBLISH/VERSION")"
case "$ARCH" in x64) DEB_ARCH=amd64 ;; arm64) DEB_ARCH=arm64 ;; *) echo "unknown arch $ARCH" >&2; exit 1 ;; esac
# Debian sorts "~" before anything, so 2.9.0~alpha.10 < 2.9.0.
DEB_VERSION="${VERSION/-/\~}"
ROOT="$LINUX/out/deb-$ARCH"
rm -rf "$ROOT"
mkdir -p "$ROOT/DEBIAN" "$ROOT/usr/lib/codex-meter" "$ROOT/usr/bin" "$ROOT/usr/share/applications" "$ROOT/usr/share/metainfo"
cp -a "$PUBLISH/." "$ROOT/usr/lib/codex-meter/"
rm -rf "$ROOT/usr/lib/codex-meter/icons" "$ROOT/usr/lib/codex-meter/VERSION"
ln -s ../lib/codex-meter/codex-meter "$ROOT/usr/bin/codex-meter"
cp "$LINUX/data/io.github.benitbuhner.CodexMeter.desktop" "$ROOT/usr/share/applications/"
cp "$LINUX/data/io.github.benitbuhner.CodexMeter.metainfo.xml" "$ROOT/usr/share/metainfo/"
cp -a "$LINUX/data/icons" "$ROOT/usr/share/"
mkdir -p "$ROOT/usr/share/doc/codex-meter"
cp "$LINUX/../LICENSE" "$ROOT/usr/share/doc/codex-meter/copyright"
INSTALLED_SIZE="$(du -sk "$ROOT/usr" | cut -f1)"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: codex-meter
Version: $DEB_VERSION
Architecture: $DEB_ARCH
Maintainer: Codex Meter contributors <noreply@github.com>
Installed-Size: $INSTALLED_SIZE
Depends: libadwaita-1-0 (>= 1.5), libgtk-4-1 (>= 4.12), libsecret-1-0, adwaita-icon-theme, libssl3t64 | libssl3, libc6 (>= 2.35), libstdc++6, zlib1g
Recommends: gnome-keyring | kwalletmanager | keepassxc
Section: utils
Priority: optional
Homepage: https://github.com/BenItBuhner/Codex-Meter
Description: See your Codex, Claude Code and Cursor usage limits
 Codex Meter shows the usage limits of your coding agents: Codex 5-hour and
 weekly windows and banked resets, Claude Code limits, and Cursor usage.
 Unofficial; not affiliated with OpenAI, Anthropic or Anysphere.
CONTROL
find "$ROOT" -type d -exec chmod 755 {} +
OUT="$LINUX/out/codex-meter_${DEB_VERSION}_${DEB_ARCH}.deb"
dpkg-deb --build --root-owner-group -Zxz "$ROOT" "$OUT"
echo "$OUT"
