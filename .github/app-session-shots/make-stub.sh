#!/usr/bin/env bash
# make-stub.sh PACKAGE LABEL OUT.apk — a code-free stand-in app with one launcher screen.
set -euo pipefail
PKG="$1"; LABEL="$2"; OUT="$3"
BT="$ANDROID_HOME/build-tools/36.0.0"; JAR="$ANDROID_HOME/platforms/android-36/android.jar"
W="$(mktemp -d)"
cat > "$W/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
  <application android:label="$LABEL" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true"
        android:label="$LABEL" android:theme="@android:style/Theme.Material.Light">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
XML
"$BT/aapt2" link -o "$W/u.apk" -I "$JAR" --manifest "$W/AndroidManifest.xml"
"$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
keytool -genkeypair -keystore "$W/k.jks" -storepass android -keypass android -alias s -keyalg RSA -dname CN=stub -validity 30 >/dev/null 2>&1
"$BT/apksigner" sign --ks "$W/k.jks" --ks-pass pass:android --out "$OUT" "$W/a.apk"
