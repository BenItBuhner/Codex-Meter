#!/usr/bin/env bash
# The fast check: plain-JVM self-tests for the parsers, policies and state the app shares, then the
# manifest and resource rules in ci/resource-checks.py. No Android SDK needed. Code that needs a
# Context is tested with Robolectric (./gradlew :app:testDebugUnitTest); version sync lives in
# ../release-invariants.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JSON_JAR="${JSON_JAR:-}"

if [[ -z "$JSON_JAR" ]]; then
  for candidate in /mnt/data/toolcache/json-20260814.jar "$ROOT/build/test-libs/json-20260814.jar"; do
    if [[ -f "$candidate" ]]; then JSON_JAR="$candidate"; break; fi
  done
fi

if [[ -z "$JSON_JAR" ]]; then
  # Gradle already resolved the same version for :shared on any machine that has built.
  JSON_JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.json/json/20260814" \
    -name json-20260814.jar 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$JSON_JAR" ]]; then
  CACHE="$ROOT/build/test-libs"
  mkdir -p "$CACHE"
  JSON_JAR="$CACHE/json-20260814.jar"
  JAR_PATH="org/json/json/20260814/json-20260814.jar"
  if [[ -n "${CAAS_ARTIFACTORY_MAVEN_REGISTRY:-}" && -n "${CAAS_ARTIFACTORY_READER_USERNAME:-}" ]]; then
    curl -fsSL -u "${CAAS_ARTIFACTORY_READER_USERNAME}:${CAAS_ARTIFACTORY_READER_PASSWORD}" \
      "https://${CAAS_ARTIFACTORY_MAVEN_REGISTRY}/$JAR_PATH" -o "$JSON_JAR"
  else
    # Maven Central rate-limits busy CI runners (HTTP 429); retry, then use Google's mirror of it.
    curl -fsSL --retry 4 --retry-delay 5 "https://repo1.maven.org/maven2/$JAR_PATH" -o "$JSON_JAR" \
      || curl -fsSL --retry 4 --retry-delay 5 \
        "https://maven-central.storage-download.googleapis.com/maven2/$JAR_PATH" -o "$JSON_JAR"
  fi
fi

OUT="$ROOT/build/tests"
rm -rf "$OUT" && mkdir -p "$OUT"

javac -encoding UTF-8 -Xlint:all -Werror -cp "$JSON_JAR" -d "$OUT" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/Text.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageWindow.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageCredits.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/SpendControl.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageLimit.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/DashboardSections.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/HistorySections.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WidgetMeters.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageSnapshot.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageSample.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageHistory.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsagePace.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/PlanPricing.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/UsageStats.java" \
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
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/WatchMeters.java" \
  "$ROOT/shared/src/main/java/dev/bennett/codexmeter/wear/WearProvidersState.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/CelebrationDetector.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageRefill.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/RefillGuard.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/RateLimitResetCredit.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditsSnapshot.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditExpiryReminder.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditExpiryApply.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ResetCreditLowUsageAutoUse.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/Pkce.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/JwtClaims.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetOptions.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OnboardingFlow.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OAuthBrowserPage.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseVersion.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/GitHubReleaseSource.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/GitHubRelease.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/GitHubReleaseParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateChannel.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseIntegrity.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseNotesMarkdown.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ReleaseUpdatePolicy.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateCheckFrequency.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DiagnosticSanitizer.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/SettingsTransfer.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UsageProvider.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/MonitorProviderPicker.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WidgetProviders.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ClaudeCodeApi.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ClaudeUsageParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ClaudeSavedResets.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ClaudeResetAutoUse.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/CursorApi.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/CursorUsageParser.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ProviderCatalog.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/OpenCodeFree.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/AppSessionRules.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ProviderTabs.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ProviderUsageParsers.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DeviceAuth.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/AuthTokens.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ModelSpeed.java" \
  "$ROOT/tests/ModelSpeedSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ModelDiscovery.java" \
  "$ROOT/tests/ModelDiscoverySelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/PriorityView.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/PrioritySummaryRules.java" \
  "$ROOT/tests/PriorityViewSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/PriorityNote.java" \
  "$ROOT/tests/PriorityNoteSelfTest.java" \
  "$ROOT/tests/UsageRefillSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ProviderEvents.java" \
  "$ROOT/tests/ProviderEventsSelfTest.java" \
  "$ROOT/tests/RefillGuardSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ProviderStatus.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/ProviderStatusSources.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/StatusPollPolicy.java" \
  "$ROOT/tests/ProviderStatusSelfTest.java" \
  "$ROOT/tests/ParserSelfTest.java" \
  "$ROOT/tests/ProviderParsersSelfTest.java" \
  "$ROOT/tests/WatchProvidersSelfTest.java" \
  "$ROOT/tests/PayloadBudgetSelfTest.java"

java -ea -Dcodex.fixtures="$ROOT/tests/fixtures" -cp "$OUT:$JSON_JAR" \
  dev.bennett.codexmeter.ParserSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.ProviderParsersSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.WatchProvidersSelfTest
java -ea -Dcodex.fixtures="$ROOT/tests/fixtures" -cp "$OUT:$JSON_JAR" \
  dev.bennett.codexmeter.PayloadBudgetSelfTest
java -ea -Dcodex.fixtures="$ROOT/tests/fixtures" -cp "$OUT:$JSON_JAR" \
  dev.bennett.codexmeter.ModelSpeedSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.ModelDiscoverySelfTest
java -ea -Dcodex.fixtures="$ROOT/tests/fixtures" -cp "$OUT:$JSON_JAR" \
  dev.bennett.codexmeter.ProviderStatusSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.PriorityViewSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.PriorityNoteSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.UsageRefillSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.ProviderEventsSelfTest
java -ea -cp "$OUT:$JSON_JAR" dev.bennett.codexmeter.RefillGuardSelfTest

javac -encoding UTF-8 -Xlint:all -Werror -d "$OUT" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/DesignIds.java" \
  "$ROOT/tests/DesignIdsSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/RefreshTracker.java" \
  "$ROOT/tests/RefreshTrackerSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/UpdateFlow.java" \
  "$ROOT/tests/UpdateFlowSelfTest.java" \
  "$ROOT/app/src/main/java/dev/bennett/codexmeter/WhatsNew.java" \
  "$ROOT/tests/WhatsNewSelfTest.java"
java -ea -cp "$OUT" dev.bennett.codexmeter.DesignIdsSelfTest
java -ea -cp "$OUT" dev.bennett.codexmeter.RefreshTrackerSelfTest
java -ea -cp "$OUT" dev.bennett.codexmeter.UpdateFlowSelfTest
java -ea -cp "$OUT" dev.bennett.codexmeter.WhatsNewSelfTest
python3 "$ROOT/ci/resource-checks.py" "$ROOT"
