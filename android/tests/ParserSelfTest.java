package dev.bennett.codexmeter;

import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSurfaceMode;
import dev.bennett.codexmeter.wear.WearSyncStatus;
import dev.bennett.codexmeter.wear.WearUsageState;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class ParserSelfTest {
    public static void main(String[] args) throws Exception {
        testStandardUsage();
        testMonthlyWindow();
        testWindowIdentification();
        testAdditionalLimits();
        testPrimaryLimitWinsOverAdditional();
        testOptionalUsageSections();
        testUsageCredits();
        testSpendControl();
        testSpendControlLivePayload();
        testUsageCreditsAutoHide();
        testResetCreditsAutoHide();
        testDashboardSectionOrder();
        testMalformedWindowIgnored();
        testZeroDurationWindowIgnored();
        testNextResetSelection();
        testCelebrationDetection();
        testResetCreditExpiryReminders();
        testResetCreditExpiryOrdering();
        testResetCountdownFollowsApiTimeline();
        testResetCreditExpiryAutoApply();
        testResetCreditLowUsageAutoUse();
        testClaudeSavedResets();
        testClaudeResetAutoUse();
        testLowUsageAlertDedup();
        testUsageHistory();
        testUsagePace();
        testPlanPricing();
        testUsageStats();
        testHistorySections();
        testAdaptiveRefreshPolicy();
        testNowBarAutoStart();
        testNowBarDisplayModes();
        testWearSurfaceModes();
        testWearSettingsState();
        testWearSyncState();
        testWearGlanceFormat();
        testNowBarPercentModes();
        testNowBarCopy();
        testJwtMerge();
        testPkce();
        testWidgetOptions();
        testWidgetMeters();
        testOnboardingFlow();
        testOAuthBrowserPage();
        testReleaseVersions();
        testGitHubReleases();
        testUpdateChannel();
        testReleaseChecksums();
        testReleaseNotesMarkdown();
        testReleaseUpdatePolicy();
        testUpdateCheckFrequency();
        testDiagnosticSanitizer();
        testSettingsTransfer();
        testUsageProviders();
        testWidgetProviders();
        testClaudeUsageParsing();
        testClaudeOAuthRequests();
        testCursorUsageParsing();
        testCursorAuth();
        System.out.println("All parser, updater, OAuth, onboarding, and widget-option self-tests passed.");
    }

    private static void testDiagnosticSanitizer() {
        String jwt = "abcdefghijklmnop.qrstuvwxyzABCDE.FGHIJKLMNOP";
        String input = "Authorization: Bearer top-secret "
                + "access_token=\"access-secret\" refresh_token=refresh-secret "
                + "Cookie: session=private-cookie email=user@example.com jwt=" + jwt + " "
                + "https://example.com/callback?code=oauth-code&state=oauth-state";
        String redacted = DiagnosticSanitizer.redact(input);
        check(!redacted.contains("top-secret"), "bearer token redacted");
        check(!redacted.contains("access-secret"), "access token redacted");
        check(!redacted.contains("refresh-secret"), "refresh token redacted");
        check(!redacted.contains("private-cookie"), "cookie redacted");
        check(!redacted.contains("user@example.com"), "email redacted");
        check(!redacted.contains(jwt), "JWT redacted");
        check(!redacted.contains("oauth-code"), "OAuth code redacted");
        check(!redacted.contains("oauth-state"), "OAuth state redacted");
        check(redacted.contains("[REDACTED]"), "redaction marker present");
        check("https://example.com:8443/path/to/resource".equals(
                        DiagnosticSanitizer.safeUrl(
                                "https://example.com:8443/path/to/resource?token=secret#fragment")),
                "safe URL strips query and fragment");
        check("The authorization server returned an error.".equals(
                        DiagnosticSanitizer.redact(
                                "The authorization server returned an error.")),
                "ordinary authorization wording remains readable");
        System.out.println("Diagnostic sanitizer strips credentials, identity, and URL queries.");
    }

    private static void testResetCountdownFollowsApiTimeline() {
        UsageWindow unusedNoReset = new UsageWindow(0, 18000L, 0L, 0L);
        UsageWindow unusedWithResetAt = new UsageWindow(0, 18000L, 0L, 2000000000L);
        UsageWindow unusedWithResetAfter = new UsageWindow(0, 18000L, 600L, 0L);
        UsageWindow usedNoReset = new UsageWindow(37, 18000L, 0L, 0L);
        UsageWindow usedWithReset = new UsageWindow(37, 18000L, 600L, 2000000000L);
        check(unusedNoReset.remainingPercent() == 100, "unused window remaining");
        check(!unusedNoReset.showsResetCountdown(),
                "unused window without API reset stays blank");
        check(unusedWithResetAt.showsResetCountdown(),
                "100% remaining still shows API reset_at");
        check(unusedWithResetAfter.showsResetCountdown(),
                "100% remaining still shows API reset_after");
        check(!usedNoReset.showsResetCountdown(),
                "used window without API reset stays blank");
        check(usedWithReset.showsResetCountdown(),
                "used window with API reset shows countdown");
        System.out.println("Reset countdown follows the API timeline, including at 100% remaining.");
    }

    private static void testLowUsageAlertDedup() {
        long windowSeconds = TimeUnit.HOURS.toSeconds(5);
        long reset = 2_000_000_000_000L;
        check(UsageWindow.shouldAnnounceLowUsage(0L, reset, windowSeconds),
                "first low-usage sighting announces");
        check(!UsageWindow.shouldAnnounceLowUsage(reset, reset, windowSeconds),
                "exact same reset does not re-announce");
        check(!UsageWindow.shouldAnnounceLowUsage(reset, reset + TimeUnit.SECONDS.toMillis(1),
                        windowSeconds),
                "one-second reset drift does not re-announce");
        check(!UsageWindow.shouldAnnounceLowUsage(reset, reset + TimeUnit.MINUTES.toMillis(1),
                        windowSeconds),
                "one-minute reset drift does not re-announce");
        check(!UsageWindow.shouldAnnounceLowUsage(reset, reset + TimeUnit.MINUTES.toMillis(14),
                        windowSeconds),
                "sub-tolerance drift does not re-announce");
        check(UsageWindow.shouldAnnounceLowUsage(reset,
                        reset + TimeUnit.HOURS.toMillis(5), windowSeconds),
                "next five-hour window announces again");
        check(UsageWindow.sameResetWindow(reset, windowSeconds,
                        reset + TimeUnit.SECONDS.toMillis(45), windowSeconds),
                "nearby resets are the same window");
        check(!UsageWindow.sameResetWindow(reset, windowSeconds,
                        reset + TimeUnit.HOURS.toMillis(5), windowSeconds),
                "hours-apart resets are distinct windows");
        long fetchedAt = reset - TimeUnit.HOURS.toMillis(2);
        UsageWindow relative = new UsageWindow(75, windowSeconds,
                TimeUnit.HOURS.toSeconds(2), 0L);
        long computed = relative.effectiveResetAtMillis(fetchedAt);
        check(computed == reset, "reset-after fallback yields absolute reset");
        check(!UsageWindow.shouldAnnounceLowUsage(reset,
                        relative.effectiveResetAtMillis(fetchedAt + 500L), windowSeconds),
                "millisecond fetch skew with reset-after does not re-announce");
        System.out.println("Low-usage alert dedupe: one shot per window despite reset drift.");
    }

    private static void testUsagePace() {
        long now = 2_000_000_000_000L;
        long hour = TimeUnit.HOURS.toMillis(1);
        long week = TimeUnit.DAYS.toMillis(7);
        UsageWindow fastWeekly = new UsageWindow(15, TimeUnit.DAYS.toSeconds(7), 0L,
                (now - hour + week) / 1000L);
        UsagePace.Assessment fast = UsagePace.assess(
                fastWeekly, now, now, UsagePace.BALANCED);
        check(fast.available, "weekly pace is available after a meaningful sample");
        check(fast.accelerated, "15% of a weekly quota in one hour is accelerated");
        check(Math.abs(fast.estimatedTotalMillis - TimeUnit.MINUTES.toMillis(400))
                        < TimeUnit.MINUTES.toMillis(1),
                "15 percent per hour projects roughly 6 hours 40 minutes total");
        UsageWindow fallbackWeekly = new UsageWindow(15, TimeUnit.DAYS.toSeconds(7),
                TimeUnit.DAYS.toSeconds(7) - TimeUnit.HOURS.toSeconds(1), 0L);
        UsagePace.Assessment fallback = UsagePace.assess(
                fallbackWeekly, now, now, UsagePace.BALANCED);
        check(fallback.available && fallback.accelerated,
                "reset-after fallback produces the same full-window pace");

        UsageWindow sameRateFiveHour = new UsageWindow(15, TimeUnit.HOURS.toSeconds(5), 0L,
                (now - hour + TimeUnit.HOURS.toMillis(5)) / 1000L);
        UsagePace.Assessment sustainable = UsagePace.assess(
                sameRateFiveHour, now, now, UsagePace.BALANCED);
        check(sustainable.available && !sustainable.accelerated,
                "same consumption rate lasts beyond a five-hour reset");

        UsageWindow pausedWeekly = new UsageWindow(15, TimeUnit.DAYS.toSeconds(7), 0L,
                (now + TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(12)) / 1000L);
        UsagePace.Assessment paused = UsagePace.assess(
                pausedWeekly, now, now, UsagePace.BALANCED);
        check(paused.available && !paused.accelerated,
                "idle time remains in the full-window average and reduces noise");

        UsageWindow borderline = new UsageWindow(55, TimeUnit.HOURS.toSeconds(5), 0L,
                (now + TimeUnit.HOURS.toMillis(3)) / 1000L);
        check(UsagePace.assess(borderline, now, now, UsagePace.SENSITIVE).accelerated,
                "sensitive policy warns before reset");
        check(UsagePace.assess(borderline, now, now, UsagePace.BALANCED).accelerated,
                "balanced policy warns at 75 percent projected coverage");
        check(!UsagePace.assess(borderline, now, now, UsagePace.RELAXED).accelerated,
                "relaxed policy requires a more severe shortfall");
        UsagePace.Assessment offWarning = UsagePace.assess(
                fastWeekly, now, now, UsagePace.OFF);
        check(offWarning.available && !offWarning.accelerated,
                "off sensitivity keeps estimates without accelerated warnings");
        check(!UsagePace.warningsEnabled(UsagePace.OFF),
                "off sensitivity disables warning triggers");

        UsageWindow tinySample = new UsageWindow(4, TimeUnit.DAYS.toSeconds(7), 0L,
                (now - hour + week) / 1000L);
        check(!UsagePace.assess(tinySample, now, now, UsagePace.BALANCED).available,
                "balanced policy suppresses low-percentage sample noise");
        check(UsagePace.assess(tinySample, now, now, UsagePace.SENSITIVE).available,
                "sensitive policy accepts earlier samples");

        UsageSnapshot both = new UsageSnapshot("pro", true, false, sameRateFiveHour,
                fastWeekly, now);
        check(UsagePace.mostAcceleratedWindow(both, now, UsagePace.BALANCED)
                        == UsagePace.WINDOW_WEEKLY,
                "accelerated window selection identifies weekly quota");
        check(UsagePace.mostAcceleratedWindow(both, now, UsagePace.OFF)
                        == UsagePace.WINDOW_NONE,
                "off sensitivity never selects an accelerated window");
        UsageWindow fastMonthly = new UsageWindow(15, TimeUnit.DAYS.toSeconds(30), 0L,
                (now - 12L * hour + TimeUnit.DAYS.toMillis(30)) / 1000L);
        UsageSnapshot monthlyOnly = new UsageSnapshot("free", true, false, null, null,
                fastMonthly, java.util.Collections.emptyList(), null, -1, now);
        check(UsagePace.mostAcceleratedWindow(monthlyOnly, now, UsagePace.BALANCED)
                        == UsagePace.WINDOW_MONTHLY,
                "accelerated window selection covers the monthly free-tier quota");
        check(UsagePace.BALANCED.equals(UsagePace.normalizeSensitivity("invalid")),
                "invalid sensitivity falls back to balanced");
        check(UsagePace.OFF.equals(UsagePace.normalizeSensitivity(UsagePace.OFF)),
                "off sensitivity is preserved");
        check(!UsagePace.assess(fastWeekly, now, fast.resetAtMillis, UsagePace.BALANCED).available,
                "expired windows do not produce stale warnings");
        System.out.println("Usage-pace demo: 15% of a week in one hour projects about 6h 40m.");
    }

    private static void testUsageHistory() throws Exception {
        long start = 2_000_000_000_000L;
        long reset = start + TimeUnit.HOURS.toMillis(5);
        UsageHistory history = UsageHistory.empty(UsageHistory.FIVE_HOUR);
        history = history.append(new UsageWindow(5, TimeUnit.HOURS.toSeconds(5), 0L,
                reset / 1000L), start + TimeUnit.MINUTES.toMillis(5));
        history = history.append(new UsageWindow(5, TimeUnit.HOURS.toSeconds(5), 0L,
                reset / 1000L), start + TimeUnit.MINUTES.toMillis(6));
        check(history.samples.size() == 1, "near-identical history samples are coalesced");
        history = history.append(new UsageWindow(20, TimeUnit.HOURS.toSeconds(5), 0L,
                reset / 1000L), start + TimeUnit.MINUTES.toMillis(20));
        check(history.currentWindowSamples().size() == 2, "same-window samples are retained");
        check(history.observedBurnRate() > 0d, "sustained usage produces an observed burn rate");

        UsageWindow current = new UsageWindow(20, TimeUnit.HOURS.toSeconds(5), 0L,
                reset / 1000L);
        long observed = start + TimeUnit.MINUTES.toMillis(20);
        UsagePace.Assessment baseline = UsagePace.assess(current, observed, observed,
                UsagePace.BALANCED);
        UsagePace.Assessment refined = UsagePace.assess(current, history, observed, observed,
                UsagePace.BALANCED);
        check(refined.available, "history-refined pace is available");
        check(refined.estimatedExhaustionAtMillis != baseline.estimatedExhaustionAtMillis,
                "history changes the estimate after a meaningful trend");

        long nextReset = reset + TimeUnit.HOURS.toMillis(5);
        history = history.append(new UsageWindow(1, TimeUnit.HOURS.toSeconds(5), 0L,
                nextReset / 1000L), reset + TimeUnit.MINUTES.toMillis(2));
        check(history.completedWindowCount() == 1, "reset boundary creates a historical window");
        check(history.currentWindowSamples().size() == 1, "latest reset window is isolated");
        check(history.recentWindows(5).size() == 2,
                "chart receives current and completed historical windows");

        UsageHistory restored = UsageHistory.fromJson(history.toJson(), UsageHistory.FIVE_HOUR);
        check(restored.samples.size() == history.samples.size(), "usage history JSON round trip");
        check(UsageHistory.WEEKLY.equals(
                        UsageHistory.empty(UsageHistory.WEEKLY).kind),
                "weekly history kind is preserved");
        check(UsageHistory.MONTHLY.equals(
                        UsageHistory.empty(UsageHistory.MONTHLY).kind),
                "monthly history kind is preserved");
        UsageHistory monthly = UsageHistory.empty(UsageHistory.MONTHLY).append(
                new UsageWindow(12, TimeUnit.DAYS.toSeconds(30), 0L,
                        (start + TimeUnit.DAYS.toMillis(30)) / 1000L), start);
        check(monthly.samples.size() == 1
                        && UsageHistory.MONTHLY.equals(
                        UsageHistory.fromJson(monthly.toJson(), UsageHistory.MONTHLY).kind),
                "monthly history samples round-trip with their kind");
        System.out.println("Usage-history demo: local samples produce reset-aware burn trends.");
    }

    private static void testAdaptiveRefreshPolicy() {
        long now = 2_000_000_000_000L;
        UsageWindow healthy = new UsageWindow(5, TimeUnit.HOURS.toSeconds(5), 0L,
                (now + TimeUnit.HOURS.toMillis(4)) / 1000L);
        UsageWindow mid = new UsageWindow(60, TimeUnit.HOURS.toSeconds(5), 0L,
                (now + TimeUnit.HOURS.toMillis(2)) / 1000L);
        UsageWindow low = new UsageWindow(92, TimeUnit.HOURS.toSeconds(5), 0L,
                (now + TimeUnit.HOURS.toMillis(2)) / 1000L);
        UsageWindow nearReset = new UsageWindow(20, TimeUnit.HOURS.toSeconds(5), 0L,
                (now + TimeUnit.MINUTES.toMillis(10)) / 1000L);

        check(AdaptiveRefreshPolicy.chooseMinutes(null, 0.0d, 12, 0, now) == 30,
                "automatic refresh uses a balanced interval before data exists");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, healthy, null, now),
                        0.0d, 12, 0, now) == 60,
                "healthy quota refreshes less often");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, mid, null, now),
                        0.0d, 12, 0, now) == 15,
                "lower quota increases refresh frequency");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, low, null, now),
                        0.0d, 12, 0, now) == 5,
                "critical quota uses the fastest interval");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, healthy, null, now),
                        3.0d, 12, 0, now) == 10,
                "frequent attention increases refresh frequency");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, healthy, null, now),
                        0.0d, 3, 0, now) == 120,
                "quiet hours reduce healthy unattended polling");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, nearReset, null, now),
                        0.0d, 12, 0, now) == 5,
                "an approaching used-window reset refreshes precisely");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", true, false, low, null, now),
                        0.0d, 12, 2, now) == 15,
                "consecutive failures back off even when quota is low");
        check(AdaptiveRefreshPolicy.chooseMinutes(
                        new UsageSnapshot("plus", false, true, low, null, now),
                        0.0d, 12, 3, now) == 30,
                "failure backoff remains bounded for a limited account");
        System.out.println("Automatic refresh demo: quota, attention, resets, quiet hours, and failures adapt the cadence.");
    }

    private static void testNowBarAutoStart() {
        UsageWindow high = new UsageWindow(10, 18000L, 600L, 2000000000L); // 90% remaining
        UsageWindow mid = new UsageWindow(80, 18000L, 600L, 2000000000L); // 20% remaining
        UsageWindow low = new UsageWindow(95, 604800L, 600L, 2000000000L); // 5% remaining

        check(!NowBarAutoStart.shouldStart(false, "both", 25, mid, null),
                "disabled auto-start never fires");
        check(!NowBarAutoStart.shouldStart(true, "both", 25, high, high),
                "above threshold does not start");
        check(NowBarAutoStart.shouldStart(true, "both", 25, mid, high),
                "five-hour at-or-below threshold starts for both");
        check(NowBarAutoStart.shouldStart(true, "both", 25, high, low),
                "weekly at-or-below threshold starts for both");
        check(NowBarAutoStart.shouldStart(true, "five_hour", 25, mid, high),
                "five-hour metric watches five-hour only");
        check(!NowBarAutoStart.shouldStart(true, "five_hour", 25, high, low),
                "five-hour metric ignores weekly");
        check(NowBarAutoStart.shouldStart(true, "weekly", 10, high, low),
                "weekly metric watches weekly only");
        check(!NowBarAutoStart.shouldStart(true, "weekly", 10, mid, high),
                "weekly metric ignores five-hour");
        check(NowBarAutoStart.shouldStart(true, "both", 100, high, high),
                "Always threshold starts whenever a window exists");
        check(!NowBarAutoStart.shouldStart(true, "both", 25, null, null),
                "missing windows do not start");
        check("both".equals(NowBarAutoStart.normalizeMetric("nope")), "invalid metric falls back");
        check(NowBarAutoStart.normalizeThreshold(3) == 25, "invalid threshold falls back");
        check(NowBarAutoStart.isValidThreshold(50), "50 is a valid threshold");
        System.out.println("Now Bar auto-start threshold rules match low-usage alert semantics.");
    }

    private static void testNowBarDisplayModes() {
        check(NowBarDisplayMode.AUTO.equals(NowBarDisplayMode.normalize(null)),
                "missing Now Bar mode defaults to automatic");
        check(NowBarDisplayMode.AUTO.equals(NowBarDisplayMode.normalize("invalid")),
                "invalid Now Bar mode defaults to automatic");
        check(NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(NowBarDisplayMode.resolve(
                        NowBarDisplayMode.AUTO, true, 36, false)),
                "automatic mode falls back when Samsung blocks Android promotion");
        check(NowBarDisplayMode.ANDROID_LIVE_UPDATE.equals(NowBarDisplayMode.resolve(
                        NowBarDisplayMode.AUTO, true, 36, true)),
                "automatic mode uses Android Live Update when Samsung allows promotion");
        check(NowBarDisplayMode.ANDROID_LIVE_UPDATE.equals(NowBarDisplayMode.resolve(
                        NowBarDisplayMode.AUTO, false, 36, false)),
                "automatic mode does not send private Samsung extras to other devices");
        check(NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(NowBarDisplayMode.resolve(
                        NowBarDisplayMode.SAMSUNG_COMPATIBILITY, false, 36, true)),
                "explicit Samsung compatibility override is preserved");
        check(NowBarDisplayMode.ANDROID_LIVE_UPDATE.equals(NowBarDisplayMode.resolve(
                        NowBarDisplayMode.ANDROID_LIVE_UPDATE, true, 35, false)),
                "explicit Android Live Update override is preserved");
        check(!NowBarDisplayMode.notificationContractChanged(
                        NowBarDisplayMode.SAMSUNG_COMPATIBILITY, false,
                        NowBarDisplayMode.SAMSUNG_COMPATIBILITY, false),
                "unchanged Samsung notification contract does not need a repost");
        check(NowBarDisplayMode.notificationContractChanged(
                        NowBarDisplayMode.SAMSUNG_COMPATIBILITY, false,
                        NowBarDisplayMode.ANDROID_LIVE_UPDATE, true),
                "granting promotion access rebuilds automatic Samsung fallback");
        check(NowBarDisplayMode.notificationContractChanged(
                        NowBarDisplayMode.ANDROID_LIVE_UPDATE, false,
                        NowBarDisplayMode.ANDROID_LIVE_UPDATE, true),
                "granting promotion access rebuilds an explicit Android Live Update");
        check(NowBarDisplayMode.notificationContractChanged(
                        null, true, NowBarDisplayMode.ANDROID_LIVE_UPDATE, true),
                "missing posted contract is refreshed");
        System.out.println("Now Bar display mode isolates Android and Samsung notification paths.");
    }

    private static void testWearSurfaceModes() {
        check(WearSurfaceMode.ONGOING_ACTIVITY == WearSurfaceMode.resolve(
                        NowBarDisplayMode.SAMSUNG_COMPATIBILITY, 36, true),
                "Samsung compatibility maps to Wear Ongoing Activity");
        check(WearSurfaceMode.LIVE_UPDATE == WearSurfaceMode.resolve(
                        NowBarDisplayMode.ANDROID_LIVE_UPDATE, 36, true),
                "Wear OS 7 local Live Updates can be used when available");
        check(WearSurfaceMode.ONGOING_ACTIVITY == WearSurfaceMode.resolve(
                        NowBarDisplayMode.ANDROID_LIVE_UPDATE, 35, true),
                "pre-36 Wear falls back to Ongoing Activity");
        check(WearSurfaceMode.ONGOING_ACTIVITY == WearSurfaceMode.resolve(
                        NowBarDisplayMode.AUTO, 36, false),
                "automatic Wear mode falls back when Live Updates are unavailable");
        check(WearSurfaceMode.LIVE_UPDATE == WearSurfaceMode.resolve(
                        NowBarDisplayMode.AUTO, 36, true),
                "automatic Wear mode uses local Live Updates on API 36+");
        System.out.println("Wear surface mode maps phone Now Bar choices to Wear-native surfaces.");
    }

    private static void testWearSettingsState() throws Exception {
        WearSettingsState phone = new WearSettingsState(
                NowBarDisplayMode.SAMSUNG_COMPATIBILITY,
                NowBarPercentMode.WEEKLY,
                true,
                NowBarAutoStart.METRIC_WEEKLY,
                50,
                true,
                15,
                1000L,
                WearSettingsState.SOURCE_PHONE);
        WearSettingsState roundTrip = WearSettingsState.fromJson(phone.toJson());
        check(phone.equals(roundTrip), "Wear settings round trip preserves content");
        WearSettingsState newerSameContent = new WearSettingsState(
                NowBarDisplayMode.SAMSUNG_COMPATIBILITY,
                NowBarPercentMode.WEEKLY,
                true,
                NowBarAutoStart.METRIC_WEEKLY,
                50,
                true,
                15,
                2000L,
                WearSettingsState.SOURCE_PHONE);
        check(phone.equals(newerSameContent), "Wear settings equality ignores update time");
        WearSettingsState normalized = WearSettingsState.fromJson(new org.json.JSONObject()
                .put("display_mode", "bad")
                .put("percent_mode", "bad")
                .put("metric", "bad")
                .put("threshold", 3)
                .put("refresh_minutes", 7)
                .put("source_node", "wear"));
        check(NowBarDisplayMode.AUTO.equals(normalized.displayMode), "Wear settings normalize display mode");
        check(NowBarPercentMode.AUTO.equals(normalized.percentMode), "Wear settings normalize percent mode");
        check(NowBarAutoStart.METRIC_BOTH.equals(normalized.metric), "Wear settings normalize metric");
        check(normalized.threshold == 25, "Wear settings normalize threshold");
        check(normalized.refreshMinutes == 30, "Wear settings normalize refresh interval");
        check(WearSettingsState.SOURCE_WEAR.equals(normalized.sourceNode), "Wear settings preserve Wear source");
        WearSettingsState pace = new WearSettingsState(
                NowBarDisplayMode.AUTO, NowBarPercentMode.AUTO, true,
                NowBarAutoStart.METRIC_BOTH, 25, false, 30, 3000L,
                WearSettingsState.SOURCE_PHONE, "dev.bennett.codexmeter",
                true, UsagePace.SENSITIVE, true);
        WearSettingsState paceRoundTrip = WearSettingsState.fromJson(pace.toJson());
        check(pace.equals(paceRoundTrip), "Wear settings preserve pace and accelerated start");
        check(paceRoundTrip.acceleratedStartEnabled,
                "Wear accelerated monitor preference survives sync");
        check(UsagePace.SENSITIVE.equals(paceRoundTrip.usagePaceSensitivity),
                "Wear pace sensitivity survives sync");
        System.out.println("Wear settings JSON preserves normalized sync preferences.");
    }

    private static void testWearSyncState() throws Exception {
        WearUsageState clear = new WearUsageState(null, 4000L,
                WearSettingsState.SOURCE_PHONE, false);
        WearUsageState clearRoundTrip = WearUsageState.fromJson(clear.toJson());
        check(clearRoundTrip != null && clearRoundTrip.snapshot == null,
                "Wear usage clear payload preserves an empty snapshot");
        check(!clearRoundTrip.signedIn,
                "Wear usage clear payload preserves signed-out state");
        WearSyncStatus status = new WearSyncStatus(true, true, 3000L,
                "Network unavailable", "2.6.10", 4000L);
        WearSyncStatus statusRoundTrip = WearSyncStatus.fromJson(status.toJson());
        check(statusRoundTrip != null && statusRoundTrip.signedIn,
                "Wear status preserves phone sign-in state");
        check(statusRoundTrip.refreshInProgress,
                "Wear status preserves refresh progress");
        check("Network unavailable".equals(statusRoundTrip.lastError),
                "Wear status preserves safe refresh errors");
        System.out.println("Wear sync status covers clear, sign-in, refresh, and error states.");
    }

    private static void testWearGlanceFormat() {
        UsageWindow five = new UsageWindow(62, TimeUnit.HOURS.toSeconds(5),
                TimeUnit.MINUTES.toSeconds(84), 2_000_000_000L);
        UsageWindow weekly = new UsageWindow(41, TimeUnit.DAYS.toSeconds(7),
                TimeUnit.DAYS.toSeconds(3), 2_100_000_000L);
        UsageSnapshot snapshot = new UsageSnapshot("demo", true, false, five, weekly,
                System.currentTimeMillis());
        check("38%".equals(WearGlanceFormat.remainingPercentText(five)),
                "five-hour remaining percent text");
        check("59%".equals(WearGlanceFormat.remainingPercentText(weekly)),
                "weekly remaining percent text");
        check("--".equals(WearGlanceFormat.remainingPercentText(null)),
                "missing window shows placeholder");
        check(Math.abs(WearGlanceFormat.remainingProgress(five) - 0.38f) < 0.001f,
                "remaining progress fraction matches percent");
        check("38·59".equals(WearGlanceFormat.dualShortText(snapshot)),
                "dual short complication text");
        check(WearGlanceFormat.dualLongText(snapshot).contains("5h 38%"),
                "dual long text includes five-hour");
        check(WearGlanceFormat.dualLongText(snapshot).contains("Week 59%"),
                "dual long text includes weekly");
        long now = System.currentTimeMillis();
        UsageSnapshot timed = new UsageSnapshot("demo", true, false,
                new UsageWindow(10, 18000L, 600L, (now + TimeUnit.HOURS.toMillis(2)) / 1000L),
                new UsageWindow(20, 604800L, 600L, (now + TimeUnit.DAYS.toMillis(2)) / 1000L),
                now);
        check("5h reset".equals(WearGlanceFormat.nextResetWindowLabel(timed, now)),
                "next reset prefers the sooner five-hour window");
        check(WearGlanceFormat.nextResetRelativeText(timed, now).contains("h"),
                "next reset relative text includes hours");
        check(WearGlanceFormat.nextResetLongText(timed, now).startsWith("Resets in "),
                "next reset long text is prefixed");
        UsageSnapshot fallbackTimed = new UsageSnapshot("demo", true, false,
                new UsageWindow(10, 18000L, TimeUnit.HOURS.toSeconds(2), 0L),
                null, now);
        check("5h reset".equals(WearGlanceFormat.nextResetWindowLabel(fallbackTimed, now)),
                "Wear reset label uses observation-based reset-after fallback");
        check(WearGlanceFormat.nextResetRelativeText(fallbackTimed, now).contains("h"),
                "Wear fallback reset countdown remains finite");
        UsageSnapshot unused = new UsageSnapshot("demo", true, false,
                new UsageWindow(0, 18000L, 0L, 0L),
                new UsageWindow(0, 604800L, 0L, 0L), now);
        check("--".equals(WearGlanceFormat.nextResetWindowLabel(unused, now)),
                "unused windows without API reset have no next-reset label");
        check("No reset yet".equals(WearGlanceFormat.nextResetLongText(unused, now)),
                "unused windows without API reset show no reset timeframe");
        UsageSnapshot unusedWithReset = new UsageSnapshot("demo", true, false,
                new UsageWindow(0, 18000L, 0L,
                        (now + TimeUnit.HOURS.toMillis(3)) / 1000L),
                null, now);
        check("5h reset".equals(WearGlanceFormat.nextResetWindowLabel(unusedWithReset, now)),
                "100% remaining still surfaces an API reset timeline");
        UsageSnapshot account = new UsageSnapshot("plus", true, true, five, weekly, 2, now);
        check("Limit reached".equals(WearGlanceFormat.accountStatus(account)),
                "Wear account status surfaces a reached limit");
        check("2 reset credits".equals(WearGlanceFormat.resetCreditsText(account)),
                "Wear displays reset-credit count");
        check(WearGlanceFormat.isStale(now - TimeUnit.HOURS.toMillis(2), 30, now),
                "Wear marks old phone data stale");
        check(!WearGlanceFormat.isStale(now - TimeUnit.MINUTES.toMillis(10), 30, now),
                "Wear keeps recent phone data fresh");
        System.out.println("Wear glance formatting covers tiles and complication text.");
    }

    private static void testNowBarPercentModes() {
        UsageWindow high = new UsageWindow(10, 18000L, 600L, 2000000000L); // 90% remaining
        UsageWindow mid = new UsageWindow(80, 18000L, 600L, 2000000000L); // 20% remaining
        UsageWindow low = new UsageWindow(95, 604800L, 600L, 2000000000L); // 5% remaining

        check(NowBarPercentMode.AUTO.equals(NowBarPercentMode.normalize(null)),
                "missing percent mode defaults to auto");
        check(NowBarPercentMode.AUTO.equals(NowBarPercentMode.normalize("nope")),
                "invalid percent mode defaults to auto");
        check(NowBarPercentMode.FIVE_HOUR.equals(NowBarPercentMode.normalize("five_hour")),
                "five-hour percent mode preserved");
        check(NowBarPercentMode.WEEKLY.equals(NowBarPercentMode.normalize("weekly")),
                "weekly percent mode preserved");

        check(NowBarPercentMode.FIVE_HOUR.equals(
                        NowBarPercentMode.resolveFocus("five_hour", mid, low, null)),
                "explicit five-hour mode uses five-hour");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.resolveFocus("weekly", mid, low, null)),
                "explicit weekly mode uses weekly");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.resolveFocus("five_hour", null, low, null)),
                "explicit five-hour falls back to weekly when missing");
        check(NowBarPercentMode.FIVE_HOUR.equals(
                        NowBarPercentMode.resolveFocus("weekly", mid, null, null)),
                "explicit weekly falls back to five-hour when missing");

        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.triggeredFocus("both", 25, high, low)),
                "auto trigger picks weekly when only weekly crossed threshold");
        check(NowBarPercentMode.FIVE_HOUR.equals(
                        NowBarPercentMode.triggeredFocus("both", 25, mid, high)),
                "auto trigger picks five-hour when only five-hour crossed threshold");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.triggeredFocus("both", 25, mid, low)),
                "auto trigger picks lower remaining when both crossed threshold");
        check(NowBarPercentMode.FIVE_HOUR.equals(
                        NowBarPercentMode.triggeredFocus("five_hour", 25, mid, low)),
                "auto trigger respects five-hour-only watch metric");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.triggeredFocus("weekly", 25, mid, low)),
                "auto trigger respects weekly-only watch metric");

        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.resolveFocus("auto", mid, low, "weekly")),
                "auto mode keeps locked weekly focus from trigger");
        check(NowBarPercentMode.FIVE_HOUR.equals(
                        NowBarPercentMode.resolveFocus("auto", mid, low, "five_hour")),
                "auto mode keeps locked five-hour focus from trigger");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.resolveFocus("auto", mid, low, null)),
                "auto mode without lock picks lower remaining");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.lowerRemainingFocus(high, low)),
                "lower remaining prefers weekly when it is lower");
        check(NowBarPercentMode.selectWindow("weekly", mid, low) == low,
                "selectWindow returns the focused window");
        check(NowBarPercentMode.selectWindow("weekly", mid, low).remainingPercent() == 5,
                "focused weekly remaining is used for the pill");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.focusForSettingsChange("auto", mid, low, "weekly")),
                "settings change back to AUTO restores session auto-start trigger");
        check(NowBarPercentMode.FIVE_HOUR.equals(
                        NowBarPercentMode.focusForSettingsChange("five_hour", mid, low, "weekly")),
                "settings change to five-hour ignores session auto-start trigger");
        check(NowBarPercentMode.WEEKLY.equals(
                        NowBarPercentMode.focusForSettingsChange("auto", mid, low, null)),
                "settings change to AUTO without trigger picks lower remaining");
        System.out.println("Now Bar percent mode selects auto-trigger, weekly, or five-hour focus.");
    }

    private static void testNowBarCopy() {
        long now = 1_700_000_000_000L;
        long observed = now - TimeUnit.MINUTES.toMillis(5);
        UsageWindow remaining = new UsageWindow(40, 18_000L, 3_600L,
                (now + TimeUnit.HOURS.toMillis(1)) / 1000L);
        UsageWindow exhaustedHours = new UsageWindow(100, 18_000L, 0L,
                (now + TimeUnit.HOURS.toMillis(3) + TimeUnit.MINUTES.toMillis(20)) / 1000L);
        UsageWindow exhaustedDays = new UsageWindow(100, 604_800L, 0L,
                (now + TimeUnit.DAYS.toMillis(2) + TimeUnit.HOURS.toMillis(4)) / 1000L);
        UsageWindow exhaustedNoReset = new UsageWindow(100, 18_000L, 0L, 0L);

        check("60%".equals(NowBarCopy.focusCriticalText("", remaining, observed, now)),
                "focus critical keeps percentage while allowance remains");
        check("W 60%".equals(NowBarCopy.focusCriticalText("W ", remaining, observed, now)),
                "weekly focus critical keeps W prefix while allowance remains");
        check("M 60%".equals(NowBarCopy.focusCriticalText("M ", remaining, observed, now)),
                "monthly focus critical keeps M prefix while allowance remains");
        check("3h 20m".equals(NowBarCopy.focusCriticalText("", exhaustedHours, observed, now)),
                "exhausted five-hour focus shows hours until natural reset");
        check("W 2d 4h".equals(NowBarCopy.focusCriticalText("W ", exhaustedDays, observed, now)),
                "exhausted weekly focus shows days and hours until natural reset");
        check("0%".equals(NowBarCopy.focusCriticalText("", exhaustedNoReset, observed, now)),
                "exhausted window without reset time falls back to 0%");

        check("Codex · 5-hour 60%".equals(
                        NowBarCopy.chipExpandedText("5-hour", remaining, observed, now)),
                "chip keeps percentage while allowance remains");
        check("Codex · Weekly 2d 4h".equals(
                        NowBarCopy.chipExpandedText("Weekly", exhaustedDays, observed, now)),
                "exhausted chip shows that window's reset duration");
        check("Codex · Monthly 60%".equals(
                        NowBarCopy.chipExpandedText("Monthly", remaining, observed, now)),
                "monthly chip names the free-tier window");

        check("5-hour: 60% left".equals(
                        NowBarCopy.limitText("5-hour", remaining, observed, now)),
                "limit text keeps remaining percentage");
        check("Weekly: resets in 2d 4h".equals(
                        NowBarCopy.limitText("Weekly", exhaustedDays, observed, now)),
                "exhausted weekly limit text announces resets in days/hours");
        check("Monthly: 60% left".equals(
                        NowBarCopy.limitText("Monthly", remaining, observed, now)),
                "monthly limit text keeps remaining percentage");
        check("5-hour: resets in 3h 20m".equals(
                        NowBarCopy.limitText("5-hour", exhaustedHours, observed, now)),
                "exhausted five-hour limit text announces resets in hours/minutes");
        check("5-hour: unavailable".equals(
                        NowBarCopy.limitText("5-hour", null, observed, now)),
                "missing window stays unavailable");

        check("5h 60%".equals(NowBarCopy.wearLimitText("5h", remaining, observed, now)),
                "Wear limit text keeps remaining percentage");
        check("Week resets 2d 4h".equals(
                        NowBarCopy.wearLimitText("Week", exhaustedDays, observed, now)),
                "Wear exhausted weekly text uses compact reset duration");
        check("2d 4h".equals(NowBarCopy.compactDuration(
                        TimeUnit.DAYS.toMillis(2) + TimeUnit.HOURS.toMillis(4))),
                "compact duration prefers days and hours");
        check("2d".equals(NowBarCopy.compactDuration(TimeUnit.DAYS.toMillis(2))),
                "compact duration omits zero hours for whole days");
        check("4h".equals(NowBarCopy.compactDuration(TimeUnit.HOURS.toMillis(4))),
                "compact duration omits zero minutes for whole hours");
        check("3h 20m".equals(NowBarCopy.compactDuration(
                        TimeUnit.HOURS.toMillis(3) + TimeUnit.MINUTES.toMillis(20))),
                "compact duration keeps leftover minutes under a day");
        check("12m".equals(NowBarCopy.compactDuration(TimeUnit.MINUTES.toMillis(12))),
                "compact duration uses minutes under one hour");
        System.out.println("Now Bar exhausted windows swap percentage copy for reset duration.");
    }

    private static void testStandardUsage() throws Exception {
        String json = "{\"plan_type\":\"plus\",\"rate_limit\":{" +
                "\"allowed\":true,\"limit_reached\":false," +
                "\"primary_window\":{\"used_percent\":37,\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,\"reset_at\":2000000000}," +
                "\"secondary_window\":{\"used_percent\":61,\"limit_window_seconds\":604800,\"reset_after_seconds\":200000,\"reset_at\":2000200000}}}";
        UsageSnapshot snapshot = UsageParser.parse(json, 1234L);
        check("plus".equals(snapshot.planType), "plan");
        check(snapshot.fiveHour != null && snapshot.fiveHour.remainingPercent() == 63, "five-hour remaining");
        check(snapshot.weekly != null && snapshot.weekly.remainingPercent() == 39, "weekly remaining");
        check(snapshot.fetchedAtMillis == 1234L, "fetch timestamp");
    }

    /**
     * Free-tier accounts report a single ~30-day Codex window. It must parse into the monthly
     * slot, stay displayable, and adapt every long-window surface (widgets, Wear, Now Bar).
     */
    private static void testMonthlyWindow() throws Exception {
        long now = 2_000_000_000_000L;
        String freeTier = "{\"plan_type\":\"free\",\"rate_limit\":{"
                + "\"allowed\":true,\"limit_reached\":false,"
                + "\"primary_window\":{\"used_percent\":28,\"limit_window_seconds\":2592000,"
                + "\"reset_after_seconds\":1209600,\"reset_at\":"
                + ((now + TimeUnit.DAYS.toMillis(14)) / 1000L) + "}}}";
        UsageSnapshot snapshot = UsageParser.parse(freeTier, now);
        check("free".equals(snapshot.planType), "free plan type preserved");
        check(snapshot.fiveHour == null && snapshot.weekly == null,
                "monthly-only response leaves 5-hour and weekly absent");
        check(snapshot.monthly != null && snapshot.monthly.usedPercent == 28,
                "monthly window classified by its ~30-day duration");
        check(snapshot.hasDisplayableData(),
                "monthly-only response is displayable instead of erroring");
        check(snapshot.longWindow() == snapshot.monthly && snapshot.longWindowIsMonthly(),
                "monthly window fills the long-window slot");
        check(snapshot.nextResetMillis(now) == now + TimeUnit.DAYS.toMillis(14),
                "monthly reset drives the next-reset selection");

        UsageSnapshot restored = UsageSnapshot.fromJson(snapshot.toJson());
        check(restored != null && restored.monthly != null
                        && restored.monthly.usedPercent == 28
                        && restored.monthly.windowSeconds == 2_592_000L,
                "monthly window survives the cache round trip");

        // Calendar months drift between 28 and 31 days; both bounds classify.
        UsageSnapshot shortMonth = UsageParser.parse(
                "{\"plan_type\":\"free\",\"rate_limit\":{\"primary_window\":"
                        + "{\"used_percent\":5,\"limit_window_seconds\":2419200}}}", now);
        check(shortMonth.monthly != null, "28-day window classifies as monthly");
        UsageSnapshot longMonth = UsageParser.parse(
                "{\"plan_type\":\"free\",\"rate_limit\":{\"primary_window\":"
                        + "{\"used_percent\":5,\"limit_window_seconds\":2678400}}}", now);
        check(longMonth.monthly != null, "31-day window classifies as monthly");

        // A Pro-style response after resubscribing keeps its slots; monthly stays empty.
        String proTier = "{\"plan_type\":\"pro\",\"rate_limit\":{"
                + "\"primary_window\":{\"used_percent\":10,\"limit_window_seconds\":18000},"
                + "\"secondary_window\":{\"used_percent\":72,\"limit_window_seconds\":604800}}}";
        UsageSnapshot pro = UsageParser.parse(proTier, now);
        check(pro.fiveHour != null && pro.weekly != null && pro.monthly == null,
                "paid-tier responses keep the 5-hour and weekly slots without a monthly one");
        check(pro.longWindow() == pro.weekly && !pro.longWindowIsMonthly(),
                "weekly stays the long window whenever it is reported");

        // Long-window consumers adapt: widgets and Wear surfaces label the monthly window.
        check(WidgetMeters.meterWindow(WidgetMeters.WEEKLY, snapshot) == snapshot.monthly,
                "weekly widget meter falls back to the monthly window");
        check("Mo".equals(WidgetMeters.shortLabel(WidgetMeters.WEEKLY, snapshot)),
                "weekly widget meter relabels to Mo on the free tier");
        check("Monthly".equals(
                        WidgetMeters.configLabel(WidgetMeters.WEEKLY, snapshot)),
                "widget config row names the monthly window");
        check("Wk".equals(WidgetMeters.shortLabel(WidgetMeters.WEEKLY, pro)),
                "weekly widget meter keeps its label on paid tiers");
        check("Monthly".equals(WearGlanceFormat.longWindowLabel(snapshot))
                        && "Month".equals(WearGlanceFormat.longWindowShortLabel(snapshot)),
                "Wear surfaces label the monthly long window");
        check("Weekly".equals(WearGlanceFormat.longWindowLabel(pro)),
                "Wear surfaces keep the weekly label on paid tiers");
        check(WearGlanceFormat.dualLongText(snapshot).contains("Month 72%"),
                "Wear dual text reports monthly remaining");
        check(WearGlanceFormat.focusSummary(snapshot).contains("Month"),
                "Wear focus summary includes the monthly window");
        check("Month".equals(WearGlanceFormat.compactWindowLabel(snapshot.monthly, "5h")),
                "compact window label recognizes month-length windows");
        check("Month reset".equals(WearGlanceFormat.nextResetWindowLabel(snapshot, now)),
                "next-reset label names the monthly window");

        // Refresh cadence and low-usage automation follow the monthly window too.
        check(AdaptiveRefreshPolicy.chooseMinutes(snapshot, 0.0d, 12, 0, now) == 30,
                "72% remaining monthly quota refreshes at a balanced cadence");
        UsageSnapshot lowMonthly = UsageParser.parse(
                "{\"plan_type\":\"free\",\"rate_limit\":{\"primary_window\":"
                        + "{\"used_percent\":92,\"limit_window_seconds\":2592000,"
                        + "\"reset_at\":" + ((now + TimeUnit.DAYS.toMillis(3)) / 1000L)
                        + "}}}", now);
        check(AdaptiveRefreshPolicy.chooseMinutes(lowMonthly, 0.0d, 12, 0, now) == 5,
                "critical monthly quota uses the fastest interval");
        check(NowBarAutoStart.shouldStart(true, "both", 25, null, lowMonthly.longWindow()),
                "monthly window triggers low-usage auto-start through the long slot");
        System.out.println("Monthly-window demo: Pro 20x expiring to Free swaps weekly for "
                + "a monthly card, widgets/Wear relabel, and nothing errors.");
    }

    private static void testWindowIdentification() throws Exception {
        String json = "{\"plan_type\":\"pro\",\"rate_limit\":{" +
                "\"allowed\":true,\"limit_reached\":false," +
                "\"primary_window\":{\"used_percent\":12,\"limit_window_seconds\":604800,\"reset_after_seconds\":1,\"reset_at\":2}," +
                "\"secondary_window\":{\"used_percent\":88,\"limit_window_seconds\":18000,\"reset_after_seconds\":1,\"reset_at\":2}}}";
        UsageSnapshot snapshot = UsageParser.parse(json, 1L);
        check(snapshot.fiveHour.usedPercent == 88, "duration-based five-hour identification");
        check(snapshot.weekly.usedPercent == 12, "duration-based weekly identification");
    }

    private static void testAdditionalLimits() throws Exception {
        String json = "{\"plan_type\":\"team\",\"additional_rate_limits\":[{" +
                "\"rate_limit\":{\"primary_window\":{\"used_percent\":150,\"limit_window_seconds\":18000,\"reset_after_seconds\":1,\"reset_at\":2}," +
                "\"secondary_window\":{\"used_percent\":-5,\"limit_window_seconds\":604800,\"reset_after_seconds\":1,\"reset_at\":3}}}]}";
        UsageSnapshot snapshot = UsageParser.parse(json, 1L);
        check(snapshot.fiveHour == null && snapshot.weekly == null,
                "additional limits do not masquerade as standard limits");
        check(snapshot.additionalLimits.size() == 1, "additional limit preserved");
        check(snapshot.additionalLimits.get(0).primary.remainingPercent() == 0, "upper clamp");
        check(snapshot.additionalLimits.get(0).secondary.remainingPercent() == 100, "lower clamp");
    }



    private static void testPrimaryLimitWinsOverAdditional() throws Exception {
        String json = "{\"plan_type\":\"pro\",\"rate_limit\":{" +
                "\"primary_window\":{\"used_percent\":10,\"limit_window_seconds\":21600,\"reset_after_seconds\":1,\"reset_at\":2}," +
                "\"secondary_window\":{\"used_percent\":20,\"limit_window_seconds\":604800,\"reset_after_seconds\":1,\"reset_at\":3}}," +
                "\"additional_rate_limits\":[{\"rate_limit\":{" +
                "\"primary_window\":{\"used_percent\":90,\"limit_window_seconds\":18000,\"reset_after_seconds\":1,\"reset_at\":4}}}]}";
        UsageSnapshot snapshot = UsageParser.parse(json, 1L);
        check(snapshot.fiveHour != null && snapshot.fiveHour.usedPercent == 10,
                "main Codex limit takes precedence over additional feature limits");
        check(snapshot.weekly != null && snapshot.weekly.usedPercent == 20,
                "main weekly limit takes precedence");
        check(snapshot.additionalLimits.size() == 1
                        && snapshot.additionalLimits.get(0).primary.usedPercent == 90,
                "additional feature limit remains independently available");
    }

    private static void testOptionalUsageSections() throws Exception {
        String weeklyOnly = "{\"plan_type\":\"pro\",\"rate_limit\":{"
                + "\"secondary_window\":{\"used_percent\":25,"
                + "\"limit_window_seconds\":604800,\"reset_at\":2000}}}";
        UsageSnapshot snapshot = UsageParser.parse(weeklyOnly, 1L);
        check(snapshot.fiveHour == null, "missing five-hour limit stays absent");
        check(snapshot.weekly != null && snapshot.weekly.usedPercent == 25,
                "weekly limit remains available without five-hour data");

        String namedAdditional = "{\"additional_rate_limits\":[{"
                + "\"limit_name\":\"GPT-5.3-Codex-Spark\","
                + "\"metered_feature\":\"codex_bengalfox\","
                + "\"rate_limit\":{\"allowed\":false,\"limit_reached\":true,"
                + "\"primary_window\":{\"used_percent\":40,\"limit_window_seconds\":18000}}}]}";
        UsageSnapshot additional = UsageParser.parse(namedAdditional, 2L);
        UsageLimit limit = additional.additionalLimits.get(0);
        check("GPT-5.3-Codex-Spark".equals(limit.displayName()),
                "API-provided additional limit label preserved");
        check(!limit.allowed && limit.limitReached, "additional limit status preserved");
        check(additional.hasDisplayableData(), "additional-only response is displayable");
    }

    private static void testUsageCredits() throws Exception {
        UsageSnapshot snapshot = UsageParser.parse(
                "{\"credits\":{\"has_credits\":true,\"unlimited\":false,"
                        + "\"balance\":\"2500.5\"}}",
                3L);
        check(snapshot.usageCredits != null && snapshot.usageCredits.hasCredits,
                "purchased usage credits parsed");
        check("2500.5".equals(snapshot.usageCredits.balance), "usage-credit balance preserved");
        check(snapshot.hasDisplayableData(), "credit-only response is displayable");
        UsageSnapshot restored = UsageSnapshot.fromJson(snapshot.toJson());
        check(restored != null && restored.usageCredits != null
                        && "2500.5".equals(restored.usageCredits.balance),
                "usage credits survive cache round trip");
        UsageSnapshot empty = UsageParser.parse("{\"credits\":{}}", 4L);
        check(empty.usageCredits == null && !empty.hasDisplayableData(),
                "empty credits object does not become dashboard data");
        UsageSnapshot none = UsageParser.parse(
                "{\"credits\":{\"has_credits\":false,\"balance\":\"0\"}}", 5L);
        check(none.usageCredits != null && none.usageCredits.balance.isEmpty()
                        && !none.hasDisplayableData(),
                "zero balance for an account without purchased credits is not standalone data");
    }

    /**
     * Workspace spend controls (#98): {@code spend_control.individual_limit} parses into its own
     * slot beside the windows and credits, survives the cache and Wear round trips, accepts
     * string-or-number field types, and stays null for accounts without one so their dashboards
     * do not change.
     */
    /**
     * The sanitized live payload from #98: amounts as strings with a long fractional {@code used},
     * {@code remaining_percent} as the only percentage, an epoch-seconds {@code reset_at}, and no
     * {@code source}, {@code remaining}, {@code used_percent}, or {@code reset_after_seconds}.
     */
    private static void testSpendControlLivePayload() throws Exception {
        long resetAt = 1_790_812_801L;
        long now = resetAt * 1000L - TimeUnit.DAYS.toMillis(10);
        SpendControl control = UsageParser.parse(fixture("usage-spend-control-live.json"), now)
                .spendControl;
        check(control != null, "live spend control parses");
        check(!control.reached, "live reached flag preserved");
        check(control.source.isEmpty() && "Workspace spend limit".equals(control.sourceLabel()),
                "a missing source still reads as the workspace limit");
        check("1000".equals(control.limit) && "426.10000133514404".equals(control.used)
                        && control.remaining.isEmpty(),
                "live amount strings preserved verbatim");
        check(control.usedPercent == -1 && control.remainingPercent == 57,
                "only remaining_percent is reported");
        check(control.effectiveRemainingPercent() == 57 && control.effectiveUsedPercent() == 43,
                "the used share is the complement of remaining_percent");
        check(new BigDecimal("573.89999866485596").compareTo(control.numericRemaining()) == 0,
                "the remainder is derived from limit minus used");
        check(SpendControl.pluralQuantity(control.numericRemaining()) != 1,
                "a fractional remainder never takes the singular form");
        check(control.resetAfterSeconds == 0L && control.showsResetCountdown()
                        && control.effectiveResetAtMillis(now) == resetAt * 1000L,
                "reset_at alone drives the reset instant");
        UsageSnapshot restored = UsageSnapshot.fromJson(
                UsageParser.parse(fixture("usage-spend-control-live.json"), now).toJson());
        check(restored != null && restored.spendControl != null
                        && "426.10000133514404".equals(restored.spendControl.used)
                        && restored.spendControl.remainingPercent == 57
                        && restored.spendControl.usedPercent == -1
                        && restored.spendControl.resetAtEpochSeconds == resetAt,
                "live spend control survives the cache round trip");
    }

    private static void testSpendControl() throws Exception {
        long resetAt = 1_735_689_720L;
        long now = resetAt * 1000L - TimeUnit.HOURS.toMillis(12);
        UsageSnapshot snapshot = UsageParser.parse(fixture("usage-spend-control.json"), now);
        SpendControl control = snapshot.spendControl;
        check(control != null, "spend control parsed from the usage response");
        check(!control.reached, "reached flag preserved");
        check(SpendControl.SOURCE_WORKSPACE.equals(control.source), "source preserved");
        check("25000".equals(control.limit) && "8000".equals(control.used)
                        && "17000".equals(control.remaining),
                "amount strings preserved verbatim");
        check(control.usedPercent == 32 && control.remainingPercent == 68,
                "percentages preserved");
        check(control.effectiveRemainingPercent() == 68 && control.effectiveUsedPercent() == 32,
                "explicit percentages win over derived ones");
        check(control.resetAfterSeconds == 43_200L && control.resetAtEpochSeconds == resetAt,
                "reset timeline preserved");
        check(control.showsResetCountdown()
                        && control.effectiveResetAtMillis(now) == resetAt * 1000L,
                "explicit reset_at drives the reset instant");
        check(new BigDecimal("25000").compareTo(control.numericLimit()) == 0
                        && new BigDecimal("8000").compareTo(control.numericUsed()) == 0
                        && new BigDecimal("17000").compareTo(control.numericRemaining()) == 0,
                "amounts parse as numbers");
        check("Workspace spend limit".equals(control.sourceLabel()),
                "workspace source gets a human label");
        check(SpendControl.pluralQuantity(new BigDecimal("17000")) == 17_000
                        && SpendControl.pluralQuantity(new BigDecimal("25000")) == 25_000
                        && SpendControl.pluralQuantity(BigDecimal.ZERO) == 0,
                "integral amounts select their own plurals quantity");
        check(SpendControl.pluralQuantity(BigDecimal.ONE) == 1
                        && SpendControl.pluralQuantity(new BigDecimal("1.00")) == 1,
                "exactly one credit is singular");
        check(SpendControl.pluralQuantity(new BigDecimal("1.5")) != 1
                        && SpendControl.pluralQuantity(new BigDecimal("2500.5")) != 1
                        && SpendControl.pluralQuantity(new BigDecimal("99999999999")) != 1
                        && SpendControl.pluralQuantity(null) != 1,
                "fractional, oversized, and missing amounts never take the singular form");

        // Everything else in the response is untouched and stays a separate concept.
        check(snapshot.fiveHour != null && snapshot.fiveHour.usedPercent == 42
                        && snapshot.weekly != null && snapshot.weekly.usedPercent == 5,
                "rate-limit windows still parse beside the spend control");
        check(snapshot.usageCredits != null && "2500.5".equals(snapshot.usageCredits.balance),
                "purchased usage credits stay separate from the spend control");
        check(snapshot.resetCreditsAvailable == 3,
                "banked reset credits stay separate from the spend control");
        check(snapshot.additionalLimits.size() == 1, "additional limits still parse");
        check(snapshot.nextResetMillis(now) == 1_735_647_120_000L,
                "the earliest rate-limit reset, not the spend-control reset, drives the "
                        + "next-reset selection");
        check(snapshot.hasDisplayableData(), "response remains displayable");

        UsageSnapshot restored = UsageSnapshot.fromJson(snapshot.toJson());
        check(restored != null && restored.spendControl != null
                        && !restored.spendControl.reached
                        && "8000".equals(restored.spendControl.used)
                        && "25000".equals(restored.spendControl.limit)
                        && restored.spendControl.usedPercent == 32
                        && restored.spendControl.remainingPercent == 68
                        && restored.spendControl.resetAtEpochSeconds == resetAt
                        && restored.spendControl.resetAfterSeconds == 43_200L
                        && SpendControl.SOURCE_WORKSPACE.equals(restored.spendControl.source),
                "spend control survives the cache round trip");
        WearUsageState wear = WearUsageState.fromJson(
                new WearUsageState(snapshot, now, WearSettingsState.SOURCE_PHONE).toJson());
        check(wear != null && wear.snapshot != null && wear.snapshot.spendControl != null
                        && "8000".equals(wear.snapshot.spendControl.used)
                        && "25000".equals(wear.snapshot.spendControl.limit)
                        && wear.snapshot.spendControl.usedPercent == 32,
                "spend control rides along in the Wear Data Layer payload");

        // Absent, null, or empty spend controls leave the slot empty: no card, no change.
        String windowsOnly = "\"rate_limit\":{\"primary_window\":{\"used_percent\":10,"
                + "\"limit_window_seconds\":18000}}";
        check(UsageParser.parse("{" + windowsOnly + "}", now).spendControl == null,
                "responses without spend_control leave the slot empty");
        check(UsageParser.parse("{\"spend_control\":null," + windowsOnly + "}", now)
                        .spendControl == null,
                "null spend_control is tolerated");
        check(UsageParser.parse("{\"spend_control\":{\"reached\":true}," + windowsOnly + "}",
                        now).spendControl == null,
                "reached without an individual_limit has nothing to show");
        check(UsageParser.parse("{\"spend_control\":{\"reached\":false,"
                        + "\"individual_limit\":null}," + windowsOnly + "}", now)
                        .spendControl == null,
                "null individual_limit is tolerated");
        check(UsageParser.parse("{\"spend_control\":{\"individual_limit\":{}}," + windowsOnly
                        + "}", now).spendControl == null,
                "empty individual_limit is not displayable");
        UsageSnapshot sourceOnly = UsageParser.parse(
                "{\"spend_control\":{\"individual_limit\":{\"source\":\"x\"}}}", now);
        check(sourceOnly.spendControl == null && !sourceOnly.hasDisplayableData(),
                "individual_limit without amounts or percentages is ignored");
        UsageSnapshot spendOnly = UsageParser.parse("{\"spend_control\":{\"reached\":false,"
                + "\"individual_limit\":{\"limit\":\"100\",\"used\":\"1\"}}}", now);
        check(spendOnly.spendControl != null && spendOnly.hasDisplayableData(),
                "a spend-control-only response is still recognizable usage data");

        // Field types are not trusted: numbers where strings are documented and vice versa.
        SpendControl typed = UsageParser.parse("{\"spend_control\":{\"reached\":false,"
                + "\"individual_limit\":{\"limit\":25000,\"used\":8000.0,\"remaining\":17000,"
                + "\"used_percent\":\"32\",\"remaining_percent\":\"68%\","
                + "\"reset_after_seconds\":\"43200\",\"reset_at\":\"" + resetAt + "\"}}}", now)
                .spendControl;
        check(typed != null && "25000".equals(typed.limit) && "8000".equals(typed.used)
                        && "17000".equals(typed.remaining),
                "numeric amounts normalize to plain strings");
        check(typed.usedPercent == 32 && typed.remainingPercent == 68,
                "string percentages parse, with or without a percent sign");
        check(typed.resetAfterSeconds == 43_200L && typed.resetAtEpochSeconds == resetAt,
                "string reset fields parse");
        check(new BigDecimal("8000").compareTo(typed.numericUsed()) == 0
                        && new BigDecimal("25000").compareTo(typed.numericLimit()) == 0,
                "typed variant yields the same amounts");

        // Missing fields are derived from whatever was reported.
        SpendControl amountsOnly = SpendControl.fromJson(new JSONObject(
                "{\"individual_limit\":{\"limit\":\"25000\",\"used\":\"8000\"}}"));
        check(amountsOnly != null
                        && new BigDecimal("17000").compareTo(amountsOnly.numericRemaining()) == 0
                        && amountsOnly.effectiveRemainingPercent() == 68
                        && amountsOnly.effectiveUsedPercent() == 32,
                "remainder and percentages derive from limit and used");
        SpendControl percentOnly = SpendControl.fromJson(new JSONObject(
                "{\"individual_limit\":{\"used_percent\":32}}"));
        check(percentOnly != null && percentOnly.numericLimit() == null
                        && percentOnly.numericUsed() == null
                        && percentOnly.numericRemaining() == null
                        && percentOnly.effectiveUsedPercent() == 32
                        && percentOnly.effectiveRemainingPercent() == 68,
                "percent-only payloads keep the percentages and no amounts");
        SpendControl restoredPercentOnly = SpendControl.fromJson(percentOnly.toJson());
        check(restoredPercentOnly != null && restoredPercentOnly.limit.isEmpty()
                        && restoredPercentOnly.usedPercent == 32
                        && restoredPercentOnly.remainingPercent == -1
                        && !restoredPercentOnly.showsResetCountdown(),
                "unknown fields stay unknown through the round trip");

        // Reached, overspent, clamped, and garbage payloads.
        SpendControl reached = SpendControl.fromJson(new JSONObject("{\"reached\":true,"
                + "\"individual_limit\":{\"limit\":\"25000\",\"used\":\"25000\","
                + "\"remaining\":\"0\",\"used_percent\":100,\"remaining_percent\":0}}"));
        check(reached != null && reached.reached && reached.effectiveRemainingPercent() == 0
                        && reached.numericRemaining().signum() == 0,
                "exhausted allocation reports the reached state");
        SpendControl reachedEarly = SpendControl.fromJson(new JSONObject("{\"reached\":true,"
                + "\"individual_limit\":{\"limit\":\"25000\",\"used\":\"8000\","
                + "\"remaining\":\"17000\"}}"));
        check(reachedEarly != null && reachedEarly.reached
                        && new BigDecimal("17000").compareTo(reachedEarly.numericRemaining()) == 0,
                "workspace reached flag is preserved even when a remainder is still reported");
        SpendControl overspent = SpendControl.fromJson(new JSONObject(
                "{\"individual_limit\":{\"limit\":\"100\",\"used\":\"120\",\"remaining\":\"-20\"}}"));
        check(overspent != null && overspent.effectiveRemainingPercent() == 0
                        && overspent.numericRemaining().signum() < 0,
                "negative remainders report zero percent remaining");
        SpendControl clamped = SpendControl.fromJson(new JSONObject(
                "{\"individual_limit\":{\"used_percent\":150,\"remaining_percent\":-5}}"));
        check(clamped != null && clamped.usedPercent == 100 && clamped.remainingPercent == -1
                        && clamped.effectiveRemainingPercent() == 0,
                "out-of-range percentages clamp or become unknown");
        check(SpendControl.fromJson(new JSONObject("{\"individual_limit\":{\"limit\":\"lots\","
                        + "\"used\":\"some\",\"used_percent\":\"forty\"}}")) == null,
                "non-numeric payloads are ignored rather than rendered");
        SpendControl relative = SpendControl.fromJson(new JSONObject(
                "{\"individual_limit\":{\"limit\":\"1\",\"used\":\"0\","
                        + "\"reset_after_seconds\":3600}}"));
        check(relative != null && relative.showsResetCountdown() && relative.resetAtMillis() == 0L
                        && relative.effectiveResetAtMillis(now) == now + TimeUnit.HOURS.toMillis(1),
                "reset_after_seconds falls back to a fetch-relative reset instant");
        check(!SpendControl.fromJson(new JSONObject(
                        "{\"individual_limit\":{\"limit\":\"1\",\"used\":\"0\"}}"))
                        .showsResetCountdown(),
                "no reset fields means no countdown");
        System.out.println("Spend control: monthly credit limit parses beside the windows and "
                + "credits, round-trips, tolerates field types, and hides when absent.");
    }

    private static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                System.getProperty("codex.fixtures", "tests/fixtures")).resolve(name)),
                StandardCharsets.UTF_8);
    }

    private static void testUsageCreditsAutoHide() {
        check(new UsageCredits(true, false, "2500").shouldDisplay(),
                "positive usage-credit balance stays visible");
        check(new UsageCredits(true, false, "0.01").shouldDisplay(),
                "smallest renderable balance stays visible");
        check(new UsageCredits(false, true, "").shouldDisplay(),
                "unlimited plans always show the credits card");
        check(new UsageCredits(true, false, "").shouldDisplay(),
                "credits without a reported amount stay visible");
        check(new UsageCredits(true, false, "12k credits").shouldDisplay(),
                "unparseable non-empty balance stays visible rather than silently vanishing");
        check(!new UsageCredits(true, false, "0").shouldDisplay(),
                "zero balance is always hidden");
        check(!new UsageCredits(true, false, "0.00").shouldDisplay(),
                "fractional zero balance is always hidden");
        check(!new UsageCredits(true, false, "0.004").shouldDisplay(),
                "balance that would render as 0 is always hidden");
        check(!new UsageCredits(true, false, "-12.5").shouldDisplay(),
                "negative balance is always hidden");
        check(!new UsageCredits(false, false, "500").shouldDisplay(),
                "no purchased credits hides the card entirely");
        UsageSnapshot zeroOnly = new UsageSnapshot("plus", true, false, null, null,
                java.util.Collections.emptyList(), new UsageCredits(true, false, "0"), -1, 9L);
        check(!zeroOnly.hasDisplayableData(),
                "snapshot with only a zero balance has nothing to display");
        System.out.println("Usage-credit auto-hide: zero, near-zero, and negative balances "
                + "never render.");
    }

    private static void testResetCreditsAutoHide() {
        check(new ResetCreditsSnapshot(3, java.util.Collections.emptyList(), 1L).shouldDisplay(),
                "positive reset-credit inventory stays visible");
        check(new ResetCreditsSnapshot(1, java.util.Collections.emptyList(), 1L).shouldDisplay(),
                "single available reset stays visible");
        check(!new ResetCreditsSnapshot(0, java.util.Collections.emptyList(), 1L).shouldDisplay(),
                "zero available resets always hide the card");
        check(ResetCreditsSnapshot.shouldDisplayCount(2),
                "usage-endpoint summary count above zero stays visible");
        check(!ResetCreditsSnapshot.shouldDisplayCount(0),
                "usage-endpoint summary of zero hides the card");
        check(!ResetCreditsSnapshot.shouldDisplayCount(-1),
                "unknown reset-credit count never displays");
        UsageSnapshot zeroResetsOnly = new UsageSnapshot("plus", true, false, null, null,
                java.util.Collections.emptyList(), null, 0, 10L);
        check(!zeroResetsOnly.hasDisplayableData(),
                "snapshot with only a zero reset-credit count has nothing to display");
        UsageSnapshot positiveResetsOnly = new UsageSnapshot("plus", true, false, null, null,
                java.util.Collections.emptyList(), null, 2, 11L);
        check(positiveResetsOnly.hasDisplayableData(),
                "snapshot with available resets remains displayable");
        System.out.println("Reset-credit shouldDisplay: zero inventory is treated as empty.");
    }

    private static void testDashboardSectionOrder() {
        // Providers other than Codex list only what they report, then usage history.
        check(DashboardSections.providerSections(null, UsageProvider.CURSOR.usualDashboardSections())
                        .equals(Arrays.asList(DashboardSections.WEEKLY,
                                DashboardSections.USAGE_HISTORY)),
                "Cursor's editor lists included usage and history before any data");
        check(DashboardSections.providerSections(null,
                        UsageProvider.CLAUDE_CODE.usualDashboardSections())
                        .equals(Arrays.asList(DashboardSections.FIVE_HOUR,
                                DashboardSections.WEEKLY, DashboardSections.USAGE_HISTORY)),
                "Claude Code's editor lists 5-hour, weekly, and history before any data");
        UsageLimit spark = new UsageLimit("codex-spark", "GPT-5.3-Codex-Spark",
                "codex_bengalfox", true, false,
                new UsageWindow(24, 18000L, 0L, 2_000_000_000L), null);
        String sparkKey = DashboardSections.limitKey(spark);
        check("limit:codex-spark".equals(sparkKey), "limit key prefers the API id");
        check("limit:gpt-5.3-codex-spark".equals(DashboardSections.limitKey(new UsageLimit(
                        "", "GPT-5.3-Codex-Spark", "", true, false,
                        new UsageWindow(1, 18000L, 0L, 2_000_000_000L), null))),
                "limit key falls back to the display name");
        List<String> defaults = DashboardSections.defaultOrder(Arrays.asList(spark));
        check(defaults.equals(Arrays.asList(
                        DashboardSections.FIVE_HOUR, DashboardSections.WEEKLY,
                        DashboardSections.MONTHLY, DashboardSections.SPEND_CONTROL, sparkKey,
                        DashboardSections.USAGE_CREDITS, DashboardSections.USAGE_HISTORY,
                        DashboardSections.RESET_CREDITS)),
                "default order is 5-hour, weekly, monthly, monthly credit limit, detected "
                        + "limits, credits, history, resets");

        check(DashboardSections.resolveOrder("", defaults).equals(defaults),
                "no saved order keeps the defaults");
        List<String> saved = DashboardSections.resolveOrder(
                "usage_credits, limit:codex-spark ,five_hour,weekly", defaults);
        check(saved.equals(Arrays.asList(DashboardSections.USAGE_CREDITS, sparkKey,
                        DashboardSections.FIVE_HOUR, DashboardSections.WEEKLY,
                        DashboardSections.MONTHLY, DashboardSections.SPEND_CONTROL,
                        DashboardSections.USAGE_HISTORY, DashboardSections.RESET_CREDITS)),
                "saved order is applied with whitespace tolerated and the new monthly, spend "
                        + "control, history, and reset-credit sections slot in at their default "
                        + "positions");
        List<String> withoutSpark = DashboardSections.resolveOrder(
                "weekly,five_hour,usage_credits",
                DashboardSections.defaultOrder(Arrays.asList(spark)));
        check(withoutSpark.equals(Arrays.asList(DashboardSections.WEEKLY,
                        DashboardSections.FIVE_HOUR, DashboardSections.MONTHLY,
                        DashboardSections.SPEND_CONTROL, sparkKey,
                        DashboardSections.USAGE_CREDITS,
                        DashboardSections.USAGE_HISTORY, DashboardSections.RESET_CREDITS)),
                "newly detected Spark limit slots in before credits, not at the end");
        List<String> staleKeys = DashboardSections.resolveOrder(
                "limit:old-model,weekly,five_hour",
                Arrays.asList(DashboardSections.FIVE_HOUR, DashboardSections.WEEKLY));
        check(staleKeys.equals(Arrays.asList(DashboardSections.WEEKLY,
                        DashboardSections.FIVE_HOUR)),
                "keys for limits no longer reported are dropped");
        check(DashboardSections.serialize(saved)
                        .equals("usage_credits,limit:codex-spark,five_hour,weekly,monthly,"
                                + "spend_control,usage_history,reset_credits"),
                "order round-trips through the stored CSV form");
        check(DashboardSections.resolveOrder(null,
                        Arrays.asList(DashboardSections.FIVE_HOUR))
                        .equals(Arrays.asList(DashboardSections.FIVE_HOUR)),
                "null saved order is tolerated");

        check(!DashboardSections.isHidden("", sparkKey)
                        && !DashboardSections.isHidden(null, sparkKey),
                "no sections are hidden by default");
        String hidden = DashboardSections.setHidden("", sparkKey, true);
        check(sparkKey.equals(hidden) && DashboardSections.isHidden(hidden, sparkKey),
                "hiding a section stores its key");
        hidden = DashboardSections.setHidden(hidden, DashboardSections.USAGE_HISTORY, true);
        check(DashboardSections.isHidden(hidden, sparkKey)
                        && DashboardSections.isHidden(hidden, DashboardSections.USAGE_HISTORY),
                "multiple hidden sections coexist");
        check(DashboardSections.setHidden(hidden, sparkKey, true).equals(hidden),
                "re-hiding an already hidden section is idempotent");
        hidden = DashboardSections.setHidden(hidden, sparkKey, false);
        check(!DashboardSections.isHidden(hidden, sparkKey)
                        && DashboardSections.isHidden(hidden, DashboardSections.USAGE_HISTORY),
                "unhiding removes only the requested key");
        check("".equals(DashboardSections.setHidden(hidden,
                        DashboardSections.USAGE_HISTORY, false)),
                "unhiding the last section empties the CSV");
        System.out.println("Dashboard sections: saved order honored, auto-detected limits "
                + "keep a stable slot, and hidden-section keys round-trip.");
    }

    private static void testMalformedWindowIgnored() throws Exception {
        String json = "{\"plan_type\":\"plus\",\"rate_limit\":{" +
                "\"allowed\":true,\"limit_reached\":false," +
                "\"primary_window\":{}," +
                "\"secondary_window\":{\"used_percent\":25,\"limit_window_seconds\":604800,\"reset_after_seconds\":1,\"reset_at\":2}}}";
        UsageSnapshot snapshot = UsageParser.parse(json, 1L);
        check(snapshot.fiveHour == null, "malformed primary window ignored");
        check(snapshot.weekly != null && snapshot.weekly.usedPercent == 25, "valid secondary preserved");
    }


    private static void testZeroDurationWindowIgnored() throws Exception {
        String json = "{\"plan_type\":\"plus\",\"rate_limit\":{" +
                "\"primary_window\":{\"used_percent\":30,\"limit_window_seconds\":0,\"reset_after_seconds\":1,\"reset_at\":2}}}";
        UsageSnapshot snapshot = UsageParser.parse(json, 1L);
        check(snapshot.fiveHour == null && snapshot.weekly == null,
                "zero-duration usage window ignored");
    }

    private static void testNextResetSelection() {
        long now = 1_000_000L;
        UsageWindow fiveHour = new UsageWindow(10, 18_000L, 0L, 1_100L);
        UsageWindow weekly = new UsageWindow(20, 604_800L, 0L, 1_200L);
        check(new UsageSnapshot("pro", true, false, fiveHour, weekly, now)
                        .nextResetMillis(now) == 1_100_000L,
                "earliest active reset ends the live monitor");
        check(new UsageSnapshot("pro", true, false, null, weekly, now)
                        .nextResetMillis(now) == 1_200_000L,
                "weekly-only account still has a monitor end time");

        UsageWindow expiredFiveHour = new UsageWindow(10, 18_000L, 0L, 900L);
        check(new UsageSnapshot("pro", true, false, expiredFiveHour, weekly, now)
                        .nextResetMillis(now) == 1_200_000L,
                "expired five-hour reset falls back to weekly");
        check(UsageSnapshot.currentWindow(expiredFiveHour, now) == null,
                "expired five-hour window is not displayed as current");
        check(UsageSnapshot.currentWindow(weekly, now) == weekly,
                "future weekly window remains available for display");
        check(new UsageSnapshot("pro", true, false, expiredFiveHour, null, now)
                        .nextResetMillis(now) == 0L,
                "no future reset does not create an unbounded monitor");
        UsageWindow fallback = new UsageWindow(10, 18_000L, 600L, 0L);
        UsageSnapshot fallbackSnapshot = new UsageSnapshot(
                "pro", true, false, fallback, null, now);
        check(fallbackSnapshot.nextResetMillis(now) == now + 600_000L,
                "reset-after fallback schedules the monitor from observation time");
        check(UsageSnapshot.currentWindow(fallback, now, now + 600_000L) == null,
                "reset-after fallback expires the current window consistently");
    }

    private static void testCelebrationDetection() {
        long firstFetch = 1_000_000L;
        UsageSnapshot previous = snapshot(2, 1, 3600L, firstFetch);
        UsageSnapshot earlyFull = snapshot(0, 0, 7200L, firstFetch + 1000L);
        int both = CelebrationDetector.detectUnexpectedRefills(previous, earlyFull);
        check(both == (CelebrationDetector.FIVE_HOUR | CelebrationDetector.WEEKLY),
                "98% and 99% remaining refills both celebrate");

        UsageSnapshot fullyUsed = snapshot(100, 50, 3600L, firstFetch);
        UsageSnapshot weeklyOnlyFull = snapshot(0, 0, 7200L, firstFetch + 2000L);
        int refill = CelebrationDetector.detectUnexpectedRefills(fullyUsed, weeklyOnlyFull);
        check(refill == (CelebrationDetector.FIVE_HOUR | CelebrationDetector.WEEKLY),
                "any non-full percentage reaching 100% celebrates");

        UsageSnapshot notFull = snapshot(1, 1, 7200L, firstFetch + 2000L);
        check(CelebrationDetector.detectUnexpectedRefills(previous, notFull) == 0,
                "99% is not a complete refill");

        UsageSnapshot atNaturalReset = snapshot(0, 0, 7200L, firstFetch + 3_600_000L);
        check(CelebrationDetector.detectUnexpectedRefills(previous, atNaturalReset) == 0,
                "countdown expiry is a natural reset");

        UsageSnapshot withoutCountdown = snapshot(50, 50, 0L, firstFetch);
        check(CelebrationDetector.detectUnexpectedRefills(withoutCountdown, earlyFull) == 0,
                "unknown reset time does not guess");
        check(CelebrationDetector.detectUnexpectedRefills(null, earlyFull) == 0,
                "first snapshot establishes a baseline");

        int allRefills = CelebrationDetector.FIVE_HOUR | CelebrationDetector.WEEKLY;
        check(CelebrationDetector.withoutUserResetRefills(allRefills, firstFetch + 1000L,
                firstFetch + 5000L, firstFetch + 5000L) == 0,
                "manual reset suppresses both refill celebrations");
        check(CelebrationDetector.withoutUserResetRefills(allRefills, firstFetch + 6000L,
                firstFetch + 5000L, firstFetch + 5000L) == allRefills,
                "expired manual reset suppression does not hide external refills");
        check(CelebrationDetector.withoutUserResetRefills(CelebrationDetector.MONTHLY,
                firstFetch + 1000L, 0L, 0L, firstFetch + 5000L) == 0,
                "manual reset suppresses a monthly refill celebration");

        UsageSnapshot monthlyUsed = monthlySnapshot(40, 3600L, firstFetch);
        UsageSnapshot monthlyFull = monthlySnapshot(0, 7200L, firstFetch + 1000L);
        check(CelebrationDetector.detectUnexpectedRefills(monthlyUsed, monthlyFull)
                        == CelebrationDetector.MONTHLY,
                "early monthly refill celebrates on the free tier");

        check(CelebrationDetector.resetCreditsAdded(-1, 2) == 0,
                "first reset-credit count establishes a baseline");
        check(CelebrationDetector.resetCreditsAdded(2, 3) == 1,
                "single reset-credit increase");
        check(CelebrationDetector.resetCreditsAdded(2, 5) == 3,
                "multiple reset-credit increase");
        check(CelebrationDetector.resetCreditsAdded(3, 2) == 0,
                "reset-credit decrease is not celebrated");

        System.out.println("Celebration demo: 98% and 99% remaining -> surprise refill for both windows.");
        System.out.println("Celebration demo: countdown elapsed -> natural reset, no surprise notification.");
        System.out.println("Celebration demo: user reset marker -> no surprise notification.");
        System.out.println("Celebration demo: reset credits 2 -> 5 -> notification reports 3 added credits.");
    }

    private static void testResetCreditExpiryReminders() {
        long now = 1_000_000L;
        RateLimitResetCredit soon = new RateLimitResetCredit("soon", "both", "available",
                now - 1, now + TimeUnit.HOURS.toMillis(48), "", "");
        RateLimitResetCredit later = new RateLimitResetCredit("later", "both", "available",
                now - 1, now + TimeUnit.DAYS.toMillis(7), "", "");
        RateLimitResetCredit redeemed = new RateLimitResetCredit("used", "both", "redeemed",
                now - 1, now + TimeUnit.HOURS.toMillis(2), "", "");
        RateLimitResetCredit expired = new RateLimitResetCredit("expired", "both", "available",
                now - 1, now - 1, "", "");
        long ninetyMinutes = TimeUnit.MINUTES.toMillis(90);
        List<ResetCreditExpiryReminder> reminders = ResetCreditExpiryReminder.plan(
                Arrays.asList(soon, later, redeemed, expired),
                Arrays.asList(TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(24),
                        ninetyMinutes, ninetyMinutes, 1L,
                        ResetCreditExpiryReminder.MAX_LEAD_TIME_MS + 1L),
                now);
        check(reminders.size() == 6,
                "every available credit gets each valid unique expiry reminder");
        check(reminders.get(0).creditId.equals("soon")
                        && reminders.get(0).leadTimeMillis == TimeUnit.HOURS.toMillis(24),
                "expiry reminders are sorted by trigger time");
        check(reminders.stream().anyMatch(reminder ->
                        reminder.creditId.equals("later")
                                && reminder.leadTimeMillis == ninetyMinutes),
                "arbitrary whole-minute reminder time is accepted");
        check(reminders.stream().noneMatch(reminder ->
                        reminder.creditId.equals("used")
                                || reminder.creditId.equals("expired")),
                "redeemed and expired credits are excluded");
        check(reminders.stream().map(ResetCreditExpiryReminder::token).distinct().count()
                        == reminders.size(),
                "reminder identities are unique across credits and lead times");
        System.out.println("Reset-credit expiry demo: multiple custom lead times planned for "
                + "every available credit.");
    }

    private static void testResetCreditExpiryOrdering() {
        long now = 2_000_000L;
        RateLimitResetCredit later = new RateLimitResetCredit("later", "both", "available",
                now - 1, now + TimeUnit.DAYS.toMillis(7), "", "");
        RateLimitResetCredit noExpiry = new RateLimitResetCredit("no-expiry", "both",
                "available", now - 1, 0L, "", "");
        RateLimitResetCredit soon = new RateLimitResetCredit("soon", "both", "available",
                now - 1, now + TimeUnit.HOURS.toMillis(4), "", "");
        RateLimitResetCredit redeemed = new RateLimitResetCredit("redeemed", "both",
                "redeemed", now - 1, now + TimeUnit.HOURS.toMillis(1), "", "");
        RateLimitResetCredit expired = new RateLimitResetCredit("expired", "both",
                "available", now - 1, now - 1, "", "");
        ResetCreditsSnapshot snapshot = new ResetCreditsSnapshot(3,
                Arrays.asList(later, noExpiry, redeemed, expired, soon), now);

        List<RateLimitResetCredit> ordered = snapshot.availableCreditsByExpiry(now);
        check(ordered.size() == 3, "only current available reset credits are shown");
        check("soon".equals(ordered.get(0).id), "soonest expiry is shown first");
        check("later".equals(ordered.get(1).id), "later expiry is shown second");
        check("no-expiry".equals(ordered.get(2).id),
                "credit without an expiry is shown last");
        check(snapshot.nextExpiryMillis(now) == soon.expiresAtMillis,
                "dashboard expiry uses the first sorted credit");
        System.out.println("Reset-credit inventory demo: available credits sort by expiry.");
    }

    private static void testResetCreditExpiryAutoApply() {
        long now = 3_000_000_000L;
        long minute = TimeUnit.MINUTES.toMillis(1);
        long lead = ResetCreditExpiryApply.DEFAULT_LEAD_MS;
        check(lead == TimeUnit.MINUTES.toMillis(30), "default auto-use lead is 30 minutes");
        RateLimitResetCredit due = new RateLimitResetCredit("due", "both", "available",
                now - 1, now + 20 * minute, "", "");
        RateLimitResetCredit later = new RateLimitResetCredit("later", "both", "available",
                now - 1, now + 90 * minute, "", "");
        RateLimitResetCredit redeemed = new RateLimitResetCredit("used", "both", "redeemed",
                now - 1, now + 10 * minute, "", "");
        RateLimitResetCredit expired = new RateLimitResetCredit("expired", "both", "available",
                now - 1, now - 1, "", "");
        RateLimitResetCredit noExpiry = new RateLimitResetCredit("none", "both", "available",
                now - 1, 0L, "", "");
        List<ResetCreditExpiryApply> planned = ResetCreditExpiryApply.plan(
                Arrays.asList(later, redeemed, expired, noExpiry, due), now, lead);
        check(planned.size() == 2, "only unexpired available credits are scheduled to auto-apply");
        check("due".equals(planned.get(0).creditId), "soonest expiry is scheduled first");
        check(planned.get(0).triggerAtMillis == due.expiresAtMillis - lead,
                "auto-apply triggers the chosen lead time before expiry");
        List<ResetCreditExpiryApply> dueNow = ResetCreditExpiryApply.due(
                Arrays.asList(due, later, expired), now, lead);
        check(dueNow.size() == 1 && "due".equals(dueNow.get(0).creditId),
                "only credits inside the lead window are due now");
        check(ResetCreditExpiryApply.isDue(now + 30 * minute, now, lead),
                "exactly the lead time remaining is inside the apply window");
        check(!ResetCreditExpiryApply.isDue(now + 31 * minute, now, lead),
                "just outside the lead time is not applied yet");
        check(ResetCreditExpiryApply.isDue(later.expiresAtMillis, now,
                        TimeUnit.HOURS.toMillis(2)),
                "a 2-hour lead picks up a credit expiring in 90 minutes");
        check(!ResetCreditExpiryApply.isDue(due.expiresAtMillis, now, minute),
                "the last-minute option waits until the final minute");
        check(!ResetCreditExpiryApply.isDue(expired.expiresAtMillis, now, lead),
                "already-expired credits are not applied");
        check(ResetCreditExpiryApply.normalizeLead(0L) == lead,
                "missing lead falls back to the default");
        check(ResetCreditExpiryApply.normalizeLead(TimeUnit.MINUTES.toMillis(50))
                        == TimeUnit.HOURS.toMillis(1),
                "an off-menu lead snaps to the nearest option");
        check("30 minutes".equals(ResetCreditExpiryApply.leadLabel(lead))
                        && "1 hour".equals(ResetCreditExpiryApply.leadLabel(60 * minute))
                        && "1 minute".equals(ResetCreditExpiryApply.leadLabel(minute)),
                "lead labels read naturally");
        RateLimitResetCredit alsoDue = new RateLimitResetCredit("also-due", "both", "available",
                now - 1, now + 5 * minute, "", "");
        List<ResetCreditExpiryApply> twoDue = ResetCreditExpiryApply.due(
                Arrays.asList(alsoDue, due, later), now, lead);
        check(twoDue.size() == 2
                        && "also-due".equals(twoDue.get(0).creditId)
                        && "due".equals(twoDue.get(1).creditId),
                "several due credits apply soonest first");
        System.out.println("Reset-credit auto-apply demo: credits inside the chosen lead window "
                + "apply, later and expired credits do not.");
    }

    private static void testResetCreditLowUsageAutoUse() {
        long now = 3_000_000_000L;
        long hour = 3600L;
        // 5-hour at 1% remaining, weekly at 40%, both resetting in an hour.
        UsageSnapshot low = snapshot(99, 60, hour, now);
        ResetCreditLowUsageAutoUse fire = ResetCreditLowUsageAutoUse.evaluate(low,
                ResetCreditLowUsageAutoUse.METRIC_BOTH, 1, 2, "", now);
        check(fire.fires() && "5-hour".equals(fire.windowLabel) && fire.remainingPercent == 1,
                "1% remaining on the 5-hour limit fires at a 1% threshold");
        check(!ResetCreditLowUsageAutoUse.evaluate(low,
                        ResetCreditLowUsageAutoUse.METRIC_WEEKLY, 1, 2, "", now).fires(),
                "weekly-only rule ignores a low 5-hour limit");
        check(!ResetCreditLowUsageAutoUse.evaluate(snapshot(97, 60, hour, now),
                        ResetCreditLowUsageAutoUse.METRIC_BOTH, 2, 2, "", now).fires(),
                "3% remaining waits at a 2% threshold");
        check(ResetCreditLowUsageAutoUse.evaluate(snapshot(98, 60, hour, now),
                        ResetCreditLowUsageAutoUse.METRIC_BOTH, 2, 2, "", now).fires(),
                "2% remaining fires at a 2% threshold");
        ResetCreditLowUsageAutoUse again = ResetCreditLowUsageAutoUse.evaluate(
                snapshot(100, 60, hour - 60, now + 60_000L),
                ResetCreditLowUsageAutoUse.METRIC_BOTH, 1, 1, fire.windowToken, now + 60_000L);
        check(ResetCreditLowUsageAutoUse.SKIP_ALREADY_USED.equals(again.outcome),
                "a rule fires once per usage window");
        check(ResetCreditLowUsageAutoUse.SKIP_NO_CREDIT.equals(ResetCreditLowUsageAutoUse
                        .evaluate(low, ResetCreditLowUsageAutoUse.METRIC_BOTH, 1, 0, "", now)
                        .outcome),
                "no credit means no attempt");
        check(ResetCreditLowUsageAutoUse.SKIP_RESET_IMMINENT.equals(ResetCreditLowUsageAutoUse
                        .evaluate(snapshot(100, 60, 600L, now),
                                ResetCreditLowUsageAutoUse.METRIC_BOTH, 1, 1, "", now).outcome),
                "a window refilling within 15 minutes is not worth a credit");
        check(ResetCreditLowUsageAutoUse.WAIT.equals(ResetCreditLowUsageAutoUse.evaluate(low,
                        ResetCreditLowUsageAutoUse.METRIC_BOTH, 1, 1, "",
                        now + TimeUnit.MINUTES.toMillis(5)).outcome),
                "a stale snapshot never spends a credit");
        ResetCreditLowUsageAutoUse monthly = ResetCreditLowUsageAutoUse.evaluate(
                monthlySnapshot(100, 86_400L, now),
                ResetCreditLowUsageAutoUse.METRIC_WEEKLY, 0, 1, "", now);
        check(monthly.fires() && "monthly".equals(monthly.windowLabel),
                "the weekly rule covers a free-tier monthly window");
        check(ResetCreditLowUsageAutoUse.normalizeThreshold(7)
                        == ResetCreditLowUsageAutoUse.DEFAULT_THRESHOLD,
                "an off-menu threshold falls back to 1%");
        System.out.println("Reset-credit low-usage demo: one credit fires per window at the "
                + "threshold, never from stale data or right before a natural refill.");
    }

    private static String claudeUsageBody(boolean atLimit, boolean usableNow, String endsAt,
            int resetsLeft) {
        return "{\"five_hour\":{\"utilization\":100,\"resets_at\":\"2070-01-01T00:00:00Z\"},"
                + "\"cedar_ember\":{\"eligible\":true,\"at_limit\":" + atLimit + ","
                + "\"grants\":[{\"id\":\"grant_late\",\"label\":\"Reset\",\"resets_total\":1,"
                + "\"resets_left\":1,\"ends_at\":\"2071-01-01T00:00:00Z\",\"clears\":[\"seven_day\"],"
                + "\"usable_now\":" + usableNow + ",\"type\":\"weekly\"},"
                + "{\"id\":\"grant_soon\",\"label\":\"Reset\",\"resets_total\":2,"
                + "\"resets_left\":" + resetsLeft + ",\"ends_at\":" + endsAt + ","
                + "\"clears\":[\"five_hour\"],\"paused\":false,\"usable_now\":" + usableNow + ","
                + "\"use_requires_limit\":true,\"type\":\"session\","
                + "\"unavailable_reason\":" + (usableNow ? "null" : "\"needs_limit\"") + "},"
                + "{\"id\":\"BAD ID\",\"resets_left\":5}],"
                + "\"next_grant_id\":\"grant_soon\",\"cooldown_until\":null}}";
    }

    private static void testClaudeSavedResets() throws Exception {
        long now = java.time.Instant.parse("2070-06-01T00:00:00Z").toEpochMilli();
        ClaudeSavedResets parsed = ClaudeSavedResets.fromUsageBody(
                claudeUsageBody(false, false, "\"2070-06-03T00:00:00Z\"", 2), now);
        check(parsed != null && parsed.eligible && !parsed.atLimit,
                "cedar_ember block parses");
        check(parsed.grants.size() == 2, "grants with malformed ids are dropped");
        check(ClaudeSavedResets.needsClaudeCodeSignIn("user:profile user:inference")
                && !ClaudeSavedResets.needsClaudeCodeSignIn(ClaudeCodeApi.SCOPE)
                && !ClaudeSavedResets.needsClaudeCodeSignIn(""),
                "sign-ins without the Claude Code scope are flagged; unknown is not");
        check(ClaudeCodeApi.SCOPE.contains("user:sessions:claude_code")
                && "user:sessions:claude_code".equals(ClaudeCodeApi.refreshBody("r",
                        "user:sessions:claude_code").get("scope"))
                && !ClaudeCodeApi.refreshBody("r").containsKey("scope"),
                "Claude sign-in and refresh ask for Claude Code's scopes");
        String report = ClaudeSavedResets.describeCheck(200,
                claudeUsageBody(false, true, "\"2070-06-03T00:00:00Z\"", 2),
                "user:profile user:inference", now);
        check(report.contains("HTTP 200") && report.contains("Offered resets: yes")
                && report.contains("Resets you can use: 3") && report.contains("Sign out")
                && report.contains("Raw: {"), "reset check report shows the server's answer");
        check(ClaudeSavedResets.describeCheck(200,
                "{\"cedar_ember\":{\"eligible\":false,\"ineligible_reason\":\"surface\"}}",
                ClaudeCodeApi.SCOPE, now).contains("Offered resets: no (surface)")
                && ClaudeSavedResets.describeCheck(200, "{}", "", now)
                        .contains("not included"),
                "reset check report explains an ineligible or missing block");
        check(parsed.availableCount(now) == 3 && parsed.shouldDisplay(now),
                "available resets add up across grants");
        check("grant_soon".equals(parsed.nextGrant(now).id), "server's next grant comes first");
        check("5-hour limit".equals(parsed.nextGrant(now).clearsLabel()),
                "session grant clears the 5-hour limit");
        check(parsed.usableGrant(now) == null
                        && "Works once you hit a usage limit".equals(
                                parsed.unavailableMessage(now)),
                "a grant the server marks needs_limit waits for a limit");
        check("Session reset".equals(parsed.nextGrant(now).title())
                        && parsed.grants.get(0).clearsWeekly(),
                "grant titles and cleared limits read from type and clears");
        ClaudeSavedResets roundTrip = ClaudeSavedResets.fromJson(
                new org.json.JSONObject(parsed.toJson().toString()));
        check(roundTrip.availableCount(now) == 3 && "grant_soon".equals(roundTrip.nextGrantId)
                        && roundTrip.grants.get(1).endsAtMillis == parsed.grants.get(1).endsAtMillis,
                "saved resets survive the on-device cache");
        ClaudeSavedResets usable = ClaudeSavedResets.fromUsageBody(
                claudeUsageBody(true, true, "\"2070-06-03T00:00:00Z\"", 2), now);
        check("grant_soon".equals(usable.usableGrant(now).id), "usable grant is picked at a limit");
        check(usable.afterUse("grant_soon").availableCount(now) == 2,
                "using a reset takes one off the card right away");
        check(ClaudeSavedResets.fromUsageBody("{\"five_hour\":{}}", now) == null,
                "no cedar_ember block means no saved resets");
        check(ClaudeSavedResets.fromUsageBody("not json", now) == null,
                "unreadable body means no saved resets");
        ClaudeSavedResets none = ClaudeSavedResets.fromUsageBody(
                claudeUsageBody(false, false, "\"2070-05-01T00:00:00Z\"", 0), now);
        check(none.availableCount(now) == 1, "expired or empty grants don't count");

        org.json.JSONObject body = ClaudeSavedResets.claimBody("grant_soon", "req-123");
        check("cedar_ember".equals(body.getString("program"))
                        && "grant_soon".equals(body.getString("grant_id"))
                        && "req-123".equals(body.getString("request_id")),
                "claim body matches Claude Code's request");
        boolean rejected = false;
        try {
            ClaudeSavedResets.claimBody("../evil", "req");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "malformed grant ids are never sent");
        check(ClaudeCodeApi.resetRateLimitsUrl("0f1e2d3c-aaaa-bbbb-cccc-1234567890ab").equals(
                        "https://api.anthropic.com/api/organizations/"
                                + "0f1e2d3c-aaaa-bbbb-cccc-1234567890ab/reset_rate_limits"),
                "claim URL targets the organization");
        check(ClaudeCodeApi.USAGE_WITH_SAVED_RESETS_URL.endsWith("/api/oauth/usage?cedar_ember=1"),
                "usage refresh asks for saved resets");

        ClaudeSavedResets.ClaimResult reset = ClaudeSavedResets.ClaimResult.parse(
                "{\"result\":\"reset\",\"resets_left\":1,\"cleared\":[\"five_hour\"]}");
        check(reset.applied() && reset.resetsLeft == 1 && reset.cleared.contains("five_hour"),
                "successful claim parses");
        check("Reset applied to 1 usage window.".equals(reset.userMessage()),
                "success uses the Codex wording");
        ClaudeSavedResets.ClaimResult notLimited = ClaudeSavedResets.ClaimResult.parse(
                "{\"result\":\"not_limited\"}");
        check(!notLimited.applied() && !notLimited.retryable()
                        && notLimited.userMessage().contains("no used Claude Code allowance"),
                "not_limited is final, worded like Codex's nothing-to-reset");
        check(ClaudeSavedResets.ClaimResult.UNAVAILABLE.equals(
                        ClaudeSavedResets.ClaimResult.parse("{\"result\":\"surprise\"}").result),
                "unknown results read as unavailable");
        check(ClaudeSavedResets.ClaimResult.parse("<html>").retryable(),
                "unreadable claim responses retry");
        System.out.println("Claude saved resets: status, cache, claim body, and results parse.");
    }

    private static ClaudeResetAutoUse claudeAuto(ClaudeSavedResets status, UsageSnapshot usage,
            boolean expiry, boolean lowUsage, String lastExpiry, String lastLow, long now) {
        return ClaudeResetAutoUse.evaluate(status, usage, expiry, TimeUnit.HOURS.toMillis(2),
                lowUsage, 1, ResetCreditLowUsageAutoUse.METRIC_BOTH, lastExpiry, lastLow, now);
    }

    private static void testClaudeResetAutoUse() {
        long now = java.time.Instant.parse("2070-06-01T00:00:00Z").toEpochMilli();
        long hour = 3600L;
        UsageSnapshot healthy = snapshot(19, 49, hour, now);
        UsageSnapshot low = snapshot(99, 49, hour, now);
        // A full reset works any time, like claude.ai's "Reset for free".
        ClaudeSavedResets usableLater = ClaudeSavedResets.fromUsageBody(
                claudeUsageBody(false, true, "\"2070-06-20T00:00:00Z\"", 1), now);
        ClaudeSavedResets usableSoon = ClaudeSavedResets.fromUsageBody(
                claudeUsageBody(false, true, "\"2070-06-01T01:30:00Z\"", 1), now);
        ClaudeSavedResets heldBack = ClaudeSavedResets.fromUsageBody(
                claudeUsageBody(false, false, "\"2070-06-01T01:30:00Z\"", 1), now);

        check(!claudeAuto(usableLater, healthy, true, true, "", "", now).fires(),
                "nothing fires with plenty left and no reset expiring");
        ClaudeResetAutoUse expiring = claudeAuto(usableSoon, healthy, true, false, "", "", now);
        check(ClaudeResetAutoUse.FIRE_EXPIRING.equals(expiring.outcome)
                        && "grant_soon".equals(expiring.grant.id),
                "a reset inside the lead time is used before it expires");
        check(!claudeAuto(usableSoon, healthy, false, false, "", "", now).fires(),
                "both rules off means nothing fires");
        check(!claudeAuto(heldBack, healthy, true, true, "", "", now).fires(),
                "a grant the server holds back is never spent");
        check(ClaudeResetAutoUse.SKIP_ALREADY_TRIED.equals(claudeAuto(usableSoon, healthy, true,
                        false, expiring.attemptKey, "", now).outcome),
                "an expiring reset is tried once");
        ClaudeResetAutoUse lowFire = claudeAuto(usableLater, low, false, true, "", "", now);
        check(ClaudeResetAutoUse.FIRE_LOW_USAGE.equals(lowFire.outcome)
                        && "grant_soon".equals(lowFire.grant.id)
                        && "5-hour".equals(lowFire.windowLabel),
                "running low on the 5-hour limit uses a reset that refills it");
        check(ClaudeResetAutoUse.SKIP_ALREADY_TRIED.equals(claudeAuto(usableLater, low, false,
                        true, "", lowFire.attemptKey, now).outcome),
                "running-low rule fires once per window");
        UsageSnapshot weeklyLow = snapshot(19, 100, 2 * hour, now);
        ClaudeResetAutoUse weekly = claudeAuto(usableLater, weeklyLow, false, true, "", "", now);
        check(weekly.fires() && "grant_late".equals(weekly.grant.id),
                "a low weekly limit picks the reset that refills the weekly limit");
        check(ClaudeResetAutoUse.WAIT.equals(claudeAuto(usableLater, snapshot(100, 49, 600L, now),
                        false, true, "", "", now).outcome),
                "a limit that refills within 15 minutes is not worth a reset");
        check(!claudeAuto(usableSoon, low, true, true, "", "",
                        now + TimeUnit.MINUTES.toMillis(5)).fires(),
                "a stale status never spends a reset");
        ResetCreditsSnapshot shared = usableLater.toResetCreditsSnapshot(now);
        check(shared.availableCount == 2 && "Session reset".equals(
                        shared.nextExpiringAvailable(now).title),
                "Claude resets feed the shared Reset credits inventory");
        check(shared.nextExpiryMillis(now) == usableLater.nextExpiryMillis(now),
                "shared inventory keeps the next expiry");
        System.out.println("Claude saved-reset auto-use: expiry lead time and running-low rules.");
    }

    private static UsageSnapshot snapshot(int fiveHourUsed, int weeklyUsed,
            long resetAfterSeconds, long fetchedAtMillis) {
        return new UsageSnapshot("pro", true, false,
                new UsageWindow(fiveHourUsed, 18_000L, resetAfterSeconds, 0L),
                new UsageWindow(weeklyUsed, 604_800L, resetAfterSeconds, 0L),
                fetchedAtMillis);
    }

    private static UsageSnapshot monthlySnapshot(int monthlyUsed, long resetAfterSeconds,
            long fetchedAtMillis) {
        return new UsageSnapshot("free", true, false, null, null,
                new UsageWindow(monthlyUsed, 2_592_000L, resetAfterSeconds, 0L),
                java.util.Collections.emptyList(), null, -1, fetchedAtMillis);
    }

    private static void testJwtMerge() {
        String id = jwt("{\"email\":\"person@example.com\"}");
        String access = jwt("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct_123\"}}");
        JwtClaims claims = JwtClaims.fromTokens(id, access);
        check("person@example.com".equals(claims.email), "JWT email");
        check("acct_123".equals(claims.accountId), "JWT account merge");
    }

    private static void testPkce() throws Exception {
        Pkce pkce = Pkce.generate();
        check(pkce.verifier.length() >= 43 && pkce.verifier.length() <= 128, "PKCE verifier length");
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pkce.verifier.getBytes(StandardCharsets.US_ASCII)));
        check(expected.equals(pkce.challenge), "PKCE S256 challenge");
        check(pkce.state.length() >= 43, "OAuth state entropy");
    }


    private static void testSettingsTransfer() throws Exception {
        WidgetOptions widget = new WidgetOptions(WidgetOptions.STYLE_DIALS,
                WidgetOptions.DENSITY_COMFORTABLE, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_MAX, WidgetOptions.THEME_DARK, WidgetOptions.ACCENT_BLUE,
                94, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                WidgetOptions.METRIC_BOTH, false, true, false, true, true, false)
                .withPercentSymbol(false)
                .withVisibleMeters("five_hour,limit:codex-spark:primary,weekly");
        org.json.JSONObject appSettings = new org.json.JSONObject()
                .put("app_theme", WidgetOptions.THEME_DARK)
                .put("material_you", true)
                .put("refresh_minutes", 15)
                .put("refresh_on_launch", false)
                .put("usage_pace_enabled", true)
                .put("usage_pace_sensitivity", UsagePace.RELAXED)
                .put("automatic_update_checks", true)
                .put("notify_updates", true)
                .put("check_interval_hours", 24)
                .put("default_widget", SettingsTransfer.widgetOptionsToJson(widget));
        org.json.JSONObject notifications = new org.json.JSONObject()
                .put("style", "notification")
                .put("metric", "weekly")
                .put("threshold", 50)
                .put("unexpected_refills", false)
                .put("reset_credit_increases", true)
                .put("reset_credit_expiry", true)
                .put("reset_credit_expiry_auto_apply", true)
                .put("reset_credit_expiry_auto_apply_lead_ms", 3_600_000L)
                .put("reset_credit_low_usage_auto_use", true)
                .put("reset_credit_low_usage_threshold", 2)
                .put("reset_credit_low_usage_metric", "weekly")
                .put("reset_credit_expiry_lead_times",
                        SettingsTransfer.leadTimesToJson(Arrays.asList(
                                TimeUnit.HOURS.toMillis(1),
                                TimeUnit.DAYS.toMillis(1))));
        org.json.JSONObject nowBar = new org.json.JSONObject()
                .put("display_mode", NowBarDisplayMode.SAMSUNG_COMPATIBILITY)
                .put("percent_mode", NowBarPercentMode.WEEKLY)
                .put("auto_enabled", true)
                .put("accelerated_enabled", true)
                .put("metric", "five_hour")
                .put("threshold", 10);
        org.json.JSONObject authentication = new org.json.JSONObject()
                .put("access_token", "access-demo")
                .put("refresh_token", "refresh-demo")
                .put("id_token", "id-demo")
                .put("expires_at", 1_700_000_000_000L)
                .put("account_id", "acct_demo")
                .put("email", "demo@example.com");

        SettingsTransfer.Document full = SettingsTransfer.create(1_700_000_000_000L, appSettings,
                notifications, nowBar, authentication);
        String json = full.toJsonString();
        check(json.contains("\"contains_authentication\": true"), "auth flag present");
        check(json.contains(SettingsTransfer.SECURITY_WARNING), "security warning embedded");
        SettingsTransfer.Document parsed = SettingsTransfer.parse(json);
        check(parsed.hasAppSettings() && parsed.hasNotifications()
                        && parsed.hasNowBar() && parsed.hasAuthentication(),
                "all sections round-trip");
        check(parsed.presentSections().size() == 4, "four present sections");
        WidgetOptions restored = SettingsTransfer.widgetOptionsFromJson(
                parsed.appSettings.getJSONObject("default_widget"));
        check(WidgetOptions.THEME_DARK.equals(restored.theme), "widget theme restored");
        check(WidgetOptions.ACCENT_BLUE.equals(restored.accent), "widget accent restored");
        check(WidgetOptions.STYLE_DIALS.equals(restored.layout), "widget layout preference restored");
        check("five_hour,limit:codex-spark:primary,weekly".equals(restored.visibleMeters),
                "widget visible meters restored");
        check(parsed.appSettings.getBoolean("material_you"), "material you preference restored");
        check(UsagePace.RELAXED.equals(
                        parsed.appSettings.getString("usage_pace_sensitivity")),
                "usage pace sensitivity preserved");
        check(!restored.showPercentSymbol, "percent symbol flag restored");
        check(parsed.notifications.getInt("threshold") == 50, "notification threshold restored");
        check(parsed.notifications.getBoolean("reset_credit_expiry_auto_apply"),
                "expiry auto-apply preference restored");
        check(parsed.notifications.getLong("reset_credit_expiry_auto_apply_lead_ms")
                        == 3_600_000L,
                "expiry auto-apply lead time restored");
        check(parsed.notifications.getBoolean("reset_credit_low_usage_auto_use")
                        && parsed.notifications.getInt("reset_credit_low_usage_threshold") == 2
                        && "weekly".equals(parsed.notifications.getString(
                                "reset_credit_low_usage_metric")),
                "low-usage auto-use preferences restored");
        check(SettingsTransfer.leadTimesFromJson(
                parsed.notifications.getJSONArray("reset_credit_expiry_lead_times")).size() == 2,
                "lead times restored");
        check(NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(
                        parsed.nowBar.getString("display_mode")),
                "Now Bar mode restored");
        check(parsed.nowBar.getBoolean("accelerated_enabled"),
                "accelerated Now Bar preference preserved");
        check("refresh-demo".equals(parsed.authentication.getString("refresh_token")),
                "auth refresh token restored");

        WidgetOptions currentDefaults = new WidgetOptions(WidgetOptions.STYLE_DIALS,
                WidgetOptions.DENSITY_COMPACT, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_LARGE, WidgetOptions.THEME_LIGHT, WidgetOptions.ACCENT_ROSE,
                72, WidgetOptions.RESET_RELATIVE, WidgetOptions.DISPLAY_USED,
                WidgetOptions.METRIC_WEEKLY, true, true, true, false, true, true)
                .withPercentSymbol(true);
        WidgetOptions merged = SettingsTransfer.widgetOptionsFromJson(
                new org.json.JSONObject().put("accent", WidgetOptions.ACCENT_CYAN),
                currentDefaults);
        check(WidgetOptions.ACCENT_CYAN.equals(merged.accent), "partial widget updates accent");
        check(WidgetOptions.THEME_LIGHT.equals(merged.theme),
                "partial widget keeps current theme");
        check(WidgetOptions.STYLE_DIALS.equals(merged.layout),
                "partial widget keeps current layout");
        check(merged.showPlan && merged.showResetCredits,
                "partial widget keeps current visibility flags");

        boolean malformedLeadTimesRejected = false;
        try {
            SettingsTransfer.requireLeadTimes(
                    new org.json.JSONObject().put("reset_credit_expiry_lead_times", "oops"),
                    "reset_credit_expiry_lead_times");
        } catch (IllegalArgumentException expected) {
            malformedLeadTimesRejected = true;
        }
        check(malformedLeadTimesRejected, "malformed lead times rejected");
        boolean badElementRejected = false;
        try {
            SettingsTransfer.leadTimesFromJson(new org.json.JSONArray().put("oops"));
        } catch (IllegalArgumentException expected) {
            badElementRejected = true;
        }
        check(badElementRejected, "non-numeric lead time element rejected");
        boolean outOfRangeRejected = false;
        try {
            SettingsTransfer.leadTimesFromJson(new org.json.JSONArray().put(1L));
        } catch (IllegalArgumentException expected) {
            outOfRangeRejected = true;
        }
        check(outOfRangeRejected, "out-of-range lead time element rejected");
        check(SettingsTransfer.leadTimesFromJson(new org.json.JSONArray()).isEmpty(),
                "empty lead times array remains allowed");
        boolean nullLeadTimesRejected = false;
        try {
            SettingsTransfer.leadTimesFromJson(null);
        } catch (IllegalArgumentException expected) {
            nullLeadTimesRejected = true;
        }
        check(nullLeadTimesRejected, "null lead times array rejected");

        SettingsTransfer.Document settingsOnly = parsed.selecting(true, true, true, false);
        check(settingsOnly.hasAppSettings() && !settingsOnly.hasAuthentication(),
                "section selection drops auth");
        check(!settingsOnly.toJson().optBoolean("contains_authentication", true),
                "settings-only export clears auth flag");
        check(!settingsOnly.toJson().has("security_warning"),
                "settings-only export omits security warning");

        boolean rejected = false;
        try {
            SettingsTransfer.parse(new org.json.JSONObject()
                    .put("format", "not_codex")
                    .put("version", 1)
                    .put("sections", new org.json.JSONObject().put("app_settings", appSettings)));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "unknown format rejected");

        boolean emptyRejected = false;
        try {
            SettingsTransfer.parse(new org.json.JSONObject()
                    .put("format", SettingsTransfer.FORMAT)
                    .put("version", SettingsTransfer.VERSION)
                    .put("sections", new org.json.JSONObject()));
        } catch (IllegalArgumentException expected) {
            emptyRejected = true;
        }
        check(emptyRejected, "empty sections rejected");
        check(SettingsTransfer.isAuthenticationSection(
                        SettingsTransfer.SECTION_AUTHENTICATION),
                "auth section detector");
        check("App settings".equals(
                        SettingsTransfer.sectionTitle(SettingsTransfer.SECTION_APP_SETTINGS)),
                "section title helper");
        System.out.println("Settings transfer JSON sections and auth warning round-trip cleanly.");
    }

    private static void testWidgetOptions() {
        WidgetOptions migrated = new WidgetOptions(WidgetOptions.LAYOUT_DETAILED,
                WidgetOptions.THEME_DARK, WidgetOptions.ACCENT_BLUE, 72,
                WidgetOptions.RESET_BOTH, WidgetOptions.DISPLAY_USED);
        check(WidgetOptions.STYLE_BARS.equals(migrated.layout), "legacy detailed migration");
        check(WidgetOptions.DENSITY_AUTO.equals(migrated.density), "default density");
        // 72 is equidistant from 56 and 88; prefer the stronger fill (88).
        check(migrated.opacity == 88, "legacy 72% opacity snaps to medium fill");
        WidgetOptions safe = new WidgetOptions("invalid", "invalid", "invalid", "invalid", 13,
                "invalid", "invalid", true, false, true);
        check(WidgetOptions.STYLE_AUTO.equals(safe.layout), "invalid style fallback");
        check(safe.opacity == 88, "invalid opacity fallback");
        check(WidgetOptions.ACCENT_MINT.equals(safe.accent), "invalid accent fallback");
        check(WidgetOptions.SURFACE_MATERIAL.equals(safe.surfaceStyle), "legacy surface fallback");
        check(WidgetOptions.GRAPHIC_AUTO.equals(safe.graphicScale), "legacy graphic fallback");
        check(!safe.showUpdated && safe.showRefresh, "boolean options");

        check(WidgetOptions.OPACITY_LEVELS.length == 3,
                "One UI widget opacity uses three levels");
        check(WidgetOptions.snapOpacity(0) == 0, "background-off stays at 0");
        check(WidgetOptions.snapOpacity(15) == 56, "legacy 15% maps to low fill");
        check(WidgetOptions.snapOpacity(40) == 56, "legacy 40% maps to low fill");
        check(WidgetOptions.snapOpacity(70) == 56, "legacy 70% maps to low fill");
        check(WidgetOptions.snapOpacity(94) == 100, "legacy 94% maps to full fill");
        check(WidgetOptions.opacityIndex(0) == 1, "background-off restores medium slider");
        check(WidgetOptions.opacityIndex(88) == 1, "default opacity is middle tick");

        WidgetOptions transparent = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_COMFORTABLE, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_MAX, WidgetOptions.THEME_LIGHT, WidgetOptions.ACCENT_VIOLET,
                0, WidgetOptions.RESET_RELATIVE, WidgetOptions.DISPLAY_REMAINING,
                false, true, false);
        check(transparent.opacity == 0, "transparent background accepted");
        check(WidgetOptions.SURFACE_ONE_UI.equals(transparent.surfaceStyle), "One UI widget style");
        check(WidgetOptions.GRAPHIC_MAX.equals(transparent.graphicScale), "maximum graphic scale");
        check(!WidgetOptions.defaults().showTitle, "widget title defaults off");
        check(WidgetOptions.defaults().opacity == WidgetOptions.DEFAULT_OPACITY,
                "default fill uses medium opacity");

        WidgetOptions low = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM, WidgetOptions.ACCENT_BLUE,
                56, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                "both", false, false, false, false, false, false);
        WidgetOptions high = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM, WidgetOptions.ACCENT_BLUE,
                100, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                "both", false, false, false, false, false, false);
        check(low.opacity == 56, "low fill strength accepted");
        check(high.opacity == 100, "full fill strength accepted");
        WidgetOptions fiveOnly = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM, WidgetOptions.ACCENT_BLUE,
                88, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                WidgetOptions.METRIC_FIVE_HOUR, false, false, false, false, false, false);
        WidgetOptions weeklyOnly = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM, WidgetOptions.ACCENT_BLUE,
                88, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                WidgetOptions.METRIC_WEEKLY, false, false, false, false, false, false);
        check(fiveOnly.singleMetric() && fiveOnly.showsFiveHour()
                        && !fiveOnly.showsWeekly(),
                "5-hour-only widget exposes one dial");
        check(weeklyOnly.singleMetric() && !weeklyOnly.showsFiveHour()
                        && weeklyOnly.showsWeekly(),
                "weekly-only widget exposes one dial");
        check(WidgetMeters.PREF_AUTO.equals(WidgetOptions.defaults().layoutPreference()),
                "default layout preference is adaptive");
        WidgetOptions dialsPref = new WidgetOptions(WidgetOptions.STYLE_DIALS,
                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM, WidgetOptions.ACCENT_BLUE,
                88, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                "both", false, false, false, false, false, false);
        check(WidgetMeters.PREF_DIALS.equals(dialsPref.layoutPreference()),
                "dials layout preference preserved");
        WidgetOptions barsPref = new WidgetOptions(WidgetOptions.STYLE_BARS,
                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM, WidgetOptions.ACCENT_BLUE,
                88, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                "both", false, false, false, false, false, false);
        check(WidgetMeters.PREF_BARS.equals(barsPref.layoutPreference()),
                "bars layout preference preserved");
        check(WidgetMeters.PREF_AUTO.equals(
                        new WidgetOptions(WidgetOptions.STYLE_RINGS,
                                WidgetOptions.DENSITY_AUTO, WidgetOptions.SURFACE_ONE_UI,
                                WidgetOptions.GRAPHIC_AUTO, WidgetOptions.THEME_SYSTEM,
                                WidgetOptions.ACCENT_BLUE, 88, WidgetOptions.RESET_HIDDEN,
                                WidgetOptions.DISPLAY_REMAINING, "both", false, false, false,
                                false, false, false).layoutPreference()),
                "legacy rings maps to adaptive preference");
    }

    private static void testWidgetMeters() {
        check(WidgetMeters.serialize(WidgetMeters.defaultVisible())
                        .equals("five_hour,weekly,next_reset,reset_credits"),
                "default visible meters csv");
        check(WidgetMeters.serialize(WidgetMeters.fromMetricMode("five_hour"))
                        .equals("five_hour"),
                "metric mode five-hour migrates");
        check(WidgetMeters.serialize(WidgetMeters.fromMetricMode("weekly"))
                        .equals("weekly"),
                "metric mode weekly migrates");
        check(WidgetMeters.effectiveVisibleCsv("", "both")
                        .equals("five_hour,weekly,next_reset,reset_credits"),
                "empty visible meters falls back to metric mode");
        check(WidgetMeters.effectiveVisibleCsv("weekly,five_hour", "both")
                        .equals("weekly,five_hour"),
                "saved visible meters keep order");

        UsageLimit spark = new UsageLimit("codex-spark", "GPT-5.3-Codex-Spark",
                "codex_bengalfox", true, false,
                new UsageWindow(40, 18000L, 600L, 0L),
                new UsageWindow(70, 604800L, 600L, 0L));
        UsageSnapshot snapshot = new UsageSnapshot("plus", true, false,
                new UsageWindow(20, 18000L, 600L, 0L),
                new UsageWindow(50, 604800L, 600L, 0L),
                java.util.Arrays.asList(spark), null, 0, System.currentTimeMillis());
        List<String> available = WidgetMeters.availableKeys(snapshot);
        check(available.equals(java.util.Arrays.asList(
                        WidgetMeters.FIVE_HOUR,
                        WidgetMeters.WEEKLY,
                        WidgetMeters.NEXT_RESET,
                        WidgetMeters.RESET_CREDITS)),
                "available meters exclude model-specific Spark limits");
        check(!available.contains(WidgetMeters.limitPrimaryKey(spark))
                        && !available.contains(WidgetMeters.limitSecondaryKey(spark)),
                "Spark limit keys are not offered for widgets");
        List<String> resolved = WidgetMeters.resolveVisible(
                "five_hour,limit:codex-spark:primary,limit:missing:primary,weekly",
                available);
        check(resolved.equals(java.util.Arrays.asList(
                        WidgetMeters.FIVE_HOUR,
                        WidgetMeters.WEEKLY)),
                "resolveVisible drops Spark and stale keys and keeps order");
        check(WidgetMeters.cap(resolved, 2).equals(java.util.Arrays.asList(
                        WidgetMeters.FIVE_HOUR, WidgetMeters.WEEKLY)),
                "capacity truncates to first N meters");
        check(WidgetMeters.resolveVisibleOrDefault(
                        "limit:gone-model:primary", available, "both")
                        .equals(WidgetMeters.defaultVisible()),
                "all-stale Spark-only selection falls back to default meters");
        check(WidgetMeters.resolveVisibleOrDefault(
                        "limit:gone-model:primary", available, "weekly")
                        .equals(WidgetMeters.defaultVisible()),
                "stale Spark-only selection falls back to defaults, not single weekly");
        check(WidgetMeters.resolveVisibleOrDefault(
                        "weekly,five_hour", available, "both")
                        .equals(java.util.Arrays.asList(WidgetMeters.WEEKLY,
                                WidgetMeters.FIVE_HOUR)),
                "valid selection is not replaced by fallback");
        check(WidgetMeters.resolveVisibleForWidget(
                        "five_hour,limit:codex-spark:primary", available, "both")
                        .equals(java.util.Arrays.asList(
                                WidgetMeters.FIVE_HOUR, WidgetMeters.WEEKLY)),
                "dropping Spark restores weekly so adaptive stays multi-meter");
        check(!WidgetMeters.resolvedSingleUsageMetric(
                        "five_hour,limit:codex-spark:primary", available, "both"),
                "Spark-stripped multi selection is not single-metric");
        check(WidgetMeters.VISUAL_BATTERY_LIST.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_AUTO,
                        WidgetMeters.resolvedSingleUsageMetric(
                                "five_hour,limit:codex-spark:primary", available, "both"),
                        2, 2, 156, 110)),
                "adaptive tall stays bars after Spark keys are stripped");
        check(WidgetMeters.resolvedSingleUsageMetric("five_hour", available, "five_hour"),
                "intentional five-hour-only selection stays single-metric");
        check(WidgetMeters.lockSlotCapacity() == 2, "lock widgets cap at two meters");
        check(WidgetMeters.shortLabel(WidgetMeters.limitPrimaryKey(spark), snapshot)
                        .equals("Spark 5h"),
                "limit primary short label still formats from display name");
        check(WidgetMeters.shortLabel(WidgetMeters.limitSecondaryKey(spark), snapshot)
                        .equals("Spark W"),
                "limit secondary short label still formats from display name");
        check(WidgetMeters.shortLabel(WidgetMeters.FIVE_HOUR, snapshot).equals("5h"),
                "five-hour short label");
        check(WidgetMeters.shortLabel(WidgetMeters.WEEKLY, snapshot).equals("Wk"),
                "weekly short label");

        check(WidgetMeters.VISUAL_RINGS.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_AUTO, false, 1, 2, 70, 110)),
                "auto short both uses rings");
        check(WidgetMeters.VISUAL_FOUR_DIALS.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_AUTO, false, 1, 4, 70, 250)),
                "auto wide short both uses four dials");
        check(WidgetMeters.VISUAL_BATTERY_LIST.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_AUTO, false, 2, 2, 156, 110)),
                "auto tall both uses battery list");
        check(WidgetMeters.VISUAL_DIALS.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_AUTO, true, 2, 2, 156, 110)),
                "auto tall single uses dials");
        check(WidgetMeters.VISUAL_FOUR_DIALS.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_DIALS, false, 2, 2, 156, 110)),
                "forced dials never returns battery list");
        check(WidgetMeters.VISUAL_BATTERY_LIST.equals(WidgetMeters.resolveHomeVisualStyle(
                        WidgetMeters.PREF_BARS, true, 1, 2, 70, 110)),
                "forced bars always returns battery list");
        check(WidgetMeters.slotCapacity(WidgetMeters.VISUAL_BATTERY_LIST, 70) == 2,
                "short bars capacity is 2");
        check(WidgetMeters.slotCapacity(WidgetMeters.VISUAL_BATTERY_LIST, 156) == 4,
                "tall bars capacity is 4");
        check(WidgetMeters.slotCapacity(WidgetMeters.VISUAL_FOUR_DIALS, 70) == 4,
                "four dials capacity is 4");
        check(WidgetMeters.slotCapacity(WidgetMeters.VISUAL_RINGS, 70) == 2,
                "rings capacity is 2");
        System.out.println("Widget meter catalog, capacity, and layout preference checks passed.");
    }

    private static void testOnboardingFlow() {
        check(OnboardingFlow.launchAction(false, false, false, false)
                        == OnboardingFlow.LAUNCH_ONBOARDING,
                "fresh install opens onboarding");
        check(OnboardingFlow.launchAction(false, true, false, false)
                        == OnboardingFlow.LAUNCH_MAIN_AND_COMPLETE,
                "signed-in upgrade is not interrupted");
        check(OnboardingFlow.launchAction(false, true, true, false)
                        == OnboardingFlow.LAUNCH_ONBOARDING,
                "OAuth return resumes onboarding");
        check(OnboardingFlow.launchAction(false, true, false, true)
                        == OnboardingFlow.LAUNCH_ONBOARDING,
                "a setup in progress resumes after a sign-in");
        check(OnboardingFlow.launchAction(true, false, false, false)
                        == OnboardingFlow.LAUNCH_MAIN,
                "completed onboarding stays completed after sign-out");
        check(OnboardingFlow.stepCount(2) == 5, "two providers: welcome, pick, two sign-ins, done");
        check(OnboardingFlow.completeStep(1) == 3, "one provider finishes on step four");
        check(OnboardingFlow.signInIndex(2, 2) == 0 && OnboardingFlow.signInIndex(3, 2) == 1,
                "sign-in steps map to the chosen providers in order");
        check(OnboardingFlow.signInIndex(4, 2) == -1
                        && OnboardingFlow.signInIndex(OnboardingFlow.STEP_PROVIDERS, 2) == -1,
                "other steps are not sign-in steps");
        check(OnboardingFlow.initialStep(3, 2) == 3,
                "a saved sign-in step survives the browser round trip");
        check(OnboardingFlow.initialStep(4, 0) == OnboardingFlow.STEP_WELCOME,
                "a saved step past the end resets safely");
        check(OnboardingFlow.normalizeStep(2, 0) == OnboardingFlow.STEP_PROVIDERS,
                "no providers chosen keeps the picker open");
        check(OnboardingFlow.nextStep(OnboardingFlow.STEP_PROVIDERS, 0)
                        == OnboardingFlow.STEP_PROVIDERS,
                "the picker needs at least one provider");
        check(OnboardingFlow.nextStep(OnboardingFlow.completeStep(3), 3)
                        == OnboardingFlow.completeStep(3),
                "next step clamps at completion");
        check(OnboardingFlow.previousStep(OnboardingFlow.STEP_WELCOME, 1)
                        == OnboardingFlow.STEP_WELCOME,
                "previous step clamps at welcome");
        check(OnboardingFlow.normalizeStep(99, 1) == OnboardingFlow.STEP_WELCOME,
                "invalid persisted page resets safely");
    }

    private static void testOAuthBrowserPage() {
        String success = OAuthBrowserPage.render(
                "Connected <securely> & ready.", true, "codexmeter://auth/complete");
        check(success.contains("You’re connected"), "browser success title");
        check(success.contains("Codex Meter</a>"), "browser app return action");
        check(success.contains("prefers-color-scheme:dark"), "browser One UI light and dark themes");
        check(success.contains("border-radius:28px"), "browser One UI rounded card");
        check(success.contains("Connected &lt;securely&gt; &amp; ready."),
                "browser message HTML escaping");
        check(success.contains("setTimeout"), "successful browser page automatically returns");

        String failure = OAuthBrowserPage.render(
                "Denied", false, "codexmeter://auth/complete");
        check(failure.contains("Let’s try that again"), "browser failure title");
        check(failure.contains("Back to Codex Meter"), "browser failure return action");
        check(!failure.contains("setTimeout"), "failure page waits for user");

        String escapedScript = OAuthBrowserPage.javascriptString("x'\\\n\u2028");
        check("x\\'\\\\\\n\\u2028".equals(escapedScript), "browser script escaping");
    }

    private static void testReleaseVersions() {
        check(ReleaseVersion.compare("v2.2.0", "2.1.9") > 0,
                "release tag version ordering");
        check(ReleaseVersion.compare("2.1", "2.1.0") == 0,
                "short release version normalization");
        check(ReleaseVersion.compare("3.0.0", "3.0.0-rc.2") > 0,
                "stable release follows prerelease");
        check(ReleaseVersion.compare("3.0.0-rc.10", "3.0.0-rc.2") > 0,
                "numeric prerelease ordering");
        check(ReleaseVersion.parse("release-2.0") == null,
                "invalid release tag rejected");
    }

    private static void testGitHubReleases() throws Exception {
        check("https://github.com/BenItBuhner/Codex-Meter".equals( // pragma: allowlist secret
                        GitHubReleaseSource.REPOSITORY_URL),
                "canonical release repository");
        check("https://api.github.com/repos/BenItBuhner/Codex-Meter/releases?per_page=30" // pragma: allowlist secret
                        .equals(GitHubReleaseSource.RELEASES_API_URL),
                "canonical release API endpoint");
        String json = "["
                + releaseJson("v2.2.0", false, false, true, true)
                + "," + releaseJson("v3.0.0-beta.1", false, true, true, true)
                + "," + releaseJson("v9.0.0", true, false, true, true)
                + "," + releaseJson("v2.3.0", false, false, true, false)
                + "]";
        java.util.List<GitHubRelease> releases = GitHubReleaseParser.parse(json);
        check(releases.size() == 2, "only complete published releases accepted");
        check("3.0.0-beta.1".equals(releases.get(0).version),
                "release history sorted semantically");
        GitHubRelease latest = GitHubReleaseParser.latestStable(releases);
        check(latest != null && "2.2.0".equals(latest.version),
                "automatic updates exclude prereleases");
        check(latest.isNewerThan("2.1.0"), "new release detected");
        check(GitHubReleaseParser.findVersion(releases, "v2.2") == latest,
                "release version lookup normalized");
        check(GitHubReleaseParser.parse("[]").isEmpty(), "empty release history accepted");
        check(!GitHubReleaseParser.isGitHubHttps("http://github.com/file.apk"),
                "non-HTTPS release URL rejected");
        check(!GitHubReleaseParser.isGitHubHttps("https://github.com.evil.example/file.apk"),
                "lookalike GitHub host rejected");
        String localFixture = "[" + releaseJson(
                "v2.2.0", false, false, true, true).replace(
                GitHubReleaseSource.REPOSITORY_URL,
                "http://10.0.2.2:8765") + "]";
        check(GitHubReleaseParser.parse(localFixture).isEmpty(),
                "local fixture rejected by production parser");
        check(GitHubReleaseParser.parse(localFixture, true).size() == 1,
                "local fixture accepted only in explicit debug mode");
    }

    private static void testUpdateChannel() throws Exception {
        check(UpdateChannel.STABLE.equals(UpdateChannel.normalize(null)),
                "update channel defaults to stable");
        check(UpdateChannel.ALPHA.equals(UpdateChannel.normalize(" Alpha ")),
                "update channel normalization");
        check(!UpdateChannel.isAlpha("nonsense"), "unknown channel treated as stable");
        String json = "["
                + releaseJson("v2.7.0-alpha.2", false, true, true, true)
                + "," + releaseJson("v2.6.10", false, false, true, true)
                + "," + releaseJson("v2.6.9", false, false, true, true)
                + "]";
        java.util.List<GitHubRelease> releases = GitHubReleaseParser.parse(json);
        check(UpdateChannel.selectUpdate(releases, "2.6.10", UpdateChannel.STABLE) == null,
                "stable channel ignores alpha builds");
        GitHubRelease alphaPick = UpdateChannel.selectUpdate(
                releases, "2.6.10", UpdateChannel.ALPHA);
        check(alphaPick != null && "2.7.0-alpha.2".equals(alphaPick.version),
                "alpha channel offers newest alpha");
        GitHubRelease alphaHop = UpdateChannel.selectUpdate(
                releases, "2.7.0-alpha.1", UpdateChannel.ALPHA);
        check(alphaHop != null && "2.7.0-alpha.2".equals(alphaHop.version),
                "alpha channel upgrades between alphas");
        check(UpdateChannel.selectUpdate(releases, "2.7.0-alpha.2", UpdateChannel.ALPHA) == null,
                "alpha channel idle on newest alpha");
        GitHubRelease revert = UpdateChannel.selectUpdate(
                releases, "2.7.0-alpha.2", UpdateChannel.STABLE);
        check(revert != null && "2.6.10".equals(revert.version),
                "stable channel offers in-place return from alpha");
        check(UpdateChannel.isReturnToStable(revert, "2.7.0-alpha.2"),
                "return-to-stable detected from alpha build");
        check(!UpdateChannel.isReturnToStable(revert, "2.6.9"),
                "stable-to-stable downgrade is not a return");
        check(UpdateChannel.selectUpdate(releases, "2.6.9", UpdateChannel.STABLE) != null,
                "stable channel still upgrades stable builds");
        String promoted = "[" + releaseJson("v2.7.0", false, false, true, true)
                + "," + releaseJson("v2.7.0-alpha.2", false, true, true, true) + "]";
        java.util.List<GitHubRelease> promotedReleases = GitHubReleaseParser.parse(promoted);
        GitHubRelease promotedPick = UpdateChannel.selectUpdate(
                promotedReleases, "2.7.0-alpha.2", UpdateChannel.ALPHA);
        check(promotedPick != null && "2.7.0".equals(promotedPick.version),
                "alpha channel follows stable promotions");
        // The first alpha cut after a shipped stable must be named for the NEXT stable
        // (2.8.0-alpha.1 after 2.7.0, never 2.7.0-alpha.1): SemVer orders X.Y.Z-alpha.N
        // below X.Y.Z, so an alpha suffixing the shipped stable is never offered.
        String firstAlpha = "[" + releaseJson("v2.8.0-alpha.1", false, true, true, true)
                + "," + releaseJson("v2.7.0", false, false, true, true) + "]";
        java.util.List<GitHubRelease> firstAlphaReleases = GitHubReleaseParser.parse(firstAlpha);
        GitHubRelease firstAlphaPick = UpdateChannel.selectUpdate(
                firstAlphaReleases, "2.7.0", UpdateChannel.ALPHA);
        check(firstAlphaPick != null && "2.8.0-alpha.1".equals(firstAlphaPick.version),
                "alpha channel offers first alpha after installed stable");
        String staleAlpha = "[" + releaseJson("v2.7.0-alpha.1", false, true, true, true)
                + "," + releaseJson("v2.7.0", false, false, true, true) + "]";
        check(UpdateChannel.selectUpdate(GitHubReleaseParser.parse(staleAlpha),
                "2.7.0", UpdateChannel.ALPHA) == null,
                "alpha suffixing the shipped stable is invisible to the updater");
    }

    private static String releaseJson(String tag, boolean draft, boolean prerelease,
            boolean apk, boolean checksum) {
        String normalized = tag.startsWith("v") ? tag.substring(1) : tag;
        StringBuilder assets = new StringBuilder();
        if (apk) {
            assets.append("{\"name\":\"CodexMeter-").append(normalized)
                    .append(".apk\",\"size\":123,\"browser_download_url\":")
                    .append("\"").append(GitHubReleaseSource.REPOSITORY_URL)
                    .append("/releases/download/")
                    .append(tag).append("/CodexMeter-").append(normalized).append(".apk\"}");
        }
        if (checksum) {
            if (assets.length() > 0) assets.append(',');
            assets.append("{\"name\":\"SHA256SUMS.txt\",\"size\":90,")
                    .append("\"browser_download_url\":")
                    .append("\"").append(GitHubReleaseSource.REPOSITORY_URL)
                    .append("/releases/download/")
                    .append(tag).append("/SHA256SUMS.txt\"}");
        }
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"Codex Meter " + normalized
                + "\",\"body\":\"Changes\",\"published_at\":\"2026-07-13T00:00:00Z\","
                + "\"html_url\":\"" + GitHubReleaseSource.REPOSITORY_URL + "/releases/tag/"
                + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease
                + ",\"assets\":[" + assets + "]}";
    }

    private static void testReleaseChecksums() {
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String checksums = digest + "  CodexMeter-2.2.0.apk\n"
                + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                + "  other.apk\n";
        check(digest.equals(ReleaseIntegrity.expectedSha256(
                        checksums, "CodexMeter-2.2.0.apk")),
                "matching APK checksum selected");
        check(ReleaseIntegrity.expectedSha256(checksums, "../other.apk").isEmpty(),
                "unsafe checksum filename rejected");
        check(ReleaseIntegrity.expectedSha256("not-a-checksum", "app.apk").isEmpty(),
                "malformed checksum rejected");
        check(ReleaseIntegrity.expectedSha256(checksums + digest
                        + "  CodexMeter-2.2.0.apk\n", "CodexMeter-2.2.0.apk").isEmpty(),
                "duplicate APK checksum rejected");
    }

    private static void testReleaseNotesMarkdown() {
        String html = ReleaseNotesMarkdown.toHtml("### Fixed\n\n"
                + "- Improved reset-duration contrast (#23).\n"
                + "- Restored in-app update discovery (#24).\n\n"
                + "### Development\n\n"
                + "- Centralized production GitHub release URLs (#24).\n\n"
                + "**Full Changelog**: https://github.com/example/Codex-Meter/compare/v2.2.0...v2.3.0 "
                + "<!-- pragma: allowlist secret -->");
        check(html.contains("<p><b>Fixed</b></p>"), "markdown heading rendered");
        check(html.contains("<ul>"), "markdown list opened");
        check(html.contains("<li>Improved reset-duration contrast (#23).</li>"),
                "markdown bullet rendered");
        check(html.contains("<li>Restored in-app update discovery (#24).</li>"),
                "second markdown bullet rendered");
        check(html.contains("<p><b>Development</b></p>"), "second markdown heading rendered");
        check(html.contains("<b>Full Changelog</b>"), "markdown bold rendered");
        check(html.contains("<a href=\"https://github.com/example/Codex-Meter/compare/v2.2.0...v2.3.0\">"),
                "markdown autolink rendered");
        check(!html.contains("pragma"), "html comments stripped from release notes");
        check(!html.contains("###"), "raw heading markers removed");
        check(!html.contains("- Improved"), "raw bullet markers removed");

        String linked = ReleaseNotesMarkdown.toHtml("See [the release](https://github.com/example/x).");
        check(linked.contains("<a href=\"https://github.com/example/x\">the release</a>"),
                "markdown link rendered");
        check(ReleaseNotesMarkdown.toHtml("").isEmpty(), "empty notes stay empty");
        check(ReleaseNotesMarkdown.toHtml("[bad](javascript:alert(1))")
                        .contains("javascript:alert(1)"),
                "unsafe markdown links stay plain text");
        check(!ReleaseNotesMarkdown.toHtml("[bad](javascript:alert(1))").contains("<a "),
                "unsafe markdown links are not anchored");

        String redactedNotes = "**Full Changelog**: https://github.com/[REDACTED]/Codex-Meter"
                + "/compare/v2.6.9...v2.6.10";
        String repaired = ReleaseNotesMarkdown.toHtml(redactedNotes);
        String expectedHref = GitHubReleaseSource.REPOSITORY_URL
                + "/compare/v2.6.9...v2.6.10";
        check(repaired.contains("<a href=\"" + expectedHref + "\">"),
                "redacted changelog owner rewritten to canonical repository");
        check(!repaired.contains("[REDACTED]"),
                "redacted changelog owner placeholder removed from rendered notes");
        check(ReleaseNotesMarkdown.repairRedactedRepositoryLinks(redactedNotes)
                        .startsWith("**Full Changelog**: " + GitHubReleaseSource.REPOSITORY_URL),
                "redacted repository link repair is reusable");
    }

    private static void testReleaseUpdatePolicy() {
        check(ReleaseUpdatePolicy.isIrreversible("2.2.0"),
                "pre-2.3.0 release is irreversible");
        check(ReleaseUpdatePolicy.isIrreversible("v2.1.0"),
                "tagged pre-2.3.0 release is irreversible");
        check(ReleaseUpdatePolicy.isIrreversible("2.2.9"),
                "latest pre-threshold release is irreversible");
        check(!ReleaseUpdatePolicy.isIrreversible("2.3.0"),
                "first in-app update release is reversible");
        check(!ReleaseUpdatePolicy.isIrreversible("2.3.1"),
                "post-threshold release is reversible");
        check(!ReleaseUpdatePolicy.isIrreversible("not-a-version"),
                "invalid versions are not flagged irreversible");
        check(ReleaseUpdatePolicy.irreversibleSummary().contains("Manual GitHub"),
                "irreversible summary mentions GitHub");
        check(ReleaseUpdatePolicy.irreversibleDetail()
                        .contains(ReleaseUpdatePolicy.FIRST_IN_APP_UPDATE_VERSION),
                "irreversible detail cites threshold version");
    }

    private static void testUpdateCheckFrequency() {
        check(UpdateCheckFrequency.normalize(1) == UpdateCheckFrequency.HOURLY,
                "hourly interval preserved");
        check(UpdateCheckFrequency.normalize(6) == UpdateCheckFrequency.EVERY_6_HOURS,
                "6-hour interval preserved");
        check(UpdateCheckFrequency.normalize(12) == UpdateCheckFrequency.EVERY_12_HOURS,
                "12-hour interval preserved");
        check(UpdateCheckFrequency.normalize(24) == UpdateCheckFrequency.DAILY,
                "daily interval preserved");
        check(UpdateCheckFrequency.normalize(168) == UpdateCheckFrequency.WEEKLY,
                "weekly interval preserved");
        check(UpdateCheckFrequency.normalize(0) == UpdateCheckFrequency.DAILY,
                "unknown interval defaults to daily");
        check(UpdateCheckFrequency.normalize(48) == UpdateCheckFrequency.DAILY,
                "unsupported interval defaults to daily");
        check(UpdateCheckFrequency.periodMillis(UpdateCheckFrequency.HOURLY)
                        == TimeUnit.HOURS.toMillis(1),
                "hourly period is one hour");
        check(UpdateCheckFrequency.periodMillis(UpdateCheckFrequency.WEEKLY)
                        == TimeUnit.DAYS.toMillis(7),
                "weekly period is seven days");
        check(UpdateCheckFrequency.flexMillis(UpdateCheckFrequency.DAILY)
                        == TimeUnit.HOURS.toMillis(6),
                "daily flex stays at six hours");
        check(UpdateCheckFrequency.flexMillis(UpdateCheckFrequency.HOURLY)
                        < UpdateCheckFrequency.periodMillis(UpdateCheckFrequency.HOURLY),
                "hourly flex is shorter than period");
        check(UpdateCheckFrequency.label(UpdateCheckFrequency.EVERY_6_HOURS)
                        .equals("Every 6 hours"),
                "6-hour label");
        check(UpdateCheckFrequency.summary(UpdateCheckFrequency.WEEKLY)
                        .toLowerCase().contains("weekly"),
                "weekly summary mentions weekly");
        for (int hours : UpdateCheckFrequency.SUPPORTED_HOURS) {
            check(UpdateCheckFrequency.flexMillis(hours)
                            <= UpdateCheckFrequency.periodMillis(hours),
                    "flex does not exceed period for " + hours + "h");
        }
    }

    private static String jwt(String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." +
                encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".x";
    }

    private static void testPlanPricing() {
        check(PlanPricing.forPlan(null) == null, "null plan has no pricing");
        check(PlanPricing.forPlan("free") == null, "free plan has no researched estimates");
        check(PlanPricing.forPlan("go") == null, "go plan has no researched estimates");
        check(PlanPricing.forPlan("enterprise") == null, "unknown plans have no estimates");

        PlanPricing plus = PlanPricing.forPlan("plus");
        check(plus != null && plus.monthlyPriceUsd == 20d, "plus subscription price");
        check(plus.monthlyValueUsd == 700d, "plus monthly value backsolves from 5x anchor");
        check(plus.weeklyValueUsd() == 175d, "plus weekly value is month over four weeks");

        PlanPricing pro5x = PlanPricing.forPlan("pro_5x");
        check(pro5x != null && pro5x.monthlyPriceUsd == 100d, "pro 5x price normalizes underscores");
        check(pro5x.monthlyValueUsd == 3500d, "pro 5x monthly value anchor");
        check(pro5x.weeklyValueUsd() == 875d, "pro 5x weekly value");
        check(PlanPricing.forPlan("prolite") != null
                && PlanPricing.forPlan("prolite").monthlyValueUsd == 3500d,
                "prolite aliases pro 5x");

        PlanPricing pro20x = PlanPricing.forPlan("pro");
        check(pro20x != null && pro20x.monthlyPriceUsd == 200d, "pro aliases pro 20x");
        check(pro20x.monthlyValueUsd == 14000d, "pro 20x monthly value anchor");
        check(pro20x.weeklyValueUsd() == 3500d, "pro 20x weekly value");
        check(Math.round(pro20x.valueMultiplier()) == 70L, "pro 20x multiplier of price");
        check(pro20x.fiveHourValueUsd() > 0d
                && pro20x.fiveHourValueUsd() < pro20x.weeklyValueUsd(),
                "5-hour burst allowance is a fraction of the weekly value");

        check(pro20x.estimatedValueUsd(UsageHistory.WEEKLY, 0) == 0d, "zero percent burns nothing");
        check(pro20x.estimatedValueUsd(UsageHistory.WEEKLY, 50) == 1750d,
                "half the weekly window burns half the weekly value");
        check(pro20x.estimatedValueUsd(UsageHistory.WEEKLY, 250)
                == pro20x.weeklyValueUsd(), "burned value clamps at the full allowance");

        check("$3,500".equals(PlanPricing.formatUsd(3500d)), "thousands formatting");
        check("$29".equals(PlanPricing.formatUsd(29.2d)), "two-digit dollars drop cents");
        check("$4.20".equals(PlanPricing.formatUsd(4.2d)), "small values keep cents");
        check("$0.00".equals(PlanPricing.formatUsd(-3d)), "negative values clamp to zero");
        System.out.println("Plan pricing demo: Plus $700/mo, Pro 5x $3,500/mo, Pro 20x $14,000/mo.");
    }

    private static void testUsageStats() {
        long start = 2_100_000_000_000L;
        long windowSeconds = TimeUnit.HOURS.toSeconds(5);
        long firstReset = start + TimeUnit.HOURS.toMillis(5);
        UsageHistory history = UsageHistory.empty(UsageHistory.FIVE_HOUR);
        // Completed window: 10% -> 40% -> 90% over four hours.
        history = history.append(new UsageWindow(10, windowSeconds, 0L, firstReset / 1000L),
                start + TimeUnit.HOURS.toMillis(1));
        history = history.append(new UsageWindow(40, windowSeconds, 0L, firstReset / 1000L),
                start + TimeUnit.HOURS.toMillis(3));
        history = history.append(new UsageWindow(90, windowSeconds, 0L, firstReset / 1000L),
                start + TimeUnit.HOURS.toMillis(5) - TimeUnit.MINUTES.toMillis(1));
        // Current window: slow burn.
        long secondReset = firstReset + TimeUnit.HOURS.toMillis(5);
        history = history.append(new UsageWindow(5, windowSeconds, 0L, secondReset / 1000L),
                firstReset + TimeUnit.HOURS.toMillis(1));
        history = history.append(new UsageWindow(10, windowSeconds, 0L, secondReset / 1000L),
                firstReset + TimeUnit.HOURS.toMillis(2));

        List<UsageStats.WindowStats> breakdown =
                UsageStats.windowBreakdown(history, 5);
        check(breakdown.size() == 2, "breakdown covers completed plus current window");
        UsageStats.WindowStats completed = breakdown.get(0);
        UsageStats.WindowStats current = breakdown.get(1);
        check(completed.complete && !current.complete, "completion flags follow window order");
        check(completed.finalPercent == 90, "completed window final percent");
        check(completed.sampleCount == 3, "completed window sample count");
        check(!completed.exhausted, "90% never reached the limit");
        check(current.finalPercent == 10, "current window final percent");
        double expectedAverage = 80d / (4d - 1d / 60d);
        check(Math.abs(completed.averageBurnPercentPerHour - expectedAverage) < 0.1d,
                "average burn spans first to last sample");
        check(completed.peakBurnPercentPerHour > completed.averageBurnPercentPerHour,
                "peak burn exceeds the average for an accelerating window");

        // Interpolation: halfway between the 10% and 40% samples reads 25%.
        List<UsageSample> completedSamples = history.recentWindows(5).get(0);
        double midpoint = UsageStats.usedPercentAt(completedSamples,
                start + TimeUnit.HOURS.toMillis(2));
        check(Math.abs(midpoint - 25d) < 0.01d, "scrub interpolation between samples");
        check(UsageStats.usedPercentAt(completedSamples, start) < 0d,
                "times before the first sample are unavailable");
        check(UsageStats.usedPercentAt(completedSamples,
                start + TimeUnit.HOURS.toMillis(1)) == 10d, "exact sample time reads its value");

        // Typical pace across completed windows at 60% elapsed (three hours in) is 40%.
        double typical = UsageStats.typicalUsedPercentAt(history, 0.6d);
        check(Math.abs(typical - 40d) < 0.01d, "typical pace at an elapsed fraction");
        check(UsageStats.typicalUsedPercentAt(UsageHistory.empty(UsageHistory.FIVE_HOUR), 0.5d)
                < 0d, "typical pace needs a completed window");
        check(Math.abs(UsageStats.averageFinalPercent(history) - 90d) < 0.01d,
                "average completed final percent");
        check(UsageStats.peakBurnPercentPerHour(history) > 0d, "peak burn is available");
        check(UsageStats.windowStats(java.util.Collections.emptyList(), false) == null,
                "empty windows produce no stats");
        System.out.println("Usage stats demo: completed window avg "
                + Math.round(completed.averageBurnPercentPerHour) + "%/h, typical@60% = "
                + Math.round(typical) + "%.");
    }

    private static void testHistorySections() {
        check(HistorySections.all().size() == 7, "seven customizable history highlights");
        check(!HistorySections.defaultVisible(HistorySections.GUIDE),
                "chart guide starts hidden for a minimal default page");
        check(HistorySections.defaultVisible(HistorySections.WINDOW_LIST),
                "previous-window list starts visible");
        check(HistorySections.defaultVisible(HistorySections.VALUE_ESTIMATES),
                "value estimates start visible");
        check(HistorySections.isVisible("", HistorySections.INSIGHT_PACE),
                "empty overrides keep defaults");
        check(!HistorySections.isVisible(null, HistorySections.GUIDE),
                "null overrides keep defaults");

        String overrides = HistorySections.setVisible("", HistorySections.GUIDE, true);
        check(HistorySections.isVisible(overrides, HistorySections.GUIDE),
                "guide can be switched on");
        overrides = HistorySections.setVisible(overrides,
                HistorySections.VALUE_ESTIMATES, false);
        check(!HistorySections.isVisible(overrides, HistorySections.VALUE_ESTIMATES),
                "value estimates can be switched off");
        check(HistorySections.isVisible(overrides, HistorySections.INSIGHT_PEAK),
                "untouched highlights keep their defaults");
        check("guide,value_estimates".equals(overrides),
                "overrides serialize as a stable csv");

        overrides = HistorySections.setVisible(overrides, HistorySections.GUIDE, false);
        overrides = HistorySections.setVisible(overrides,
                HistorySections.VALUE_ESTIMATES, true);
        check(overrides.isEmpty(), "restoring defaults clears every override");
        check("guide".equals(HistorySections.setVisible("guide, guide ,",
                HistorySections.WINDOW_LIST, true)),
                "duplicate and blank override entries collapse");

        // Full minimal mode: everything optional switched off leaves just the charts.
        String minimal = "";
        for (String key : HistorySections.all()) {
            minimal = HistorySections.setVisible(minimal, key, false);
            check(!HistorySections.label(key).isEmpty(), "every highlight has a label");
        }
        for (String key : HistorySections.all()) {
            check(!HistorySections.isVisible(minimal, key),
                    "minimal mode hides every optional highlight");
        }
        System.out.println("History highlights: minimal defaults + per-insight overrides verified.");
    }

    private static void testWidgetProviders() {
        java.util.List<UsageProvider> order = java.util.Arrays.asList(UsageProvider.CURSOR,
                UsageProvider.CODEX, UsageProvider.CLAUDE_CODE);
        check(WidgetProviders.parse("claude_code").equals(
                java.util.Arrays.asList(UsageProvider.CLAUDE_CODE)),
                "a single saved widget provider parses as one item");
        check(WidgetProviders.parse(" cursor,codex,cursor,bogus ").equals(
                java.util.Arrays.asList(UsageProvider.CURSOR, UsageProvider.CODEX)),
                "widget providers keep order and skip duplicates and unknown ids");
        check(WidgetProviders.parse(null).isEmpty() && WidgetProviders.parse(" ").isEmpty(),
                "no saved widget providers parses empty");
        check("codex,claude_code".equals(WidgetProviders.serialize(java.util.Arrays.asList(
                UsageProvider.CODEX, UsageProvider.CLAUDE_CODE))),
                "widget providers serialize as csv");
        java.util.List<UsageProvider> one = java.util.Arrays.asList(UsageProvider.CODEX);
        check(WidgetProviders.toggled(one, UsageProvider.CODEX, false, order, 3).equals(one),
                "the last widget provider can't be turned off");
        check(WidgetProviders.toggled(one, UsageProvider.CURSOR, true, order, 3).equals(
                java.util.Arrays.asList(UsageProvider.CURSOR, UsageProvider.CODEX)),
                "turning a widget provider on follows tab order");
        java.util.List<UsageProvider> two = java.util.Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CLAUDE_CODE);
        check(WidgetProviders.toggled(two, UsageProvider.CURSOR, true, order, 2).equals(two),
                "wide lock widgets refuse a third provider");
        check(WidgetProviders.fallback(order, java.util.Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CURSOR)) == UsageProvider.CODEX,
                "unsaved widgets stay on Codex while it's signed in");
        check(WidgetProviders.fallback(order, java.util.Arrays.asList(UsageProvider.CLAUDE_CODE,
                UsageProvider.CURSOR)) == UsageProvider.CURSOR,
                "unsaved widgets without Codex follow tab order");
        check(WidgetProviders.fallback(order, java.util.Collections.emptyList())
                == UsageProvider.CODEX, "unsaved widgets with nobody signed in show Codex");
        java.util.List<String> both = java.util.Arrays.asList(WidgetMeters.FIVE_HOUR,
                WidgetMeters.WEEKLY, WidgetMeters.NEXT_RESET);
        check(WidgetProviders.metersFor(both, true, true, 2).equals(java.util.Arrays.asList(
                WidgetMeters.FIVE_HOUR, WidgetMeters.WEEKLY)),
                "multi-provider rows take both usage meters when they fit");
        check(WidgetProviders.metersFor(both, false, true, 1).equals(java.util.Arrays.asList(
                WidgetMeters.WEEKLY)), "Cursor rows skip the missing five-hour window");
        check(WidgetProviders.metersFor(java.util.Arrays.asList(WidgetMeters.FIVE_HOUR), false,
                true, 1).equals(java.util.Arrays.asList(WidgetMeters.WEEKLY)),
                "a provider without the chosen meter falls back to its long window");
        check(WidgetProviders.metersFor(both, false, false, 1).equals(java.util.Arrays.asList(
                WidgetMeters.FIVE_HOUR)), "signed-out providers keep a placeholder meter");
        check(WidgetProviders.metersPerProvider(2, 4) == 2
                && WidgetProviders.metersPerProvider(3, 4) == 1
                && WidgetProviders.metersPerProvider(2, 2) == 1,
                "providers get two rows only when all of them fit twice");
        check("Codex + Claude Code".equals(WidgetProviders.summary(two))
                && "Cursor + 2 more".equals(WidgetProviders.summary(order)),
                "widget provider summary");
        check("CLAUDE".equals(WidgetProviders.shortTag(UsageProvider.CLAUDE_CODE)),
                "lock labels use short provider tags");
        for (UsageProvider provider : UsageProvider.values()) {
            String tag = WidgetProviders.shortTag(provider);
            check(!tag.isEmpty() && tag.length() <= 6, "every provider has a short lock tag");
        }
        check(WidgetProviders.lockDefault(order, java.util.Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CURSOR, UsageProvider.CLAUDE_CODE), 2).equals(
                java.util.Arrays.asList(UsageProvider.CURSOR, UsageProvider.CODEX)),
                "unsaved wide lock widgets show the first two signed-in tabs");
        check(WidgetProviders.lockDefault(order, java.util.Arrays.asList(UsageProvider.CLAUDE_CODE),
                2).equals(java.util.Arrays.asList(UsageProvider.CLAUDE_CODE)),
                "unsaved lock widgets show only signed-in providers");
        check(WidgetProviders.lockDefault(order, java.util.Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CURSOR), 1).equals(java.util.Arrays.asList(UsageProvider.CURSOR)),
                "unsaved tiny lock widgets show the first signed-in tab");
        check(WidgetProviders.lockDefault(order, java.util.Collections.emptyList(), 2).equals(
                java.util.Arrays.asList(UsageProvider.CURSOR)),
                "unsaved lock widgets with nobody signed in show the first tab");
        check(WidgetProviders.metersFor(java.util.Arrays.asList(WidgetMeters.FIVE_HOUR),
                false, true, 1).equals(java.util.Arrays.asList(WidgetMeters.WEEKLY)),
                "Cursor lock gauges show the long window instead of an empty 5-hour dial");
    }

    private static void testUsageProviders() {
        check(UsageProvider.DEFAULT == UsageProvider.CODEX, "Codex stays the default provider");
        check(UsageProvider.fromId(null) == UsageProvider.CODEX, "missing provider -> Codex");
        check(UsageProvider.fromId("unknown") == UsageProvider.CODEX, "unknown provider -> Codex");
        check(UsageProvider.fromId(" CLAUDE_CODE ") == UsageProvider.CLAUDE_CODE,
                "provider ids are case-insensitive");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (UsageProvider provider : UsageProvider.values()) {
            check(ids.add(provider.id), "provider ids are unique");
            check(UsageProvider.fromId(provider.id) == provider, "provider id round-trips");
        }
        check("Sign in with Claude".equals(UsageProvider.CLAUDE_CODE.signInLabel()),
                "Claude sign-in label");

        List<UsageProvider> all = Arrays.asList(UsageProvider.values());
        List<UsageProvider> codexOnly = Arrays.asList(UsageProvider.CODEX);
        check(UsageProvider.migrationDefault(null).equals(codexOnly),
                "an upgrade with no saved choice shows only Codex");
        check(UsageProvider.migrationDefault(Arrays.asList(UsageProvider.CURSOR,
                UsageProvider.CODEX)).equals(Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CURSOR)), "an upgrade keeps providers it already signed in to");
        check(UsageProvider.enabledFromIds(null, codexOnly).equals(codexOnly),
                "no saved choice -> the fallback");
        check(UsageProvider.enabledFromIds(Arrays.asList("", "bogus"), codexOnly)
                .equals(codexOnly), "unreadable choice -> the fallback");
        check(UsageProvider.enabledFromIds(Arrays.asList("cursor", " CODEX "), codexOnly)
                .equals(Arrays.asList(UsageProvider.CODEX, UsageProvider.CURSOR)),
                "enabled tabs keep enum order");
        List<UsageProvider> firstThree = Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR);
        List<UsageProvider> two = UsageProvider.withEnabled(firstThree, UsageProvider.CLAUDE_CODE,
                false);
        check(two.equals(Arrays.asList(UsageProvider.CODEX, UsageProvider.CURSOR)),
                "turning a provider off removes only its tab");
        List<UsageProvider> one = UsageProvider.withEnabled(
                UsageProvider.withEnabled(two, UsageProvider.CODEX, false),
                UsageProvider.CURSOR, false);
        check(one.equals(Arrays.asList(UsageProvider.CURSOR)),
                "the last provider can't be turned off");
        check(UsageProvider.withEnabled(one, UsageProvider.CLAUDE_CODE, true)
                .equals(Arrays.asList(UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR)),
                "turning a provider back on restores its place");
        check(UsageProvider.visibleSelection(UsageProvider.CLAUDE_CODE, two)
                == UsageProvider.CODEX, "a hidden selected tab falls back to the first one on");
        check(UsageProvider.visibleSelection(UsageProvider.CURSOR, two) == UsageProvider.CURSOR,
                "a visible selected tab stays selected");
        check(UsageProvider.orderFromIds(null).equals(all), "default tab order is enum order");
        List<UsageProvider> custom = UsageProvider.orderFromIds(
                Arrays.asList("cursor", "bogus", "CURSOR", " codex "));
        check(custom.subList(0, 3).equals(Arrays.asList(UsageProvider.CURSOR, UsageProvider.CODEX,
                UsageProvider.CLAUDE_CODE)) && custom.size() == all.size()
                && custom.get(3) == UsageProvider.COPILOT,
                "saved order first, duplicates and unknown ids skipped, the rest appended");
        check(UsageProvider.inOrder(two, custom)
                .equals(Arrays.asList(UsageProvider.CURSOR, UsageProvider.CODEX)),
                "enabled tabs follow the chosen order");
        System.out.println("Usage providers: Codex default, stable ids, and enabled tabs verified.");
    }

    private static void testClaudeUsageParsing() throws Exception {
        // Legacy-only shape: five_hour / seven_day objects. Per-model and codename keys are
        // ignored because Claude Code no longer shows them.
        String legacy = "{"
                + "\"five_hour\":{\"utilization\":6.0,\"resets_at\":\"2026-10-19T18:59:59.943648+00:00\"},"
                + "\"seven_day\":{\"utilization\":35.4,\"resets_at\":\"2026-10-23T03:00:00Z\"},"
                + "\"seven_day_oauth_apps\":null,"
                + "\"seven_day_opus\":{\"utilization\":0.0,\"resets_at\":null},"
                + "\"seven_day_sonnet\":{\"utilization\":100,\"resets_at\":\"2026-10-23T03:00:00+00:00\"},"
                + "\"seven_day_omelette\":{\"utilization\":3.0,\"resets_at\":null},"
                + "\"iguana_necktie\":null,"
                + "\"extra_usage\":{\"is_enabled\":true,\"monthly_limit\":5000,"
                + "\"used_credits\":1200,\"utilization\":24.0}"
                + "}";
        UsageSnapshot snapshot = ClaudeUsageParser.parse(legacy, "Max 20x", 1_000L);
        check(snapshot.fiveHour != null && snapshot.fiveHour.usedPercent == 6,
                "Claude five_hour utilization is percent used");
        check(snapshot.fiveHour.windowSeconds == 18_000L, "Claude five_hour is a 5-hour window");
        check(snapshot.fiveHour.resetAtEpochSeconds
                        == java.time.OffsetDateTime.parse("2026-10-19T18:59:59Z").toEpochSecond(),
                "Claude resets_at with fractional seconds parses");
        check(snapshot.weekly != null && snapshot.weekly.usedPercent == 35
                        && snapshot.weekly.windowSeconds == 604_800L,
                "Claude seven_day maps to weekly");
        check(snapshot.weekly.remainingPercent() == 65, "Claude weekly remaining");
        check(snapshot.monthly == null, "Claude has no monthly window");
        check("Max 20x".equals(snapshot.planType), "Claude plan label kept");
        check(snapshot.additionalLimits.size() == 1,
                "legacy Opus, Sonnet, and codename keys are not shown");
        UsageLimit extra = snapshot.additionalLimits.get(0);
        check("Extra usage".equals(extra.displayName()) && extra.primary.usedPercent == 24,
                "Claude extra usage maps to a monthly cap");
        check(!snapshot.limitReached && snapshot.allowed,
                "ignored legacy keys never mark the snapshot limited");

        // Current shape: the server-built limits[] list drives the cards.
        String current = "{"
                + "\"five_hour\":{\"utilization\":40.0,\"resets_at\":\"2026-10-19T14:59:59+00:00\"},"
                + "\"seven_day\":{\"utilization\":19.0,\"resets_at\":\"2026-10-23T03:00:00+00:00\"},"
                + "\"seven_day_sonnet\":{\"utilization\":2.0,\"resets_at\":null},"
                + "\"limits\":["
                + "{\"kind\":\"session\",\"group\":\"session\",\"percent\":41,\"severity\":\"normal\","
                + "\"resets_at\":\"2026-10-19T14:59:59.729061+00:00\",\"scope\":null,\"is_active\":true},"
                + "{\"kind\":\"weekly_all\",\"group\":\"weekly\",\"percent\":20,\"severity\":\"normal\","
                + "\"resets_at\":\"2026-10-23T03:00:00+00:00\",\"scope\":null,\"is_active\":false},"
                + "{\"kind\":\"weekly_scoped\",\"group\":\"weekly\",\"percent\":100,"
                + "\"severity\":\"critical\",\"resets_at\":\"2026-10-23T03:00:00+00:00\","
                + "\"scope\":{\"model\":{\"id\":null,\"display_name\":\"Fable\"},\"surface\":null},"
                + "\"is_active\":false},"
                + "{\"kind\":\"weekly_scoped\",\"group\":\"weekly\",\"percent\":5,"
                + "\"scope\":{\"model\":{\"id\":null,\"display_name\":\"All models\"}}},"
                + "{\"kind\":\"session\",\"group\":\"session\",\"percent\":null}"
                + "],"
                + "\"extra_usage\":{\"is_enabled\":null,\"utilization\":null}"
                + "}";
        UsageSnapshot fromLimits = ClaudeUsageParser.parse(current, "Max 5x", 2_000L);
        check(fromLimits.fiveHour.usedPercent == 41 && fromLimits.fiveHour.windowSeconds == 18_000L,
                "limits[] session row is the 5-hour meter");
        check(fromLimits.weekly.usedPercent == 20 && fromLimits.weekly.windowSeconds == 604_800L,
                "limits[] weekly_all row is the weekly meter");
        check(fromLimits.additionalLimits.size() == 1,
                "only server-listed meters are shown; legacy Sonnet and All models are not");
        UsageLimit fable = fromLimits.additionalLimits.get(0);
        check("Fable only".equals(fable.displayName()) && fable.limitReached
                        && fable.primary.windowSeconds == 604_800L,
                "model-scoped weekly row becomes a Fable-only card");
        check(DashboardSections.limitKey(fable).equals("limit:claude_weekly_scoped_fable"),
                "Claude limits get stable dashboard keys");
        check(fromLimits.limitReached && !fromLimits.allowed,
                "an exhausted scoped meter marks the snapshot limited");

        String plain = "{\"limits\":["
                + "{\"kind\":\"session\",\"group\":\"session\",\"percent\":7,\"resets_at\":null},"
                + "{\"kind\":\"weekly_all\",\"group\":\"weekly\",\"percent\":3,\"resets_at\":null}]}";
        UsageSnapshot unified = ClaudeUsageParser.parse(plain, "Pro", 3L);
        check(unified.fiveHour.usedPercent == 7 && unified.weekly.usedPercent == 3
                        && unified.additionalLimits.isEmpty(),
                "a unified plan shows only the 5-hour and weekly meters");

        UsageSnapshot legacyFable = ClaudeUsageParser.parse(
                "{\"seven_day_fable\":{\"utilization\":12,\"resets_at\":null}}", "", 4L);
        check(legacyFable.additionalLimits.size() == 1
                        && "Fable only".equals(legacyFable.additionalLimits.get(0).displayName()),
                "older top-level Fable keys still show a Fable-only card");

        UsageSnapshot roundTrip = UsageSnapshot.fromJson(fromLimits.toJson());
        check(roundTrip.weekly.usedPercent == 20 && roundTrip.additionalLimits.size() == 1,
                "Claude snapshot survives the cache round-trip");

        UsageSnapshot minimal = ClaudeUsageParser.parse(
                "{\"five_hour\":null,\"seven_day\":{\"utilization\":12.6,\"resets_at\":null},"
                        + "\"extra_usage\":{\"is_enabled\":false,\"utilization\":null}}",
                "", 1L);
        check(minimal.fiveHour == null && minimal.weekly.usedPercent == 13
                        && minimal.additionalLimits.isEmpty() && !minimal.limitReached,
                "Claude null windows and disabled extra usage are skipped");
        check(ClaudeUsageParser.parseResetSeconds("not a date") == 0L,
                "bad Claude reset timestamps are ignored");

        check("Max 20x".equals(ClaudeUsageParser.planLabel(
                "{\"organization\":{\"organization_type\":\"claude_max\","
                        + "\"rate_limit_tier\":\"default_claude_max_20x\"}}")),
                "Claude Max 20x from rate-limit tier");
        check("Max 5x".equals(ClaudeUsageParser.planLabel(
                "{\"organization\":{\"rate_limit_tier\":\"default_claude_max_5x\"}}")),
                "Claude Max 5x from rate-limit tier");
        check("Pro".equals(ClaudeUsageParser.planLabel(
                "{\"account\":{\"has_claude_pro\":true},\"organization\":{}}")),
                "Claude Pro from account flags");
        check("Team".equals(ClaudeUsageParser.planLabel(
                "{\"organization\":{\"organization_type\":\"claude_team\"}}")),
                "Claude Team from organization type");
        check("".equals(ClaudeUsageParser.planLabel("not json")), "bad profile -> no badge");
        check("me@example.com".equals(ClaudeUsageParser.email(
                "{\"account\":{\"email_address\":\"me@example.com\"}}")),
                "Claude profile email");
        System.out.println("Claude Code usage: limits list, legacy windows, extra usage, plans verified.");
    }

    private static void testClaudeOAuthRequests() throws Exception {
        String redirect = ClaudeCodeApi.redirectUri(54545);
        check("http://localhost:54545/callback".equals(redirect), "Claude loopback redirect");
        String url = ClaudeCodeApi.authorizeUrl(redirect, "challenge-value", "state-value");
        check(url.startsWith("https://claude.ai/oauth/authorize?code=true&client_id="
                + ClaudeCodeApi.CLIENT_ID + "&response_type=code&"), "Claude authorize URL");
        check(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A54545%2Fcallback"),
                "Claude authorize redirect encoded");
        check(url.contains("scope=user%3Aprofile+user%3Ainference"), "Claude read-only scopes");
        check(url.contains("code_challenge=challenge-value&code_challenge_method=S256"),
                "Claude PKCE S256");
        check(url.endsWith("&state=state-value"), "Claude state param");
        java.util.Map<String, String> exchange = ClaudeCodeApi.codeExchangeBody(
                "auth-code#state-value", redirect, "verifier", "state-value");
        check("auth-code".equals(exchange.get("code")), "Claude strips #state from the code");
        check("authorization_code".equals(exchange.get("grant_type"))
                        && redirect.equals(exchange.get("redirect_uri"))
                        && "verifier".equals(exchange.get("code_verifier"))
                        && "state-value".equals(exchange.get("state")),
                "Claude code exchange body");
        java.util.Map<String, String> refresh = ClaudeCodeApi.refreshBody("refresh-token");
        check("refresh_token".equals(refresh.get("grant_type"))
                        && ClaudeCodeApi.CLIENT_ID.equals(refresh.get("client_id")),
                "Claude refresh body");
        long minute = 60_000L;
        long interval = ClaudeCodeApi.BACKGROUND_INTERVAL_MILLIS;
        check(ClaudeCodeApi.isRefreshDue(100 * minute, 0L, 0L, interval),
                "Claude refresh due with no cached data");
        check(!ClaudeCodeApi.isRefreshDue(100 * minute, 90 * minute, 0L, interval),
                "Claude background refresh waits 30 minutes");
        check(ClaudeCodeApi.isRefreshDue(100 * minute, 70 * minute, 0L, interval),
                "Claude background refresh runs after 30 minutes");
        check(!ClaudeCodeApi.isRefreshDue(100 * minute, 0L, 70 * minute, interval),
                "Claude backs off for an hour after a 429");
        check(ClaudeCodeApi.isRefreshDue(200 * minute, 0L, 70 * minute, interval),
                "Claude retries once the 429 back-off ends");
        check(ClaudeCodeApi.isRefreshDue(100 * minute, 94 * minute, 0L, 5 * minute),
                "widget refresh taps use the 5-minute spacing");
        check(ClaudeCodeApi.isRefreshDue(100 * minute, 200 * minute, 0L, interval),
                "a cache from the future (clock change) does not block refresh");
        System.out.println("Claude Code OAuth: authorize URL, code exchange, refresh verified.");
    }

    private static void testCursorUsageParsing() throws Exception {
        check(UsageProvider.fromId("cursor") == UsageProvider.CURSOR, "Cursor provider id");
        check("Sign in with Cursor".equals(UsageProvider.CURSOR.signInLabel()),
                "Cursor sign-in label");
        String body = "{\"billingCycleStart\":\"1791676800000\","
                + "\"billingCycleEnd\":\"2026-11-11T00:00:00Z\","
                + "\"planUsage\":{\"totalSpend\":500,\"includedSpend\":400,\"bonusSpend\":100,"
                + "\"remaining\":1500,\"limit\":2000,\"totalPercentUsed\":25,"
                + "\"autoPercentUsed\":10,\"apiPercentUsed\":15},"
                + "\"spendLimitUsage\":{\"totalSpend\":300,\"individualLimit\":1000,"
                + "\"individualUsed\":300,\"individualRemaining\":700,"
                + "\"pooledLimit\":\"20000\",\"pooledUsed\":5000,\"limitType\":\"team\"},"
                + "\"enabled\":true,\"serverTimeOffsetMs\":123}";
        UsageSnapshot snapshot = CursorUsageParser.parse(body, "Pro", 1_000L);
        long end = java.time.OffsetDateTime.parse("2026-11-11T00:00:00Z").toEpochSecond();
        check(snapshot.monthly != null && snapshot.monthly.usedPercent == 25,
                "Cursor totalPercentUsed is the included-usage window");
        check(snapshot.monthly.resetAtEpochSeconds == end,
                "Cursor ISO billingCycleEnd is the reset");
        check(snapshot.monthly.windowSeconds == end - 1_791_676_800L,
                "Cursor window spans the billing cycle");
        check(snapshot.fiveHour == null && snapshot.weekly == null,
                "Cursor has no 5-hour or weekly window");
        check(snapshot.longWindowIsMonthly(), "Cursor widgets fall back to the monthly window");
        check(snapshot.additionalLimits.size() == 3,
                "Cursor legacy plan: two model pools and on-demand");
        check("Cursor models".equals(snapshot.additionalLimits.get(0).name)
                        && snapshot.additionalLimits.get(0).primary.usedPercent == 10,
                "Cursor models pool percent");
        check("Other models".equals(snapshot.additionalLimits.get(1).name)
                        && snapshot.additionalLimits.get(1).primary.usedPercent == 15,
                "Other models pool percent");
        UsageLimit onDemand = snapshot.additionalLimits.get(2);
        check("On-demand ($3 of $10)".equals(onDemand.name)
                        && onDemand.primary.usedPercent == 30 && !onDemand.limitReached,
                "Cursor individual on-demand cap wins over the team pool, in dollars");
        check(!snapshot.limitReached && snapshot.allowed, "Cursor snapshot not limited");
        check(UsageSnapshot.fromJson(snapshot.toJson()).monthly.usedPercent == 25,
                "Cursor snapshot survives the cache round-trip");

        UsageSnapshot computed = CursorUsageParser.parse(
                "{\"billingCycleStart\":1791676800000,\"billingCycleEnd\":\"1794355200000\","
                        + "\"planUsage\":{\"includedSpend\":2000,\"limit\":2000},"
                        + "\"spendLimitUsage\":{\"pooledLimit\":\"5000\",\"pooledUsed\":\"5000\"}}",
                "", 1L);
        check(computed.monthly.usedPercent == 100 && computed.monthly.resetAtEpochSeconds
                        == 1_794_355_200L,
                "Cursor percent from includedSpend/limit with epoch-ms strings");
        check(computed.additionalLimits.size() == 1
                        && "Team on-demand ($50 of $50)".equals(computed.additionalLimits.get(0).name)
                        && computed.additionalLimits.get(0).limitReached,
                "Cursor team pool on-demand fallback");
        check(computed.limitReached, "Cursor plan and on-demand both spent -> limited");
        UsageSnapshot light = CursorUsageParser.parse(
                "{\"planUsage\":{\"totalPercentUsed\":0.36}}", "", 1L);
        check(light.monthly.usedPercent == 1
                        && light.monthly.windowSeconds == CursorUsageParser.DEFAULT_CYCLE_SECONDS,
                "Cursor fractional usage shows at least 1% and defaults to a 30-day cycle");
        check(light.additionalLimits.isEmpty(), "Cursor plans without pools show no pool cards");
        UsageSnapshot oneSided = CursorUsageParser.parse(
                "{\"planUsage\":{\"limit\":2000,\"autoPercentUsed\":40}}", "", 1L);
        check(oneSided.monthly.usedPercent == 0 && oneSided.additionalLimits.size() == 2
                        && oneSided.additionalLimits.get(1).primary.usedPercent == 0,
                "Cursor omitted zero pool and zero spend still show as 0%");
        // Current plans: one included pool that refreshes weekly, no pool split.
        UsageSnapshot weeklyPlan = CursorUsageParser.parse(
                "{\"billingCycleStart\":\"1791676800000\",\"billingCycleEnd\":\"1792281600000\","
                        + "\"planUsage\":{\"totalPercentUsed\":100,\"autoPercentUsed\":100},"
                        + "\"spendLimitUsage\":{\"individualLimit\":2000,\"individualUsed\":500}}",
                "Premium", 1L);
        check(weeklyPlan.weekly != null && weeklyPlan.monthly == null
                        && weeklyPlan.weekly.usedPercent == 100
                        && weeklyPlan.weekly.windowSeconds == 7L * 24L * 3600L,
                "Cursor weekly cycle is the weekly window");
        check(weeklyPlan.additionalLimits.size() == 1
                        && "Additional usage ($5 of $20)".equals(
                                weeklyPlan.additionalLimits.get(0).name),
                "Cursor current plan shows no model pools and calls extra spend additional usage");
        UsageSnapshot empty = CursorUsageParser.parse("{\"enabled\":false}", "", 1L);
        check(empty.monthly == null && empty.additionalLimits.isEmpty(),
                "Cursor response without usage yields no windows");
        check("$12.50".equals(CursorUsageParser.dollars(1250)), "Cursor cents to dollars");

        check("Pro+".equals(CursorUsageParser.planLabel("{\"membershipType\":\"pro_plus\"}")),
                "Cursor Pro+ plan");
        check("Ultra".equals(CursorUsageParser.planLabel("{\"membershipType\":\"ultra\"}")),
                "Cursor Ultra plan");
        check("Plus".equals(CursorUsageParser.planLabel("{\"membershipType\":\"plus\"}"))
                        && "Premium".equals(CursorUsageParser.planLabel(
                                "{\"membershipType\":\"premium\"}"))
                        && "Super".equals(CursorUsageParser.planLabel(
                                "{\"membershipType\":\"super\"}")),
                "X Subscription Cursor plan names");
        check("Hobby".equals(CursorUsageParser.planLabel("{\"membershipType\":\"free\"}")),
                "Cursor free plan is Hobby");
        check("Teams".equals(CursorUsageParser.planLabel("{\"membership_type\":\"team\"}")),
                "Cursor team plan");
        check("".equals(CursorUsageParser.planLabel("nope")), "bad Cursor profile -> no badge");
        check("me@example.com".equals(CursorUsageParser.email("{\"email\":\"me@example.com\"}")),
                "Cursor profile email");
        System.out.println("Cursor usage: billing cycle, model pools, on-demand, plans verified.");
    }

    private static void testCursorAuth() throws Exception {
        CursorApi.LoginRequest login = CursorApi.newLoginRequest();
        check(login.verifier.length() == 43, "Cursor verifier is 32 bytes base64url");
        check(login.challenge.equals(CursorApi.challengeFor(login.verifier)),
                "Cursor challenge is SHA-256 of the verifier");
        // RFC 7636 appendix B test vector.
        check("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(
                        CursorApi.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")),
                "Cursor S256 challenge matches RFC 7636");
        check(login.loginUrl().equals("https://cursor.com/loginDeepControl?challenge="
                        + login.challenge + "&uuid=" + login.uuid + "&mode=login"),
                "Cursor login URL");
        check(login.pollUrl().equals("https://api2.cursor.sh/auth/poll?uuid=" + login.uuid
                + "&verifier=" + login.verifier), "Cursor poll URL");
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"auth0|user_01ABC\",\"exp\":1800000000,\"email\":\"a@b.co\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String jwt = "eyJhbGciOiJIUzI1NiJ9." + payload + ".sig";
        check(CursorApi.expiresAtMillis(jwt) == 1_800_000_000_000L, "Cursor JWT exp");
        check("a@b.co".equals(CursorApi.email(jwt)), "Cursor JWT email");
        check(CursorApi.expiresAtMillis("not-a-jwt") == 0L, "bad Cursor JWT -> no expiry");
        CursorApi.Tokens polled = CursorApi.parseTokens(
                "{\"authId\":\"auth0|user_01ABC\",\"accessToken\":\"a\",\"refreshToken\":\"r\"}");
        check("a".equals(polled.accessToken) && "r".equals(polled.refreshToken)
                && "auth0|user_01ABC".equals(polled.authId), "Cursor poll tokens");
        CursorApi.Tokens refreshed = CursorApi.parseTokens("{\"access_token\":\"new\"}");
        check("new".equals(refreshed.accessToken) && "new".equals(refreshed.refreshToken),
                "Cursor refresh reuses the access token as refresh token, like the editor");
        check(CursorApi.parseTokens("{\"shouldLogout\":true}").shouldLogout,
                "Cursor shouldLogout is read");
        check(CursorApi.refreshBody("r").contains("\"client_id\":\"" + CursorApi.CLIENT_ID + "\""),
                "Cursor refresh body carries the client id");
        check(CursorApi.isUnauthenticated(401, ""), "Cursor 401 is unauthenticated");
        check(CursorApi.isUnauthenticated(200, "{\"error\":\"not_authenticated\"}"),
                "Cursor 200 with auth error body is unauthenticated");
        check(!CursorApi.isUnauthenticated(200, "{\"planUsage\":{}}"), "Cursor usage is not an auth error");
        check(CursorApi.isBotCheckpoint(403, "<html>Vercel Security Checkpoint</html>")
                        && !CursorApi.isUnauthenticated(403, "<html>Vercel Security Checkpoint</html>"),
                "Cursor bot checkpoint is not a sign-in failure");
        long minute = 60_000L;
        check(!CursorApi.isRefreshDue(100 * minute, 90 * minute,
                CursorApi.BACKGROUND_INTERVAL_MILLIS), "Cursor background refresh waits");
        check(CursorApi.isRefreshDue(100 * minute, 80 * minute,
                CursorApi.BACKGROUND_INTERVAL_MILLIS), "Cursor background refresh runs");
        System.out.println("Cursor auth: PKCE poll login, JWT expiry, refresh verified.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
