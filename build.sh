#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION_NAME="1.7.0"
VERSION_CODE="8"
BUILD="$ROOT/build/release"
DIST="$ROOT/dist"
TOOLCACHE="${CODEX_METER_TOOLCACHE:-$ROOT/../toolcache}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

mkdir -p "$DIST"
rm -rf "$BUILD"
mkdir -p "$BUILD/generated" "$BUILD/classes" "$BUILD/dex" "$BUILD/signtool"

AAPT2="${AAPT2:-}"
LINK_FRAMEWORK="${ANDROID_LINK_FRAMEWORK:-}"
ANDROID_JAR="${ANDROID_JAR:-}"
D8=""
APKSIGNER=""

if [[ -n "$SDK_ROOT" ]]; then
  [[ -n "$AAPT2" ]] || AAPT2="$(find "$SDK_ROOT/build-tools" -type f -name aapt2 2>/dev/null | sort | tail -1 || true)"
  [[ -n "$ANDROID_JAR" ]] || ANDROID_JAR="$SDK_ROOT/platforms/android-36/android.jar"
  [[ -n "$LINK_FRAMEWORK" ]] || LINK_FRAMEWORK="$ANDROID_JAR"
  if [[ -n "$AAPT2" ]]; then
    BT="$(dirname "$AAPT2")"
    [[ -x "$BT/d8" ]] && D8="$BT/d8"
    [[ -x "$BT/apksigner" ]] && APKSIGNER="$BT/apksigner"
  fi
fi

[[ -n "$AAPT2" ]] || AAPT2="$TOOLCACHE/apktool-extract/prebuilt/linux/aapt2"
[[ -n "$LINK_FRAMEWORK" ]] || LINK_FRAMEWORK="$TOOLCACHE/apktool-extract/prebuilt/android-framework.jar"
[[ -n "$ANDROID_JAR" ]] || ANDROID_JAR="$TOOLCACHE/android-all-16.jar"
DX_JAR="${DX_JAR:-$TOOLCACHE/builder.jar}"
DX_DEP_JAR="${DX_DEP_JAR:-$TOOLCACHE/apktool-cli-3.0.2.jar}"
APKSIG_JAR="${APKSIG_JAR:-$TOOLCACHE/apksig-2.3.0.jar}"

for required in "$AAPT2" "$LINK_FRAMEWORK" "$ANDROID_JAR"; do
  [[ -f "$required" ]] || { echo "Missing build dependency: $required" >&2; exit 2; }
done
chmod +x "$AAPT2"

"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/resources.zip"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$LINK_FRAMEWORK" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$BUILD/generated" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/resources.zip"

find "$ROOT/app/src/main/java" "$BUILD/generated" -name '*.java' -print0 | \
  xargs -0 javac \
    -encoding UTF-8 \
    -source 8 -target 8 \
    -classpath "$ANDROID_JAR" \
    -Xlint:all -Xlint:-options -Xmaxwarns 20 \
    -d "$BUILD/classes"

if [[ -n "$D8" ]]; then
  jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
  "$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"
else
  [[ -f "$DX_JAR" ]] || { echo "Missing D8 and legacy dx jar: $DX_JAR" >&2; exit 2; }
  [[ -f "$DX_DEP_JAR" ]] || { echo "Missing legacy dx dependency jar: $DX_DEP_JAR" >&2; exit 2; }
  # The source deliberately avoids lambdas/default-interface methods. Java 8 javac emits
  # otherwise Java-7-compatible bytecode; patching only the classfile marker lets legacy dx
  # process it in minimal/offline build environments.
  python3 "$ROOT/tools/patch_class_version.py" "$BUILD/classes"
  jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
  if javap -classpath "$BUILD/classes" -c dev.bennett.codexmeter.MainActivity 2>/dev/null | grep -q invokedynamic; then
    echo "Unexpected invokedynamic bytecode; a modern D8 installation is required." >&2
    exit 2
  fi
  java -cp "$DX_JAR:$DX_DEP_JAR" com.android.dx.command.Main \
    --dex --output="$BUILD/dex/classes.dex" "$BUILD/classes.jar"
fi

python3 "$ROOT/tools/assemble_aligned_apk.py" \
  "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/aligned-unsigned.apk"

if [[ -n "${CODEX_METER_KEYSTORE:-}" ]]; then
  KEYSTORE="$CODEX_METER_KEYSTORE"
  KEY_ALIAS="${CODEX_METER_KEY_ALIAS:-codexmeter}"
  STORE_PASS="${CODEX_METER_STORE_PASS:?Set CODEX_METER_STORE_PASS}"
  KEY_PASS="${CODEX_METER_KEY_PASS:-$STORE_PASS}"
else
  SIGNING_DIR="$ROOT/.local-signing"
  mkdir -p "$SIGNING_DIR"
  KEYSTORE="$SIGNING_DIR/codex-meter-local.jks"
  PASS_FILE="$SIGNING_DIR/password"
  if [[ ! -f "$KEYSTORE" ]]; then
    openssl rand -hex 24 > "$PASS_FILE"
    chmod 600 "$PASS_FILE"
    STORE_PASS="$(cat "$PASS_FILE")"
    keytool -genkeypair \
      -storetype JKS \
      -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -alias codexmeter -keyalg RSA -keysize 3072 -validity 10000 \
      -dname "CN=Codex Meter Local Build, OU=Personal Android App, O=Local Build" \
      >/dev/null 2>&1
  else
    STORE_PASS="$(cat "$PASS_FILE")"
  fi
  KEY_ALIAS="codexmeter"
  KEY_PASS="$STORE_PASS"
fi

OUT="$DIST/CodexMeter-$VERSION_NAME.apk"
rm -f "$OUT" "$OUT.idsig"
if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --v4-signing-enabled false \
    --out "$OUT" "$BUILD/aligned-unsigned.apk"
  rm -f "$OUT.idsig"
  "$APKSIGNER" verify --verbose --print-certs "$OUT"
else
  [[ -f "$APKSIG_JAR" ]] || { echo "Missing APK signer: $APKSIG_JAR" >&2; exit 2; }
  javac -cp "$APKSIG_JAR" -d "$BUILD/signtool" "$ROOT/tools/ApkSignTool.java"
  JAVA_EXPORTS=(
    --add-exports java.base/sun.security.x509=ALL-UNNAMED
    --add-exports java.base/sun.security.pkcs=ALL-UNNAMED
  )
  java "${JAVA_EXPORTS[@]}" -cp "$BUILD/signtool:$APKSIG_JAR" ApkSignTool sign \
    "$KEYSTORE" "$KEY_ALIAS" "$STORE_PASS" "$KEY_PASS" \
    "$BUILD/aligned-unsigned.apk" "$OUT"
  java "${JAVA_EXPORTS[@]}" -cp "$BUILD/signtool:$APKSIG_JAR" ApkSignTool verify "$OUT"
fi

python3 - "$OUT" <<'PY'
import sys, zipfile
path=sys.argv[1]
with zipfile.ZipFile(path) as z:
    names=set(z.namelist())
    assert 'AndroidManifest.xml' in names
    assert 'resources.arsc' in names
    assert 'classes.dex' in names
print('APK contents verified.')
PY

sha256sum "$OUT" | tee "$DIST/SHA256SUMS.txt"
echo "Built $OUT"
