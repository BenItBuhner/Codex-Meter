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
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/RateLimitResetCredit.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditExpiryReminder.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/Pkce.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/JwtClaims.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptions.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingFlow.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OAuthBrowserPage.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseVersion.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/GitHubReleaseSource.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/GitHubRelease.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/GitHubReleaseParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseIntegrity.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarAutoStart.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseNotesMarkdown.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseUpdatePolicy.java" \
  "$ROOT/tests/ParserSelfTest.java"

java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.ParserSelfTest

# Source-level release checks.
grep -q 'VERSION_NAME = "2.3.0"' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'VERSION_CODE = 13' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'versionName = "2.3.0"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 13' "$ROOT/app/build.gradle.kts"
grep -q 'codex-meter-android/2.3.0' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'VERSION_NAME="2.3.0"' "$ROOT/build.sh"
grep -q 'BenItBuhner/Codex-Meter/releases?per_page=30' "$ROOT/app/build.gradle.kts" # pragma: allowlist secret
! grep -R -q 'thatjoshguy67/Codex-Meter' \
  "$ROOT/app/src" "$ROOT/app/build.gradle.kts"

grep -q 'android.permission.ACCESS_NETWORK_STATE' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.POST_NOTIFICATIONS' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.SCHEDULE_EXACT_ALARM' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android:scheme="codexmeter"' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'OnboardingActivity' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'ResetAlertReceiver' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.POST_PROMOTED_NOTIFICATIONS' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'NowBarActionReceiver' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'MY_PACKAGE_REPLACED' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.REQUEST_INSTALL_PACKAGES' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'ReleaseUpdateJobService' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'WidgetRepairJobService' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'UpdateInstallReceiver' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'ReleaseHistoryActivity' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'ReleaseNotesMarkdown.toHtml' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseNotesUi.java"
grep -q 'ReleaseNotesUi.create' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateActivity.java"
grep -q 'ReleaseNotesUi.create' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseHistoryActivity.java"
grep -q 'FIRST_IN_APP_UPDATE_VERSION = "2.3.0"' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseUpdatePolicy.java"
grep -q 'isIrreversible' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateActivity.java"
grep -q 'Open on GitHub' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateActivity.java"
grep -q 'Open on GitHub' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseHistoryActivity.java"
grep -q 'com.samsung.android.support.ongoing_activity' "$ROOT/app/src/main/AndroidManifest.xml"

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
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditExpiryScheduler.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditExpiryReceiver.java"
grep -q 'ResetCreditExpiryReceiver' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'reset_credit_expiry_times_ui' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q '"Use reset", useReset' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetNotificationManager.java"
grep -q 'EXTRA_PROMPT_USE_RESET' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditActivity.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarActionReceiver.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarPreferences.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarAutoStart.java"
grep -q 'Build.VERSION.SDK_INT >= 36' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'codex_live_monitor_v2' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NotificationManager.IMPORTANCE_DEFAULT' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'setSmallIcon(R.drawable.ic_notification)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
if grep -q 'android.ongoingActivityNoti.' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"; then
  echo "Legacy Samsung notification extras must not be mixed with Android Live Updates" >&2
  exit 1
fi
grep -q 'setDeleteIntent(stopIntent)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'FLAG_PROMOTED_ONGOING' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarManager.isPromoted' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'hasStoredActiveState' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarManager.onUsageUpdated' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageApi.java"
grep -q 'maybeAutoStart' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'now_bar_auto_start_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings.xml"
grep -q 'now_bar_threshold_ui' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'NowBarPreferences.isAutoStartEnabled' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'markSuppressedUntil' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarAutoStart.shouldStart' \
  "$ROOT/tests/ParserSelfTest.java"

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

for provider in \
  SamsungLockSquareWidget SamsungLockWideWidget \
  SamsungLockRingsSquareWidget SamsungLockRingsWideWidget \
  SamsungLockDialsSquareWidget SamsungLockDialsWideWidget \
  SamsungLockBarsSquareWidget SamsungLockBarsWideWidget \
  SamsungLockFiveHourWidget SamsungLockWeeklyWidget; do
  grep -q "android:name=\"dev.bennett.codexmeter.$provider\"" \
    "$ROOT/app/src/main/AndroidManifest.xml"
  grep -q "$provider.class" \
    "$ROOT/app/src/main/java/dev/bennett/codexmeter/SamsungLockWidgetSupport.java"
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
grep -q 'titlePaint.setColor(foreground);' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
grep -q 'resetPaint.setColor(foreground);' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
grep -q 'showsResetCountdown' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWindow.java"
grep -q '!usageWindow.showsResetCountdown()' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageFormat.java"
! grep -q 'resetPaint.setColor(Ui.secondaryText(dark));' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
! grep -q 'titlePaint.setColor(0xFF000000)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"

echo "Parser, updater, OAuth, onboarding, reset-credit, alert, and widget source checks passed."
