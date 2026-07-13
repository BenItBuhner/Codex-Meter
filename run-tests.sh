#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JSON_JAR="${JSON_JAR:-}"

if [[ -z "$JSON_JAR" ]]; then
  for candidate in /mnt/data/toolcache/json-20250517.jar "$ROOT/build/test-libs/json-20250517.jar"; do
    if [[ -f "$candidate" ]]; then JSON_JAR="$candidate"; break; fi
  done
fi

if [[ -z "$JSON_JAR" ]]; then
  CACHE="$ROOT/build/test-libs"
  mkdir -p "$CACHE"
  JSON_JAR="$CACHE/json-20250517.jar"
  if [[ -n "${CAAS_ARTIFACTORY_MAVEN_REGISTRY:-}" && -n "${CAAS_ARTIFACTORY_READER_USERNAME:-}" ]]; then
    curl -fsSL -u "${CAAS_ARTIFACTORY_READER_USERNAME}:${CAAS_ARTIFACTORY_READER_PASSWORD}" \
      "https://${CAAS_ARTIFACTORY_MAVEN_REGISTRY}/org/json/json/20250517/json-20250517.jar" \
      -o "$JSON_JAR"
  else
    curl -fsSL "https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar" -o "$JSON_JAR"
  fi
fi

OUT="$ROOT/build/tests"
rm -rf "$OUT" && mkdir -p "$OUT"

javac -encoding UTF-8 -cp "$JSON_JAR" -d "$OUT" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWindow.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageSnapshot.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/CelebrationDetector.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/Pkce.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/JwtClaims.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptions.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingFlow.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OAuthBrowserPage.java" \
  "$ROOT/tests/ParserSelfTest.java"

java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.ParserSelfTest

# Source-level release checks.
grep -q 'VERSION_NAME = "2.0.1"' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'VERSION_CODE = 10' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'versionName = "2.0.1"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 10' "$ROOT/app/build.gradle.kts"

grep -q 'android.permission.ACCESS_NETWORK_STATE' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.POST_NOTIFICATIONS' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.SCHEDULE_EXACT_ALARM' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android:scheme="codexmeter"' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'OnboardingActivity' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'ResetAlertReceiver' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'MY_PACKAGE_REPLACED' "$ROOT/app/src/main/AndroidManifest.xml"

grep -q 'app:expanded="true"' "$ROOT/app/src/main/res/layout/activity_settings.xml"
grep -q 'app:expandable="true"' "$ROOT/app/src/main/res/layout/activity_settings.xml"
grep -q 'Ui.configureReachToolbar(toolbar, "Settings", true);' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'toolbar.setExpandable(false);' "$ROOT/app/src/main/java/dev/bennett/codexmeter/Ui.java"
grep -q 'toolbar.setExpandable(true);' "$ROOT/app/src/main/java/dev/bennett/codexmeter/Ui.java"
grep -q 'toolbar.setExpanded(true, false);' "$ROOT/app/src/main/java/dev/bennett/codexmeter/Ui.java"

test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetAlertPreferences.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetAlertScheduler.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetAlertReceiver.java"
grep -q 'scheduleFromSnapshot' "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetAlertScheduler.java"
grep -q 'POST_NOTIFICATIONS' "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"

grep -R -q '<Chronometer' "$ROOT/app/src/main/res/layout/widget_lock_"*.xml
grep -q 'setChronometerCountDown' "$ROOT/app/src/main/java/dev/bennett/codexmeter/SamsungLockWidgetSupport.java"
grep -q 'show_countdown' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppPreferences.java"
grep -q 'Show live time until reset' "$ROOT/app/src/main/java/dev/bennett/codexmeter/LockWidgetConfigActivity.java"

for style in rings dials bars; do
  for shape in square wide; do
    test -f "$ROOT/app/src/main/res/layout/widget_lock_${style}_${shape}.xml"
    test -f "$ROOT/app/src/main/res/xml/samsung_lock_${style}_${shape}_info.xml"
    test -f "$ROOT/app/src/main/res/xml/samsung_lock_${style}_${shape}_oneui_info.xml"
  done
done

grep -R -q 'android:widgetCategory="0x2000"' "$ROOT/app/src/main/res/xml/samsung_lock_"*_info.xml
grep -R -q 'app:widgetStyle="monotone"' "$ROOT/app/src/main/res/xml/samsung_lock_"*_info.xml
! grep -R -q 'com.samsung.systemui.permission.FACE_WIDGET' "$ROOT/app/src/main"

grep -q 'RESET_CREDITS_CONSUME_URL' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'ResetCreditActivity' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'showResetAction' "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptions.java"
grep -q 'dev.oneuiproject.oneui.widget.CardItemView' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingActivity.java"
grep -q 'Ui.nativePrimaryButton' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingActivity.java"
grep -q 'OAuthBrowserPage.render' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OAuthService.java"

echo "Parser, OAuth, onboarding, reset-credit, countdown, alert, and Samsung widget source checks passed."
