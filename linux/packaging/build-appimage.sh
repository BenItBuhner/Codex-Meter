#!/usr/bin/env bash
# Builds out/CodexMeter-<version>-<x86_64|aarch64>.AppImage from out/publish-<arch>. GTK 4,
# libadwaita and their dependencies are bundled (linuxdeploy + its GTK plugin), so the AppImage also
# runs on distributions older than the .deb needs. Run on the oldest system the build supports
# (CI uses Ubuntu 24.04) with libgtk-4-dev and libadwaita-1-dev installed.
# Usage: packaging/build-appimage.sh x64|arm64
set -euo pipefail
ARCH="${1:?usage: build-appimage.sh x64|arm64}"
LINUX="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISH="$LINUX/out/publish-$ARCH"
VERSION="$(cat "$PUBLISH/VERSION")"
case "$ARCH" in x64) MACHINE=x86_64 ;; arm64) MACHINE=aarch64 ;; *) echo "unknown arch $ARCH" >&2; exit 1 ;; esac
LIBDIR="/usr/lib/$MACHINE-linux-gnu"

# Pinned tools (bump deliberately).
LINUXDEPLOY_TAG="1-alpha-20251107-1"
GTK_PLUGIN_COMMIT="7a3fbc31a9e5075073ff8790f26effbac5f84453"
TOOLS="$LINUX/out/tools"
mkdir -p "$TOOLS"
if [[ ! -x "$TOOLS/linuxdeploy-$MACHINE.AppImage" ]]; then
  curl -fsSL -o "$TOOLS/linuxdeploy-$MACHINE.AppImage" \
    "https://github.com/linuxdeploy/linuxdeploy/releases/download/$LINUXDEPLOY_TAG/linuxdeploy-$MACHINE.AppImage"
  chmod +x "$TOOLS/linuxdeploy-$MACHINE.AppImage"
fi
if [[ ! -x "$TOOLS/linuxdeploy-plugin-gtk.sh" ]]; then
  curl -fsSL -o "$TOOLS/linuxdeploy-plugin-gtk.sh" \
    "https://raw.githubusercontent.com/linuxdeploy/linuxdeploy-plugin-gtk/$GTK_PLUGIN_COMMIT/linuxdeploy-plugin-gtk.sh"
  chmod +x "$TOOLS/linuxdeploy-plugin-gtk.sh"
fi
export PATH="$TOOLS:$PATH"
# CI runners have no FUSE; the tools run fine extracted.
export APPIMAGE_EXTRACT_AND_RUN=1

APPDIR="$LINUX/out/AppDir-$ARCH"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib/codex-meter" "$APPDIR/usr/bin"
cp -a "$PUBLISH/." "$APPDIR/usr/lib/codex-meter/"
rm -f "$APPDIR/usr/lib/codex-meter/VERSION"
ln -s ../lib/codex-meter/codex-meter "$APPDIR/usr/bin/codex-meter"
mkdir -p "$APPDIR/usr/share/metainfo"
cp "$LINUX/data/io.github.benitbuhner.CodexMeter.metainfo.xml" "$APPDIR/usr/share/metainfo/"

# .NET loads GTK, libadwaita, libsecret and OpenSSL with dlopen, so name them for linuxdeploy explicitly;
# it then pulls in their dependencies (minus the libraries every distribution has).
LIBS=()
for lib in libadwaita-1.so.0 libgtk-4.so.1 libsecret-1.so.0 libssl.so.3 libcrypto.so.3 libgraphene-1.0.so.0 libpangocairo-1.0.so.0 libcairo-gobject.so.2 libharfbuzz.so.0; do
  LIBS+=(--library "$LIBDIR/$lib")
done

export DEPLOY_GTK_VERSION=4
# The bundled runtime's own native libraries (libSystem.Native etc.) need no stripping.
export NO_STRIP=1

# 1. Deploy the app, its libraries and GTK's modules, data and loaders.
(cd "$LINUX/out" && "linuxdeploy-$MACHINE.AppImage" \
  --appdir "$APPDIR" \
  --executable "$APPDIR/usr/lib/codex-meter/codex-meter" \
  --desktop-file "$LINUX/data/io.github.benitbuhner.CodexMeter.desktop" \
  --icon-file "$LINUX/data/icons/hicolor/256x256/apps/io.github.benitbuhner.CodexMeter.png" \
  "${LIBS[@]}" \
  --plugin gtk)

# 2. Trim and adjust.
# Media playback and printing aren't used; dropping their modules removes GStreamer, CUPS and Avahi.
rm -rf "$APPDIR"/usr/lib/gtk-4.0/*/media "$APPDIR"/usr/lib/gtk-4.0/*/printbackends
rm -f "$APPDIR"/usr/lib/libgst*.so* "$APPDIR"/usr/lib/libcups*.so* "$APPDIR"/usr/lib/libavahi*.so*
# The GTK plugin pins GTK_THEME (which breaks libadwaita's light/dark switching) and forces X11;
# keep X11 as the default but let users choose, and make dlopen() find the bundled libraries.
HOOK="$APPDIR/apprun-hooks/linuxdeploy-plugin-gtk.sh"
sed -i -e '/^export GTK_THEME=/d' -e 's/^export GDK_BACKEND=x11.*/export GDK_BACKEND="${GDK_BACKEND:-x11}"/' "$HOOK"
cat >> "$HOOK" <<'HOOKEOF'
# Codex Meter: .NET opens GTK, libadwaita, libsecret and OpenSSL by name, so they come from here.
export LD_LIBRARY_PATH="$APPDIR/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export CODEX_METER_APPDIR="$APPDIR"
HOOKEOF
# Adwaita's symbolic icons, for desktops whose icon theme lacks them.
if [[ -d /usr/share/icons/Adwaita/symbolic ]]; then
  mkdir -p "$APPDIR/usr/share/icons/Adwaita"
  cp /usr/share/icons/Adwaita/index.theme "$APPDIR/usr/share/icons/Adwaita/"
  cp -a /usr/share/icons/Adwaita/symbolic "$APPDIR/usr/share/icons/Adwaita/"
  mkdir -p "$APPDIR/usr/share/doc/adwaita-icon-theme"
  cp /usr/share/doc/adwaita-icon-theme/copyright "$APPDIR/usr/share/doc/adwaita-icon-theme/" 2>/dev/null || true
fi

# 3. Package.
export LINUXDEPLOY_OUTPUT_VERSION="$VERSION"
export OUTPUT="$LINUX/out/CodexMeter-$VERSION-$MACHINE.AppImage"
(cd "$LINUX/out" && "linuxdeploy-$MACHINE.AppImage" --appdir "$APPDIR" --output appimage)
echo "$OUTPUT"
