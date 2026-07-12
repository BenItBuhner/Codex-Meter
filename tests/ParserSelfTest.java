package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class ParserSelfTest {
    public static void main(String[] args) throws Exception {
        testStandardUsage();
        testWindowIdentification();
        testAdditionalLimits();
        testPrimaryLimitWinsOverAdditional();
        testMalformedWindowIgnored();
        testZeroDurationWindowIgnored();
        testJwtMerge();
        testPkce();
        testWidgetOptions();
        testUnexpectedResetDetection();
        System.out.println("All parser, PKCE, widget-option, and reset-detection self-tests passed.");
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


    private static void testWidgetOptions() {
        WidgetOptions migrated = new WidgetOptions(WidgetOptions.LAYOUT_DETAILED,
                WidgetOptions.THEME_DARK, WidgetOptions.ACCENT_BLUE, 72,
                WidgetOptions.RESET_BOTH, WidgetOptions.DISPLAY_USED);
        check(WidgetOptions.STYLE_BARS.equals(migrated.layout), "legacy detailed migration");
        check(WidgetOptions.DENSITY_AUTO.equals(migrated.density), "default density");
        WidgetOptions safe = new WidgetOptions("invalid", "invalid", "invalid", "invalid", 13,
                "invalid", "invalid", true, false, true);
        check(WidgetOptions.STYLE_AUTO.equals(safe.layout), "invalid style fallback");
        check(safe.opacity == 88, "invalid opacity fallback");
        check(WidgetOptions.ACCENT_MINT.equals(safe.accent), "invalid accent fallback");
        check(WidgetOptions.SURFACE_MATERIAL.equals(safe.surfaceStyle), "legacy surface fallback");
        check(WidgetOptions.GRAPHIC_AUTO.equals(safe.graphicScale), "legacy graphic fallback");
        check(!safe.showUpdated && safe.showRefresh, "boolean options");

        WidgetOptions transparent = new WidgetOptions(WidgetOptions.STYLE_RINGS,
                WidgetOptions.DENSITY_COMFORTABLE, WidgetOptions.SURFACE_ONE_UI,
                WidgetOptions.GRAPHIC_MAX, WidgetOptions.THEME_LIGHT, WidgetOptions.ACCENT_VIOLET,
                0, WidgetOptions.RESET_RELATIVE, WidgetOptions.DISPLAY_REMAINING,
                false, true, false);
        check(transparent.opacity == 0, "transparent background accepted");
        check(WidgetOptions.SURFACE_ONE_UI.equals(transparent.surfaceStyle), "One UI widget style");
        check(WidgetOptions.GRAPHIC_MAX.equals(transparent.graphicScale), "maximum graphic scale");
        check(!WidgetOptions.defaults().showTitle, "widget title defaults off");
    }

    private static void testUnexpectedResetDetection() {
        long observedAt = 1_000_000L;
        UsageWindow full = new UsageWindow(0, 18_000L, 0L, 0L);
        check(ExternalResetDetector.isUnexpectedReset(1, observedAt + 3_600_000L,
                full, observedAt, false), "99% to 100% surprise reset");
        check(ExternalResetDetector.isUnexpectedReset(2, observedAt + 3_600_000L,
                full, observedAt, false), "98% to 100% surprise reset");
        check(ExternalResetDetector.isUnexpectedReset(100, observedAt + 3_600_000L,
                full, observedAt, false), "empty to full surprise reset");
        check(!ExternalResetDetector.isUnexpectedReset(90, observedAt - 1L,
                full, observedAt, false), "natural reset excluded");
        check(!ExternalResetDetector.isUnexpectedReset(90,
                observedAt + ExternalResetDetector.NATURAL_RESET_TOLERANCE_MS,
                full, observedAt, false), "natural reset tolerance boundary excluded");
        check(!ExternalResetDetector.isUnexpectedReset(90, 0L,
                full, observedAt, false), "unknown countdown excluded");
        check(!ExternalResetDetector.isUnexpectedReset(90, observedAt + 3_600_000L,
                full, observedAt, true), "manual reset excluded");
        check(!ExternalResetDetector.isUnexpectedReset(0, observedAt + 3_600_000L,
                full, observedAt, false), "already-full allowance excluded");
        check(!ExternalResetDetector.isUnexpectedReset(90, observedAt + 3_600_000L,
                new UsageWindow(1, 18_000L, 0L, 0L), observedAt, false),
                "partial refill excluded");
        check(ExternalResetDetector.expectedResetAt(
                new UsageWindow(40, 18_000L, 600L, 0L), observedAt)
                == observedAt + 600_000L, "relative countdown fallback");
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
