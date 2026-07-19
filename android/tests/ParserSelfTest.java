package dev.bennett.codexmeter;

import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSurfaceMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public final class ParserSelfTest {
    public static void main(String[] args) throws Exception {
        testStandardUsage();
        testWindowIdentification();
        testAdditionalLimits();
        testPrimaryLimitWinsOverAdditional();
        testMalformedWindowIgnored();
        testZeroDurationWindowIgnored();
        testNextResetSelection();
        testCelebrationDetection();
        testResetCreditExpiryReminders();
        testResetCreditExpiryOrdering();
        testFullWindowHidesResetCountdown();
        testUsagePace();
        testNowBarAutoStart();
        testNowBarDisplayModes();
        testWearSurfaceModes();
        testWearSettingsState();
        testWearGlanceFormat();
        testNowBarPercentModes();
        testJwtMerge();
        testPkce();
        testWidgetOptions();
        testOnboardingFlow();
        testOAuthBrowserPage();
        testReleaseVersions();
        testGitHubReleases();
        testReleaseChecksums();
        testReleaseNotesMarkdown();
        testReleaseUpdatePolicy();
        testUpdateCheckFrequency();
        testSettingsTransfer();
        System.out.println("All parser, updater, OAuth, onboarding, and widget-option self-tests passed.");
    }

    private static void testFullWindowHidesResetCountdown() {
        UsageWindow full = new UsageWindow(0, 18000L, 600L, 2000000000L);
        UsageWindow almostFull = new UsageWindow(1, 18000L, 600L, 2000000000L);
        UsageWindow used = new UsageWindow(37, 18000L, 600L, 2000000000L);
        check(full.remainingPercent() == 100, "full window remaining");
        check(!full.showsResetCountdown(), "100% remaining hides drifting reset countdown");
        check(almostFull.remainingPercent() == 99, "1% used is 99% remaining");
        check(almostFull.showsResetCountdown(), "99% remaining still shows reset countdown");
        check(used.showsResetCountdown(), "partial usage shows reset countdown");
        System.out.println("Reset-countdown demo: hide at 100% remaining, show again at 99% or less.");
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
        check(UsagePace.BALANCED.equals(UsagePace.normalizeSensitivity("invalid")),
                "invalid sensitivity falls back to balanced");
        check(UsagePace.OFF.equals(UsagePace.normalizeSensitivity(UsagePace.OFF)),
                "off sensitivity is preserved");
        check(!UsagePace.assess(fastWeekly, now, fast.resetAtMillis, UsagePace.BALANCED).available,
                "expired windows do not produce stale warnings");
        System.out.println("Usage-pace demo: 15% of a week in one hour projects about 6h 40m.");
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
        System.out.println("Wear settings JSON preserves normalized sync preferences.");
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
        check(snapshot.fiveHour.remainingPercent() == 0, "upper clamp");
        check(snapshot.weekly.remainingPercent() == 100, "lower clamp");
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

    private static UsageSnapshot snapshot(int fiveHourUsed, int weeklyUsed,
            long resetAfterSeconds, long fetchedAtMillis) {
        return new UsageSnapshot("pro", true, false,
                new UsageWindow(fiveHourUsed, 18_000L, resetAfterSeconds, 0L),
                new UsageWindow(weeklyUsed, 604_800L, resetAfterSeconds, 0L),
                fetchedAtMillis);
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
        WidgetOptions widget = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_COMFORTABLE, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_MAX, WidgetOptions.THEME_DARK, WidgetOptions.ACCENT_BLUE,
                94, WidgetOptions.RESET_HIDDEN, WidgetOptions.DISPLAY_REMAINING,
                WidgetOptions.METRIC_BOTH, false, true, false, true, true, false)
                .withPercentSymbol(false);
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
        check(parsed.appSettings.getBoolean("material_you"), "material you preference restored");
        check(UsagePace.RELAXED.equals(
                        parsed.appSettings.getString("usage_pace_sensitivity")),
                "usage pace sensitivity preserved");
        check(!restored.showPercentSymbol, "percent symbol flag restored");
        check(parsed.notifications.getInt("threshold") == 50, "notification threshold restored");
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
    }

    private static void testOnboardingFlow() {
        check(OnboardingFlow.launchAction(false, false, false)
                        == OnboardingFlow.LAUNCH_ONBOARDING,
                "fresh install opens onboarding");
        check(OnboardingFlow.launchAction(false, true, false)
                        == OnboardingFlow.LAUNCH_MAIN_AND_COMPLETE,
                "signed-in upgrade is not interrupted");
        check(OnboardingFlow.launchAction(false, true, true)
                        == OnboardingFlow.LAUNCH_ONBOARDING,
                "OAuth return reaches onboarding completion");
        check(OnboardingFlow.launchAction(true, false, false)
                        == OnboardingFlow.LAUNCH_MAIN,
                "completed onboarding stays completed after sign-out");
        check(OnboardingFlow.initialStep(OnboardingFlow.STEP_USAGE, false, false)
                        == OnboardingFlow.STEP_USAGE,
                "incomplete onboarding resumes saved page");
        check(OnboardingFlow.initialStep(OnboardingFlow.STEP_WELCOME, true, true)
                        == OnboardingFlow.STEP_COMPLETE,
                "successful OAuth opens completion page");
        check(OnboardingFlow.initialStep(OnboardingFlow.STEP_WELCOME, false, true)
                        == OnboardingFlow.STEP_ACCOUNT,
                "failed OAuth returns to account page");
        check(OnboardingFlow.nextStep(OnboardingFlow.STEP_COMPLETE)
                        == OnboardingFlow.STEP_COMPLETE,
                "next step clamps at completion");
        check(OnboardingFlow.previousStep(OnboardingFlow.STEP_WELCOME)
                        == OnboardingFlow.STEP_WELCOME,
                "previous step clamps at welcome");
        check(OnboardingFlow.normalizeStep(99) == OnboardingFlow.STEP_WELCOME,
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

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
