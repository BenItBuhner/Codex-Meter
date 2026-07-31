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
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageWindow.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageCredits.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageLimit.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/DashboardSections.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WidgetMeters.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageSnapshot.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageSample.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageHistory.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsagePace.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/AdaptiveRefreshPolicy.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarAutoStart.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarDisplayMode.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarPercentMode.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarCopy.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSyncPaths.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSyncStatus.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSettingsState.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearUsageState.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearMonitorState.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSurfaceMode.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WearGlanceFormat.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/CelebrationDetector.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/RateLimitResetCredit.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditsSnapshot.java" \
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
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseNotesMarkdown.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseUpdatePolicy.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateCheckFrequency.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransfer.java" \
  "$ROOT/tests/ParserSelfTest.java"

java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.ParserSelfTest

# Source-level release checks.
grep -q 'VERSION_NAME = "2.6.8"' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'VERSION_CODE = 26' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'versionName = "2.6.8"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 26' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "2.6.8"' "$ROOT/wear/build.gradle.kts"
grep -q 'versionCode = 26' "$ROOT/wear/build.gradle.kts"
grep -q 'codex-meter-android/2.6.8' "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppConstants.java"
grep -q 'VERSION_NAME="2.6.8"' "$ROOT/build.sh"
WORKFLOW="$ROOT/../.github/workflows/build-apk.yml"
grep -Fq 'release-dist/CodexMeter-Wear-$VERSION_NAME.apk' "$WORKFLOW"
grep -Fq '"platforms;android-37.0"' "$WORKFLOW"
grep -q 'BenItBuhner/Codex-Meter/releases?per_page=30' "$ROOT/app/build.gradle.kts" # pragma: allowlist secret
! grep -R -q 'thatjoshguy67/Codex-Meter' \
  "$ROOT/app/src" "$ROOT/app/build.gradle.kts"

# Dashboard reorder + usage-credit / reset-credit auto-hide wiring.
grep -q 'testUsageCreditsAutoHide' "$ROOT/tests/ParserSelfTest.java"
grep -q 'testResetCreditsAutoHide' "$ROOT/tests/ParserSelfTest.java"
grep -q 'testDashboardSectionOrder' "$ROOT/tests/ParserSelfTest.java"
grep -q 'DashboardReorderActivity' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'public boolean shouldDisplay()' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditsSnapshot.java"
grep -q 'DashboardSections.resolveOrder' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'ItemTouchHelper' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DashboardReorderActivity.java"
grep -q 'ic_oui_reorder' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DashboardReorderActivity.java"
grep -q 'dashboard_reorder_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_refresh_usage.xml"
grep -q 'dashboard_reorder_ui' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"

# Edit-dashboard visibility switches + sortable/hideable usage-history section.
grep -q 'USAGE_HISTORY = "usage_history"' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/DashboardSections.java"
grep -q 'SwitchCompat' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DashboardReorderActivity.java"
grep -q 'setSectionVisible' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DashboardReorderActivity.java"
grep -q 'DashboardSections.USAGE_HISTORY.equals(key)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'setDashboardSectionHidden' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppPreferences.java"
grep -q 'dashboard_usage_history' \
  "$ROOT/app/src/main/res/xml/preferences_settings_refresh_usage.xml"
grep -q 'dashboard_hidden_sections' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'hidden-section keys round-trip' "$ROOT/tests/ParserSelfTest.java"
# Usage-history charts still gate on real windows; the section itself stays when toggled on.
grep -q 'fiveWindow != null && snapshot.fetchedAtMillis > 0L' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'weeklyWindow != null && snapshot.fetchedAtMillis > 0L' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'fiveWindow != null && snapshot.fetchedAtMillis > 0L' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageHistoryActivity.java"
grep -q 'blank dash placeholder' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DashboardReorderActivity.java"
grep -q 'Keeps a blank placeholder when this window is missing' \
  "$ROOT/app/src/main/res/xml/preferences_settings_refresh_usage.xml"
# Toggled-on dashboard sections keep placeholders instead of auto-hiding.
grep -q 'showDashboardFiveHour(this))' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'showDashboardUsageCredits(this))' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'showDashboardResetCredits(this))' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
! grep -q 'shouldShowResetCreditsCard' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
! grep -q 'snapshot.usageCredits.shouldDisplay()' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
# Widget meter catalog excludes Spark / additional model limits; config supports drag reorder.
grep -q 'Model-specific additional limits' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WidgetMeters.java"
grep -q 'available meters exclude model-specific Spark limits' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'ItemTouchHelper' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
grep -q 'orderedSelectedMeters' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
grep -q 'ic_oui_reorder' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"

# Reset/usage-credit dashboard cards use bold in-card titles with left-aligned icon rows,
# matching the other dashboard cards, instead of external One UI separators or centered blocks.
grep -Fq 'Ui.text(this, "Reset credits", 18' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -Fq 'Ui.text(this, "Usage credits", 18' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'buildIconDetailRow' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'ic_oui_battery' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'ic_oui_credit_card_outline' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
! grep -q 'Ui.separator' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -Fq 'Ui.separator(this, "Available credits")' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditActivity.java"
grep -Fq 'Ui.separator(this, "Credit expirations")' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditActivity.java"
! grep -q 'ic_reset_credit_details' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
# Reset credits is an orderable dashboard section, not a card pinned below the dashboard.
grep -q 'RESET_CREDITS = "reset_credits"' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/DashboardSections.java"
grep -q 'DashboardSections.RESET_CREDITS.equals(key)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'DashboardSections.RESET_CREDITS.equals(key)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DashboardReorderActivity.java"
! grep -q 'this.content.addView(buildResetCreditsCard())' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"

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
grep -q 'UpdateCheckFrequency' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseUpdateScheduler.java"
grep -q 'notify_update_available_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_updates.xml"
grep -q 'update_check_interval_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_updates.xml"
grep -q 'settings_update_interval_entries' \
  "$ROOT/app/src/main/res/values/settings_arrays.xml"
grep -q 'EXTRA_START_INSTALL' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateActivity.java"
grep -q '"Open", open' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateNotificationManager.java"
grep -q '"Update", update' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateNotificationManager.java"
grep -q 'UpdateNotificationManager.onReleasesUpdated' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdatePreferences.java"
grep -q 'testUpdateCheckFrequency' "$ROOT/tests/ParserSelfTest.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateCheckFrequency.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateNotificationManager.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransfer.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'testSettingsTransfer' "$ROOT/tests/ParserSelfTest.java"
grep -q 'export_settings_transfer' \
  "$ROOT/app/src/main/res/xml/preferences_settings_transfer.xml"
grep -q 'import_settings_transfer' \
  "$ROOT/app/src/main/res/xml/preferences_settings_transfer.xml"
grep -q 'transfer_security_notice' \
  "$ROOT/app/src/main/res/xml/preferences_settings_transfer.xml"
grep -q 'SECURITY_WARNING' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransfer.java"
grep -q 'bindTransfer' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'SettingsTransferStore.collect' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'SettingsTransferStore.apply' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'Protect authentication exports' \
  "$ROOT/app/src/main/res/xml/preferences_settings_transfer.xml"
grep -q 'material_you' \
  "$ROOT/app/src/main/res/xml/preferences_settings_appearance.xml"
grep -q 'HorizontalRadioPreference' \
  "$ROOT/app/src/main/res/xml/preferences_settings_appearance.xml"
grep -q 'theme_system_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_appearance.xml"
grep -q 'preferences_darkmode_entries_image' \
  "$ROOT/app/src/main/res/values/settings_arrays.xml"
grep -q 'HorizontalRadioPreference theme' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
test -f "$ROOT/app/src/main/res/drawable-xxhdpi/display_help_light_mode.webp"
test -f "$ROOT/app/src/main/res/drawable-xxhdpi/display_help_dark_mode.webp"
grep -q 'isMaterialYouEnabled' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppPreferences.java"
grep -q 'AppTheme_MaterialYou' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/Ui.java"
grep -q 'oneUiAccent' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/Ui.java"
grep -q 'oneui_primary' \
  "$ROOT/app/src/main/res/values/colors.xml"
grep -q '#0381FE' \
  "$ROOT/app/src/main/res/values/colors.xml"
grep -q 'material you preference restored' "$ROOT/tests/ParserSelfTest.java"
grep -q 'widgetOptionsFromJson(widget,' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'requireLeadTimes' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'applyNowBar && NowBarManager.isActive' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'AppPreferences.setLastError(app, message)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'partial widget keeps current theme' "$ROOT/tests/ParserSelfTest.java"
grep -q 'malformed lead times rejected' "$ROOT/tests/ParserSelfTest.java"
grep -q 'non-numeric lead time element rejected' "$ROOT/tests/ParserSelfTest.java"
grep -q 'parseLeadTimeEntry' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransfer.java"
python3 - <<PY
from pathlib import Path
text = (Path(r"""$ROOT""") / "app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java").read_text()
start = text.index("private static void applyNotifications")
end = text.index("private static void applyNowBar", start)
block = text[start:end]
assert "SettingsTransfer.requireLeadTimes" in block
assert block.index("SettingsTransfer.requireLeadTimes") < block.index("ResetAlertPreferences.save")
print("notification import validates lead times before saving.")
PY
grep -q 'com.samsung.android.support.ongoing_activity' "$ROOT/app/src/main/AndroidManifest.xml"

grep -q 'app:expanded="true"' "$ROOT/app/src/main/res/layout/activity_settings.xml"
grep -q 'app:expandable="true"' "$ROOT/app/src/main/res/layout/activity_settings.xml"
grep -q 'Ui.configureReachToolbar(toolbar, pageTitle(page), true);' \
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
test -f "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarAutoStart.java"
test -f "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarDisplayMode.java"
test -f "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarPercentMode.java"
test -f "$ROOT/shared/src/main/java/dev/bennett/codexmeter/NowBarCopy.java"
grep -q 'NowBarCopy.focusCriticalText' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarCopy.limitText' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarCopy.chipExpandedText' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarCopy.focusCriticalText' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'NowBarCopy.wearLimitText' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'testNowBarCopy' "$ROOT/tests/ParserSelfTest.java"
grep -q 'exhausted five-hour focus shows hours until natural reset' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'Build.VERSION.SDK_INT >= 36' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'codex_live_monitor_v2' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NotificationManager.IMPORTANCE_DEFAULT' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'setSmallIcon(R.drawable.ic_notification)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'ic_now_bar_progress_dot' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'ic_codex_logo_on_accent' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'themeAdaptiveCodexLogo' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'ic_codex_logo_dark' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
test -f "$ROOT/app/src/main/res/drawable/ic_now_bar_progress_dot.xml"
test -f "$ROOT/app/src/main/res/drawable/ic_codex_logo.xml"
test -f "$ROOT/app/src/main/res/drawable/ic_codex_logo_dark.xml"
test -f "$ROOT/app/src/main/res/drawable/ic_codex_logo_on_accent.xml"
# Do not rely on night-qualified logos for Samsung extras (SystemUI may mismatch).
! test -f "$ROOT/app/src/main/res/drawable-night/ic_codex_logo.xml"
# Progress tracker must be a plain circle, not the brand glyph.
grep -q 'M12,2c5.523,0 10,4.477 10,10' \
  "$ROOT/app/src/main/res/drawable/ic_now_bar_progress_dot.xml"
# Codex logo vectors must stay transparent (no baked white square background).
! grep -q 'android:pathData="M19.503 0H4.496' \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo.xml" \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo_dark.xml" \
  "$ROOT/app/src/main/res/drawable/ic_notification.xml" \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo_on_accent.xml"
# Keep SVG arc flags explicitly separated so SystemUI's VectorDrawable parser can load them.
grep -q 'android:pathData="M 8.086,0.457 a 6.105,6.105 0 0,1' \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo.xml" \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo_dark.xml" \
  "$ROOT/app/src/main/res/drawable/ic_notification.xml" \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo_on_accent.xml"
! grep -q 'android:pathData="M8.086.457' \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo.xml" \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo_dark.xml" \
  "$ROOT/app/src/main/res/drawable/ic_notification.xml" \
  "$ROOT/app/src/main/res/drawable/ic_codex_logo_on_accent.xml"
grep -q 'fillType="evenOdd"' \
  "$ROOT/app/src/main/res/drawable/ic_notification.xml"
grep -q '#FF111111' "$ROOT/app/src/main/res/drawable/ic_codex_logo.xml"
grep -q '#FFFFFFFF' "$ROOT/app/src/main/res/drawable/ic_codex_logo_dark.xml"
grep -q 'android.ongoingActivityNoti.' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'applySamsungCompatibility' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarDisplayMode.ANDROID_LIVE_UPDATE' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'setDeleteIntent(stopIntent)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'FLAG_PROMOTED_ONGOING' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'legacyColorizedFallback' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'builder.setColorized(true)' \
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
  "$ROOT/app/src/main/res/xml/preferences_settings_now_bar.xml"
grep -q 'now_bar_threshold_ui' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'NowBarPreferences.isAutoStartEnabled' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'markSuppressedUntil' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'NowBarAutoStart.shouldStart' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'NowBarDisplayMode.resolve' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'NowBarPercentMode.resolveFocus' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'NowBarPercentMode.triggeredFocus' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'NowBarPercentMode.focusForSettingsChange' \
  "$ROOT/tests/ParserSelfTest.java"
grep -q 'KEY_AUTO_TRIGGER_FOCUS' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'sessionAutoTriggerFocus' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'focusForSettingsChange' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
# applyPercentModeChange must stop the monitor when post() fails (no zombie active state).
python3 - <<PY
from pathlib import Path
root = Path(r"""$ROOT""")
text = (root / "app/src/main/java/dev/bennett/codexmeter/NowBarManager.java").read_text()
start = text.index("public static synchronized boolean applyPercentModeChange")
end = text.index("public static synchronized void stop(Context context)", start)
block = text[start:end]
assert "if (post(context, snapshot, until, preview)) return true;" in block
assert "stop(context, false);" in block
assert "return false;" in block
print("applyPercentModeChange stops on post failure.")
PY
grep -q 'now_bar_display_mode_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_now_bar.xml"
grep -q 'now_bar_percent_mode_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_now_bar.xml"
grep -q 'NowBarPreferences.getPercentMode' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'applyPercentModeChange' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q 'KEY_FOCUS_METRIC' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'Live notifications for all apps' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"

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
grep -q 'OPACITY_LEVELS = {56, 88, 100}' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptions.java"
grep -q 'widget_background' "$ROOT/app/src/main/res/values/strings.xml"
grep -q 'backgroundSwitch' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
grep -q 'RadioItemViewGroup' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OneUiChoiceDialog.java"
grep -q 'OneUiChoiceDialog.show' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
grep -q '"Both windows", "5-hour only", "Weekly only"' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptionCatalog.java"
# Home widget layout picker exposes Auto / Dials / Progress bars.
grep -q '"Adaptive by size", "Dials", "Progress bars"' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptionCatalog.java"
grep -q 'styleSpinner' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
# Home and lock config drive the shared meter catalog for per-meter visibility.
grep -q 'WidgetMeters' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
grep -q 'WidgetMeters' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/LockWidgetConfigActivity.java"
grep -q 'selectedMeters' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/LockWidgetConfigActivity.java"
# Renderer honors the layout preference and binds ordered meter slots.
grep -q 'resolveHomeVisualStyle' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WidgetMeters.java"
grep -q 'resolveSlots' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetRenderer.java"
! grep -q 'ListPopupWindow' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetConfigActivity.java"
grep -q 'android:max="2"' "$ROOT/app/src/main/res/layout/view_widget_opacity.xml"
! grep -q 'opacity_tick_3' "$ROOT/app/src/main/res/layout/view_widget_opacity.xml"
grep -q 'One UI widget opacity uses three levels' "$ROOT/tests/ParserSelfTest.java"
grep -q 'dev.oneuiproject.oneui.widget.CardItemView' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingActivity.java"
grep -q 'Ui.nativePrimaryButton' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingActivity.java"
grep -q 'Ui.addSpacer(this.content, 20)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingActivity.java"
python3 - <<PY
from pathlib import Path
text = (Path(r"""$ROOT""") / "app/src/main/res/xml/preferences_settings.xml").read_text()
about = text.index('android:key="about_codex_meter"')
assert text.rfind('InsetPreferenceCategory', 0, about) != -1, "missing inset before About"
assert text.find('InsetPreferenceCategory', about) != -1, "missing bottom inset after About"
assert 'app:height="28dp"' in text[about:], "bottom settings inset should be 28dp"
print("settings list keeps spacing before About and bottom padding after it.")
PY
grep -q 'OAuthBrowserPage.render' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OAuthService.java"
grep -q 'titlePaint.setColor(foreground);' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
grep -q 'resetPaint.setColor(foreground);' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
grep -q 'showsResetCountdown' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageWindow.java"
grep -q 'testUsagePace' "$ROOT/tests/ParserSelfTest.java"
grep -q 'UsagePace.mostAcceleratedWindow' "$ROOT/tests/ParserSelfTest.java"
test -f "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsagePace.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsagePacePreferences.java"
test -f "$ROOT/app/src/debug/java/dev/bennett/codexmeter/UsagePaceDemoActivity.java"
grep -q 'UsagePaceDemoActivity' "$ROOT/app/src/debug/AndroidManifest.xml"
grep -q 'usage_pace_enabled_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_refresh_usage.xml"
grep -q 'usage_pace_sensitivity_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_refresh_usage.xml"
grep -q '<item>off</item>' \
  "$ROOT/app/src/main/res/values/settings_arrays.xml"
grep -q 'public static final String OFF = "off"' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsagePace.java"
grep -q 'areWarningsEnabled' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsagePacePreferences.java"
grep -q 'now_bar_accelerated_ui' \
  "$ROOT/app/src/main/res/xml/preferences_settings_now_bar.xml"
grep -q 'accelerated_enabled' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransferStore.java"
grep -q 'START_ACCELERATED' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/NowBarManager.java"
grep -q 'Color.rgb(230, 91, 23)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/Ui.java"
grep -q 'WARNING_WAVE_DURATION_MS = 950L' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
grep -q '"· " + pace' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
grep -q 'card.setMinimumHeight(Ui.dp(this, 103.0f))' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MainActivity.java"
grep -q 'onPaceSettingsChanged' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsActivity.java"
grep -q '!usageWindow.showsResetCountdown()' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageFormat.java"
! grep -q 'resetPaint.setColor(Ui.secondaryText(dark));' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"
! grep -q 'titlePaint.setColor(0xFF000000)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageWaveView.java"

# Wear OS companion module and phone↔watch sync contract.
test -f "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearMainActivity.java"
test -f "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearSettingsActivity.java"
test -f "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
test -f "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearDataLayerService.java"
test -f "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearPhoneSync.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/wear/PhoneWearSync.java"
test -f "$ROOT/app/src/main/java/dev/bennett/codexmeter/wear/PhoneWearListenerService.java"
test -f "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSyncStatus.java"
grep -q 'androidx.wear.ongoing.OngoingActivity' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'com.google.android.wearable.standalone' "$ROOT/wear/src/main/AndroidManifest.xml"
grep -q 'codex_meter_wear' "$ROOT/wear/src/main/res/values/wear.xml"
grep -q 'codex_meter_phone' "$ROOT/app/src/main/res/values/wear.xml"
grep -q 'PhoneWearListenerService' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'PhoneWearTrust.isTrustedWearMessage' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/wear/PhoneWearListenerService.java"
grep -q 'MSG_SYNC_NOW' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSyncPaths.java"
grep -q 'PATH_STATUS' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSyncPaths.java"
grep -q 'clearSnapshot(context, state.updatedAtMillis' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearPhoneSync.java"
! grep -Rq 'seedDemoSnapshot\\|demo_button\\|wear_load_demo' "$ROOT/wear/src/main"
grep -q 'android:icon="@mipmap/ic_launcher"' "$ROOT/wear/src/main/AndroidManifest.xml"
for density in mdpi xhdpi xxhdpi xxxhdpi; do
  cmp "$ROOT/app/src/main/res/drawable-${density}/codex_meter_adaptive_bg.png" \
    "$ROOT/wear/src/main/res/drawable-${density}/codex_meter_adaptive_bg.png"
  cmp "$ROOT/app/src/main/res/drawable-${density}/codex_meter_adaptive_fg.png" \
    "$ROOT/wear/src/main/res/drawable-${density}/codex_meter_adaptive_fg.png"
done
grep -q 'isTrustedWearSettings' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/wear/PhoneWearTrust.java"
grep -q 'package_name' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSettingsState.java"
grep -q 'Phone owns the refresh interval' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/wear/PhoneWearSync.java"
grep -q 'leaving desired state for retry' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
! grep -q 'AppPreferences.setRefreshMinutes(app, remote.refreshMinutes)' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/wear/PhoneWearSync.java"
! grep -q 'stop(context, false);\n        return false;' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"

grep -q 'isMonitorDesired' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'setMonitorDesired(context, true, false)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'markMonitorPosted(context, until)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'clearMonitorPosted(context)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
grep -q 'KEY_MONITOR_DESIRED' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearPreferences.java"
! grep -q 'setMonitorActive(context, true);' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearOngoingMonitor.java"
# Vendored SESL transitive deps keep phone Android CI working without GitHub Packages auth.
test -f "$ROOT/vendor/m2/sesl/androidx/appcompat/appcompat/1.7.1+1.0.21-sesl8+rev8/appcompat-1.7.1+1.0.21-sesl8+rev8.aar"
test -f "$ROOT/vendor/m2/sesl/com/google/android/material/material/1.12.0+1.0.32-sesl8+rev3/material-1.12.0+1.0.32-sesl8+rev3.aar"
grep -q ':wear:assembleRelease' "$ROOT/build.sh"
grep -q ':wear:lintRelease' "$ROOT/lint.sh"
grep -q 'WearPreferences.settingsState(this, 0L,' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearSettingsActivity.java"
! grep -nE 'monitorSwitch\.isChecked\(\),\s*$' -A1 \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearSettingsActivity.java" \
  | grep -qE '^\s*30,'


grep -q 'PhoneWearSync.pushUsage' \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageApi.java"
grep -q 'WearSurfaceMode.resolve' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSurfaceMode.java"
grep -q 'samsung_compatibility' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearSurfaceMode.java"
for provider in \
  UsageOverviewTileService FiveHourTileService WeeklyTileService \
  ResetCountdownTileService MonitorStatusTileService \
  FiveHourComplicationService WeeklyComplicationService \
  DualUsageComplicationService NextResetComplicationService; do
  test -f "$ROOT/wear/src/main/java/dev/bennett/codexmeter/${provider}.java"
  grep -q "$provider" "$ROOT/wear/src/main/AndroidManifest.xml"
done
grep -q 'WearSurfaceUpdater' "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearSurfaceUpdater.java"
grep -q 'BIND_TILE_PROVIDER' "$ROOT/wear/src/main/AndroidManifest.xml"
python3 - <<PY
from pathlib import Path
import xml.etree.ElementTree as ET

manifest = Path(r"""$ROOT""") / "wear/src/main/AndroidManifest.xml"
root = ET.parse(manifest).getroot()
android = "{http://schemas.android.com/apk/res/android}"
expected = {
    "dev.bennett.codexmeter.UsageOverviewTileService": "2x2",
    "dev.bennett.codexmeter.FiveHourTileService": "2x1",
    "dev.bennett.codexmeter.WeeklyTileService": "2x1",
    "dev.bennett.codexmeter.ResetCountdownTileService": "2x1",
    "dev.bennett.codexmeter.MonitorStatusTileService": "2x1",
}
services = {service.get(android + "name"): service for service in root.findall("application/service")}
for service_name, footprint in expected.items():
    metadata = {
        item.get(android + "name"): item.get(android + "value") or item.get(android + "resource")
        for item in services[service_name].findall("meta-data")
    }
    assert metadata["com.samsung.android.wearable.tiles.LAYOUT_TYPE"] == footprint
    assert metadata["com.samsung.android.wearable.tiles.category"] == \
        "com.samsung.android.wearable.tiles.category.CONNECTED_DEVICES"
    assert metadata["com.google.android.clockwork.tiles.MULTI_INSTANCES_SUPPORTED"] == "true"
    for suffix in ("CATEGORY_A", "CATEGORY_B", "CATEGORY_C"):
        assert "com.samsung.android.wearable.tiles.category." + suffix in metadata
print("Wear tile services declare Samsung modular footprints and picker categories.")
PY
grep -q 'new ColorBuilders.LinearGradient.Builder' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/CodexTileLayouts.java"
grep -q 'SWEEP_DEGREES = 270f' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/OneUiTileDial.java"
grep -q 'GRADIENT_START = 0xFF534FA7' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/CodexTileLayouts.java"
grep -q 'Typeface.create("sec"' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/OneUiTileText.java"
grep -Fq 'MAX_INLINE_IMAGE_BYTES = 10 * 1024' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/TileImageResources.java"
grep -q 'ARC_DIAMETER_DP = 52f' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/OneUiTileDial.java"
! grep -q 'InlineImageResource' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/OneUiTileDial.java"
grep -q 'setWidth(DimensionBuilders.expand())' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/CodexTileLayouts.java"
grep -q 'Stale phone data' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/CodexTileLayouts.java"
grep -q 'readPersisted(requestedVersion)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/CodexTileService.java"
grep -q 'TileImageResources.argb8888(bitmap)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/OneUiTileText.java"
grep -q 'new OneUiTileText(context, scope)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/CodexTileLayouts.java"
grep -q 'WearSurfaceUpdater.requestAll(context)' \
  "$ROOT/wear/src/main/java/dev/bennett/codexmeter/WearBootReceiver.java"
grep -q 'ACTION_COMPLICATION_UPDATE_REQUEST' "$ROOT/wear/src/main/AndroidManifest.xml"
grep -q 'WearGlanceFormat' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WearGlanceFormat.java"
grep -q 'One UI Watch' \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WearGlanceFormat.java"

echo "Parser, updater, OAuth, onboarding, reset-credit, alert, widget, and Wear sync source checks passed."
