package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class AppPreferences {
    private static final String KEY_APP_STYLE = "app_surface_style";
    private static final String KEY_APP_THEME = "app_theme";
    private static final String KEY_DASHBOARD_ADDITIONAL_LIMITS = "dashboard_additional_limits";
    private static final String KEY_DASHBOARD_FIVE_HOUR = "dashboard_five_hour";
    private static final String KEY_DASHBOARD_HIDDEN_SECTIONS = "dashboard_hidden_sections";
    private static final String KEY_DASHBOARD_MONTHLY = "dashboard_monthly";
    private static final String KEY_DASHBOARD_RESET_CREDITS = "dashboard_reset_credits";
    private static final String KEY_DASHBOARD_SECTION_ORDER = "dashboard_section_order";
    private static final String KEY_DASHBOARD_SPEND_CONTROL = "dashboard_spend_control";
    private static final String KEY_DASHBOARD_USAGE_CREDITS = "dashboard_usage_credits";
    private static final String KEY_DASHBOARD_USAGE_HISTORY = "dashboard_usage_history";
    private static final String KEY_DASHBOARD_WEEKLY = "dashboard_weekly";
    private static final String KEY_HISTORY_SECTION_OVERRIDES = "history_section_overrides";
    private static final String KEY_MATERIAL_YOU = "material_you";
    private static final String KEY_ONE_UI_9_DESIGN = "one_ui_9_design";
    private static final String KEY_APP_DESIGN = "app_design";
    /** Settings → Appearance → Design choices. */
    public static final String DESIGN_CLASSIC = "classic";
    public static final String DESIGN_ONE_UI_9 = "one_ui_9";
    /** Either Liquid Glass look; saved by installs from before the two looks, read as iOS 26. */
    public static final String DESIGN_LIQUID_GLASS = DesignIds.LIQUID_GLASS;
    public static final String DESIGN_MATERIAL_3_EXPRESSIVE = DesignIds.MATERIAL_3_EXPRESSIVE;
    /** Set once a new install's first-run design suggestion has been looked at ("" for none). */
    private static final String KEY_DESIGN_SUGGESTED = "design_suggested_on_first_run";
    /** Liquid Glass (iOS 27-inspired) tint, 0 (ultra clear) to 100 (fully tinted). */
    static final String KEY_GLASS_TINT = "glass_tint_level";
    static final String KEY_GLASS_REDUCE_TRANSPARENCY = "glass_reduce_transparency";
    static final String KEY_GLASS_INCREASE_CONTRAST = "glass_increase_contrast";
    static final String KEY_GLASS_DUO_LAYOUT = "glass_duo_layout";
    private static final String KEY_ERROR = "last_error";
    private static final String KEY_ERROR_AT = "last_error_at";
    private static final String KEY_OAUTH_PENDING = "oauth_pending";
    private static final String KEY_OAUTH_STARTED_AT = "oauth_started_at";
    private static final String KEY_OAUTH_URL = "oauth_url";
    private static final String KEY_ONBOARDING_COMPLETE = "onboarding_complete";
    private static final String KEY_ONBOARDING_STEP = "onboarding_step";
    private static final String KEY_ONBOARDING_PROVIDERS = "onboarding_providers";
    private static final String KEY_AUTOMATIC_REFRESH = "automatic_refresh";
    private static final String KEY_REFRESH_FAILURES = "refresh_failures";
    private static final String KEY_REFRESH_MINUTES = "refresh_minutes";
    private static final String KEY_REFRESH_ON_LAUNCH = "refresh_on_launch";
    private static final String KEY_RESET_CREDITS = "reset_credits_snapshot";
    private static final String KEY_RESET_ERROR = "reset_credits_error";
    private static final String KEY_RESET_ERROR_AT = "reset_credits_error_at";
    private static final String KEY_SCHEDULER_ERROR = "scheduler_error";
    private static final String KEY_SNAPSHOT = "last_snapshot";
    private static final String KEY_HISTORY_FIVE_HOUR = "usage_history_five_hour";
    private static final String KEY_HISTORY_WEEKLY = "usage_history_weekly";
    private static final String KEY_HISTORY_MONTHLY = "usage_history_monthly";
    private static final long OAUTH_STALE_AFTER_MS = 720000;
    private static final String PREFS = "codex_meter_settings_v1";

    private AppPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    public static boolean saveSnapshot(Context context, UsageSnapshot usageSnapshot) {
        if (usageSnapshot == null) {
            return false;
        }
        try {
            return prefs(context).edit().putString(KEY_SNAPSHOT, usageSnapshot.toJson().toString()).remove(KEY_ERROR).remove(KEY_ERROR_AT).commit();
        } catch (Exception e) {
            setLastError(context, "Could not cache the latest usage response.");
            return false;
        }
    }

    public static UsageSnapshot loadSnapshot(Context context) {
        String string = prefs(context).getString(KEY_SNAPSHOT, null);
        if (string == null || string.isEmpty()) {
            return null;
        }
        try {
            return UsageSnapshot.fromJson(new JSONObject(string));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearSnapshot(Context context) {
        prefs(context).edit().remove(KEY_SNAPSHOT).remove(KEY_ERROR).remove(KEY_ERROR_AT)
                .remove(KEY_RESET_CREDITS).remove(KEY_RESET_ERROR).remove(KEY_RESET_ERROR_AT)
                .remove(KEY_HISTORY_FIVE_HOUR).remove(KEY_HISTORY_WEEKLY)
                .remove(KEY_HISTORY_MONTHLY)
                .remove(KEY_REFRESH_FAILURES).apply();
        NowBarManager.stop(context);
        NowBarPreferences.clearSuppression(context);
        ResetNotificationManager.clearState(context);
        ResetCreditExpiryScheduler.cancelAll(context);
        ResetCreditExpiryApplyScheduler.cancelAll(context);
        ResetCreditExpiryApplyReceiver.clearState(context);
        PhoneWearSync.pushUsage(context, null);
    }

    public static void setLastError(Context context, String str) {
        if (str == null || str.trim().isEmpty()) {
            clearLastError(context);
        } else {
            prefs(context).edit().putString(KEY_ERROR, trim(str, "Refresh failed.")).putLong(KEY_ERROR_AT, System.currentTimeMillis()).apply();
        }
    }

    public static void clearLastError(Context context) {
        prefs(context).edit().remove(KEY_ERROR).remove(KEY_ERROR_AT).apply();
    }

    /**
     * The last parsed history for each key with the text it came from. The screens read every
     * meter's history on every redraw; parsing hundreds of samples each time stalled the main
     * thread. UsageHistory is immutable, so the parsed copy is shared until the text changes.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> HISTORY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static UsageHistory loadUsageHistory(Context context, String kind) {
        String key = historyKey(kind);
        String stored = prefs(context).getString(key, null);
        if (stored == null || stored.isEmpty()) return UsageHistory.empty(kind);
        Object[] cached = HISTORY_CACHE.get(key);
        if (cached != null && cached[0] == stored) {
            return (UsageHistory) cached[1];
        }
        try {
            UsageHistory parsed = UsageHistory.fromJson(new JSONObject(stored), kind);
            HISTORY_CACHE.put(key, new Object[] {stored, parsed});
            return parsed;
        } catch (Exception ignored) {
            return UsageHistory.empty(kind);
        }
    }

    public static boolean saveUsageHistory(Context context, UsageHistory history) {
        if (history == null) return false;
        try {
            return prefs(context).edit()
                    .putString(historyKey(history.kind), history.toJson().toString()).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void clearUsageHistory(Context context) {
        prefs(context).edit().remove(KEY_HISTORY_FIVE_HOUR).remove(KEY_HISTORY_WEEKLY)
                .remove(KEY_HISTORY_MONTHLY).apply();
    }

    private static String historyKey(String kind) {
        if (UsageHistory.WEEKLY.equals(kind)) return KEY_HISTORY_WEEKLY;
        if (UsageHistory.MONTHLY.equals(kind)) return KEY_HISTORY_MONTHLY;
        return KEY_HISTORY_FIVE_HOUR;
    }

    public static String getLastError(Context context) {
        return prefs(context).getString(KEY_ERROR, "");
    }

    public static String getVisibleRefreshError(Context context) {
        String lastError = getLastError(context);
        if (lastError.isEmpty()) {
            return "";
        }
        UsageSnapshot usageSnapshotLoadSnapshot = loadSnapshot(context);
        if (usageSnapshotLoadSnapshot != null) {
            long j = prefs(context).getLong(KEY_ERROR_AT, 0L);
            if (j <= 0 || j > usageSnapshotLoadSnapshot.fetchedAtMillis) {
                return Math.max(0L, System.currentTimeMillis() - usageSnapshotLoadSnapshot.fetchedAtMillis) < 900000 ? "" : lastError;
            }
            clearLastError(context);
            return "";
        }
        return lastError;
    }

    public static boolean saveResetCredits(Context context, ResetCreditsSnapshot resetCreditsSnapshot) {
        if (resetCreditsSnapshot == null) {
            return false;
        }
        try {
            return prefs(context).edit().putString(KEY_RESET_CREDITS, resetCreditsSnapshot.toJson().toString()).remove(KEY_RESET_ERROR).remove(KEY_RESET_ERROR_AT).commit();
        } catch (Exception e) {
            setResetCreditsError(context, "Could not cache Codex reset credits.");
            return false;
        }
    }

    public static ResetCreditsSnapshot loadResetCredits(Context context) {
        ResetCreditsSnapshot resetCreditsSnapshotFromJson = null;
        String string = prefs(context).getString(KEY_RESET_CREDITS, null);
        if (string != null && !string.isEmpty()) {
            try {
                resetCreditsSnapshotFromJson = ResetCreditsSnapshot.fromJson(new JSONObject(string));
            } catch (Exception e) {
            }
        }
        UsageSnapshot usageSnapshotLoadSnapshot = loadSnapshot(context);
        if (usageSnapshotLoadSnapshot != null && usageSnapshotLoadSnapshot.resetCreditsAvailable >= 0) {
            if (resetCreditsSnapshotFromJson == null) {
                return ResetCreditsSnapshot.summary(usageSnapshotLoadSnapshot.resetCreditsAvailable, usageSnapshotLoadSnapshot.fetchedAtMillis);
            }
            if (usageSnapshotLoadSnapshot.fetchedAtMillis > resetCreditsSnapshotFromJson.fetchedAtMillis && usageSnapshotLoadSnapshot.resetCreditsAvailable != resetCreditsSnapshotFromJson.availableCount) {
                return ResetCreditsSnapshot.summary(usageSnapshotLoadSnapshot.resetCreditsAvailable, usageSnapshotLoadSnapshot.fetchedAtMillis);
            }
            return resetCreditsSnapshotFromJson;
        }
        return resetCreditsSnapshotFromJson;
    }

    public static void setResetCreditsError(Context context, String str) {
        if (str == null || str.trim().isEmpty()) {
            clearResetCreditsError(context);
        } else {
            prefs(context).edit().putString(KEY_RESET_ERROR, trim(str, "Reset-credit refresh failed.")).putLong(KEY_RESET_ERROR_AT, System.currentTimeMillis()).apply();
        }
    }

    public static void clearResetCreditsError(Context context) {
        prefs(context).edit().remove(KEY_RESET_ERROR).remove(KEY_RESET_ERROR_AT).apply();
    }

    public static String getResetCreditsError(Context context) {
        return prefs(context).getString(KEY_RESET_ERROR, "");
    }

    public static String getVisibleResetCreditsError(Context context) {
        String resetCreditsError = getResetCreditsError(context);
        if (resetCreditsError.isEmpty()) {
            return "";
        }
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = loadResetCredits(context);
        if (resetCreditsSnapshotLoadResetCredits != null) {
            long j = prefs(context).getLong(KEY_RESET_ERROR_AT, 0L);
            if (j <= 0 || j > resetCreditsSnapshotLoadResetCredits.fetchedAtMillis) {
                return Math.max(0L, System.currentTimeMillis() - resetCreditsSnapshotLoadResetCredits.fetchedAtMillis) < 1800000 ? "" : resetCreditsError;
            }
            clearResetCreditsError(context);
            return "";
        }
        return resetCreditsError;
    }

    public static void setSchedulerError(Context context, String str) {
        if (str == null || str.trim().isEmpty()) {
            prefs(context).edit().remove(KEY_SCHEDULER_ERROR).apply();
        } else {
            prefs(context).edit().putString(KEY_SCHEDULER_ERROR, trim(str, "Background scheduling is unavailable.")).apply();
        }
    }

    public static String getSchedulerError(Context context) {
        return prefs(context).getString(KEY_SCHEDULER_ERROR, "");
    }

    public static int getRefreshMinutes(Context context) {
        int i = prefs(context).getInt(KEY_REFRESH_MINUTES, 30);
        if (validRefresh(i)) {
            return i;
        }
        return 30;
    }

    public static void setRefreshMinutes(Context context, int i) {
        SharedPreferences.Editor editorEdit = prefs(context).edit();
        if (!validRefresh(i)) {
            i = 30;
        }
        editorEdit.putInt(KEY_REFRESH_MINUTES, i).apply();
    }

    public static boolean getAutomaticRefresh(Context context) {
        return prefs(context).getBoolean(KEY_AUTOMATIC_REFRESH, true);
    }

    public static void setAutomaticRefresh(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC_REFRESH, enabled).apply();
    }

    public static int getRefreshFailures(Context context) {
        return Math.max(0, Math.min(3, prefs(context).getInt(KEY_REFRESH_FAILURES, 0)));
    }

    public static void recordRefreshSuccess(Context context) {
        prefs(context).edit().remove(KEY_REFRESH_FAILURES).apply();
    }

    public static void recordRefreshFailure(Context context) {
        int failures = Math.min(3, getRefreshFailures(context) + 1);
        prefs(context).edit().putInt(KEY_REFRESH_FAILURES, failures).apply();
    }

    public static boolean getRefreshOnLaunch(Context context) {
        return prefs(context).getBoolean(KEY_REFRESH_ON_LAUNCH, true);
    }

    public static void setRefreshOnLaunch(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_REFRESH_ON_LAUNCH, enabled).apply();
    }

    public static boolean showDashboardFiveHour(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_FIVE_HOUR, true);
    }

    public static void setShowDashboardFiveHour(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_FIVE_HOUR, show).apply();
    }

    public static boolean showDashboardWeekly(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_WEEKLY, true);
    }

    public static void setShowDashboardWeekly(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_WEEKLY, show).apply();
    }

    public static boolean showDashboardMonthly(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_MONTHLY, true);
    }

    public static void setShowDashboardMonthly(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_MONTHLY, show).apply();
    }

    public static boolean showDashboardSpendControl(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_SPEND_CONTROL, true);
    }

    public static void setShowDashboardSpendControl(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_SPEND_CONTROL, show).apply();
    }

    public static boolean showDashboardAdditionalLimits(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_ADDITIONAL_LIMITS, true);
    }

    public static boolean showDashboardUsageCredits(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_USAGE_CREDITS, true);
    }

    public static void setShowDashboardUsageCredits(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_USAGE_CREDITS, show).apply();
    }

    public static boolean showDashboardUsageHistory(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_USAGE_HISTORY, true);
    }

    public static void setShowDashboardUsageHistory(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_USAGE_HISTORY, show).apply();
    }

    public static boolean showDashboardResetCredits(Context context) {
        return prefs(context).getBoolean(KEY_DASHBOARD_RESET_CREDITS, true);
    }

    public static void setShowDashboardResetCredits(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_DASHBOARD_RESET_CREDITS, show).apply();
    }

    /** Hidden section keys (currently model-specific limits) as a {@link DashboardSections} CSV. */
    public static String getDashboardHiddenSections(Context context) {
        return prefs(context).getString(KEY_DASHBOARD_HIDDEN_SECTIONS, "");
    }

    public static void setDashboardHiddenSections(Context context, String hiddenCsv) {
        if (hiddenCsv == null || hiddenCsv.trim().isEmpty()) {
            prefs(context).edit().remove(KEY_DASHBOARD_HIDDEN_SECTIONS).apply();
        } else {
            prefs(context).edit().putString(KEY_DASHBOARD_HIDDEN_SECTIONS, hiddenCsv).apply();
        }
    }

    public static boolean isDashboardSectionHidden(Context context, String key) {
        return DashboardSections.isHidden(getDashboardHiddenSections(context), key);
    }

    public static void setDashboardSectionHidden(Context context, String key, boolean hidden) {
        setDashboardHiddenSections(context,
                DashboardSections.setHidden(getDashboardHiddenSections(context), key, hidden));
    }

    /** Usage-history highlight overrides as a {@link HistorySections} CSV. */
    public static String getHistorySectionOverrides(Context context) {
        return prefs(context).getString(KEY_HISTORY_SECTION_OVERRIDES, "");
    }

    public static void setHistorySectionOverrides(Context context, String overridesCsv) {
        if (overridesCsv == null || overridesCsv.trim().isEmpty()) {
            prefs(context).edit().remove(KEY_HISTORY_SECTION_OVERRIDES).apply();
        } else {
            prefs(context).edit().putString(KEY_HISTORY_SECTION_OVERRIDES, overridesCsv).apply();
        }
    }

    public static boolean isHistorySectionVisible(Context context, String key) {
        return HistorySections.isVisible(getHistorySectionOverrides(context), key);
    }

    public static void setHistorySectionVisible(Context context, String key, boolean visible) {
        setHistorySectionOverrides(context,
                HistorySections.setVisible(getHistorySectionOverrides(context), key, visible));
    }

    public static void setDashboardVisibility(Context context, boolean fiveHour,
            boolean weekly, boolean monthly, boolean additionalLimits, boolean usageCredits,
            boolean resetCredits, boolean usageHistory) {
        prefs(context).edit()
                .putBoolean(KEY_DASHBOARD_FIVE_HOUR, fiveHour)
                .putBoolean(KEY_DASHBOARD_WEEKLY, weekly)
                .putBoolean(KEY_DASHBOARD_MONTHLY, monthly)
                .putBoolean(KEY_DASHBOARD_ADDITIONAL_LIMITS, additionalLimits)
                .putBoolean(KEY_DASHBOARD_USAGE_CREDITS, usageCredits)
                .putBoolean(KEY_DASHBOARD_RESET_CREDITS, resetCredits)
                .putBoolean(KEY_DASHBOARD_USAGE_HISTORY, usageHistory)
                .apply();
    }

    /** Saved dashboard section order as a comma-separated {@link DashboardSections} key list. */
    public static String getDashboardOrder(Context context) {
        return prefs(context).getString(KEY_DASHBOARD_SECTION_ORDER, "");
    }

    public static void setDashboardOrder(Context context, java.util.List<String> order) {
        String csv = DashboardSections.serialize(order);
        if (csv.isEmpty()) {
            prefs(context).edit().remove(KEY_DASHBOARD_SECTION_ORDER).apply();
        } else {
            prefs(context).edit().putString(KEY_DASHBOARD_SECTION_ORDER, csv).apply();
        }
    }

    private static boolean validRefresh(int i) {
        return i == 5 || i == 10 || i == 15 || i == 30 || i == 60 || i == 120;
    }

    public static String getAppTheme(Context context) {
        String string = prefs(context).getString(KEY_APP_THEME, WidgetOptions.THEME_SYSTEM);
        return (WidgetOptions.THEME_DARK.equals(string) || WidgetOptions.THEME_LIGHT.equals(string)) ? string : WidgetOptions.THEME_SYSTEM;
    }

    public static void setAppTheme(Context context, String str) {
        if (!WidgetOptions.THEME_DARK.equals(str) && !WidgetOptions.THEME_LIGHT.equals(str)) {
            str = WidgetOptions.THEME_SYSTEM;
        }
        prefs(context).edit().putString(KEY_APP_THEME, str).commit();
    }

    /** When enabled, accents follow Android Material You system colors (API 31+). */
    public static boolean isMaterialYouEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MATERIAL_YOU, false);
    }

    public static void setMaterialYouEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MATERIAL_YOU, enabled).commit();
    }

    /**
     * The app's design: classic (One UI 8.5), One UI 9, or one of the two Liquid Glass looks. Installs
     * from before the Liquid Glass design only stored the One UI 9 switch, so that is the fallback,
     * and the single Liquid Glass id saved before the two looks reads as the iOS 26-inspired one.
     */
    public static String getDesign(Context context) {
        SharedPreferences prefs = prefs(context);
        String saved = prefs.getString(KEY_APP_DESIGN, null);
        String design = DesignIds.normalize(saved, prefs.getBoolean(KEY_ONE_UI_9_DESIGN, false));
        // Either of the two former Liquid Glass looks is saved as the one Liquid Glass design.
        if (saved != null && !saved.equals(design) && DesignIds.isLiquidGlass(saved)) {
            prefs.edit().putString(KEY_APP_DESIGN, design).apply();
        }
        return design;
    }

    public static void setDesign(Context context, String design) {
        design = DesignIds.forSave(design);
        prefs(context).edit()
                .putString(KEY_APP_DESIGN, design)
                .putBoolean(KEY_ONE_UI_9_DESIGN, DESIGN_ONE_UI_9.equals(design))
                .commit();
    }

    /** When enabled, pages use the One UI 9 chrome (floating toolbar and tab bar). */
    public static boolean isOneUi9Design(Context context) {
        return DESIGN_ONE_UI_9.equals(getDesign(context));
    }

    public static void setOneUi9Design(Context context, boolean enabled) {
        setDesign(context, enabled ? DESIGN_ONE_UI_9 : DESIGN_CLASSIC);
    }

    /** When enabled, pages use the Liquid Glass-inspired chrome from the Glasse library (either look). */
    public static boolean isLiquidGlassDesign(Context context) {
        return DesignIds.isLiquidGlass(getDesign(context));
    }

    /** Material 3 Expressive: the dashboard is drawn with Material Components' Expressive views. */
    public static boolean isMaterialExpressiveDesign(Context context) {
        return DesignIds.isMaterialExpressive(getDesign(context));
    }

    /**
     * A new install's first run: picks a starting design from the device ({@link
     * DesignIds#suggestFor}). Does nothing once onboarding has started or finished, when any design
     * was ever saved (including the old One UI 9 switch), or when it has run before, so an existing
     * install is never moved to another design. Returns the design it saved, or null.
     */
    static String applyFirstRunDesignSuggestion(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_DESIGN_SUGGESTED) || prefs.contains(KEY_APP_DESIGN)
                || prefs.contains(KEY_ONE_UI_9_DESIGN) || isOnboardingComplete(context)
                || getOnboardingStep(context) > OnboardingFlow.STEP_WELCOME) {
            return null;
        }
        String suggested = DesignIds.suggestFor(android.os.Build.MANUFACTURER,
                android.os.Build.BRAND, oneUiVersion());
        prefs.edit().putString(KEY_DESIGN_SUGGESTED, suggested == null ? "" : suggested).commit();
        if (suggested != null && !suggested.equals(getDesign(context))) {
            setDesign(context, suggested);
            return suggested;
        }
        return null;
    }

    /** The design the first run picked from the device, while it is still the one in use; else null. */
    static String firstRunSuggestedDesign(Context context) {
        String suggested = prefs(context).getString(KEY_DESIGN_SUGGESTED, "");
        return suggested.isEmpty() || !suggested.equals(getDesign(context)) ? null : suggested;
    }

    /** Samsung's {@code ro.build.version.oneui} (e.g. 80500 for One UI 8.5), or 0 when unknown. */
    private static int oneUiVersion() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class, String.class)
                    .invoke(null, "ro.build.version.oneui", "");
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Liquid Glass's tint, 0 (ultra clear) to 100 (fully tinted). */
    public static int getGlassTintLevel(Context context) {
        return Math.max(0, Math.min(100, prefs(context).getInt(KEY_GLASS_TINT, 25)));
    }

    public static void setGlassTintLevel(Context context, int level) {
        prefs(context).edit().putInt(KEY_GLASS_TINT, Math.max(0, Math.min(100, level))).apply();
    }

    public static void setGlassReduceTransparency(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_GLASS_REDUCE_TRANSPARENCY, on).apply();
    }

    public static void setGlassIncreaseContrast(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_GLASS_INCREASE_CONTRAST, on).apply();
    }

    /**
     * Liquid Glass's Duo layout: on a wide screen (an unfolded Galaxy Z Fold, a tablet) Back, the
     * toolbar buttons and the tabs stand in a column on the right edge. On by default.
     */
    public static boolean isGlassDuoLayout(Context context) {
        return prefs(context).getBoolean(KEY_GLASS_DUO_LAYOUT, true);
    }

    public static void setGlassDuoLayout(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_GLASS_DUO_LAYOUT, on).apply();
    }

    public static boolean isGlassReduceTransparency(Context context) {
        return prefs(context).getBoolean(KEY_GLASS_REDUCE_TRANSPARENCY, false);
    }

    /**
     * Increase Contrast for the glass. Off by default; Settings turns it on by default when the
     * system's own contrast setting is high (see {@link Ui#systemPrefersHighContrast}).
     */
    public static boolean isGlassIncreaseContrast(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_GLASS_INCREASE_CONTRAST)) {
            return prefs.getBoolean(KEY_GLASS_INCREASE_CONTRAST, false);
        }
        return Ui.systemPrefersHighContrast(context);
    }

    public static String getAppStyle(Context context) {
        return WidgetOptions.SURFACE_ONE_UI;
    }

    public static void setAppStyle(Context context, String str) {
        prefs(context).edit().putString(KEY_APP_STYLE, WidgetOptions.SURFACE_ONE_UI).apply();
    }

    public static WidgetOptions loadDefaultWidgetOptions(Context context) {
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        return normalizeLoaded(new WidgetOptions(
                sharedPreferencesPrefs.getString("default_style", WidgetOptions.STYLE_AUTO),
                sharedPreferencesPrefs.getString("default_density", "auto"),
                sharedPreferencesPrefs.getString("default_surface_style", WidgetOptions.SURFACE_ONE_UI),
                sharedPreferencesPrefs.getString("default_graphic_scale", "auto"),
                sharedPreferencesPrefs.getString("default_theme", WidgetOptions.THEME_SYSTEM),
                sharedPreferencesPrefs.getString("default_accent", WidgetOptions.ACCENT_BLUE),
                sharedPreferencesPrefs.getInt("default_opacity", 88),
                sharedPreferencesPrefs.getString("default_reset_mode", WidgetOptions.RESET_ABSOLUTE),
                sharedPreferencesPrefs.getString("default_display_mode", WidgetOptions.DISPLAY_REMAINING),
                sharedPreferencesPrefs.getString("default_metric_mode", WidgetOptions.METRIC_BOTH),
                false,
                sharedPreferencesPrefs.getBoolean("default_show_plan", false),
                sharedPreferencesPrefs.getBoolean("default_show_updated", false),
                sharedPreferencesPrefs.getBoolean("default_show_refresh", true),
                sharedPreferencesPrefs.getBoolean("default_show_reset_credits", false),
                sharedPreferencesPrefs.getBoolean("default_show_reset_action", false))
                .withPercentSymbol(sharedPreferencesPrefs.getBoolean(
                        "default_show_percent_symbol", true))
                .withVisibleMeters(sharedPreferencesPrefs.getString("default_visible_meters", "")));
    }

    public static void saveDefaultWidgetOptions(Context context, WidgetOptions widgetOptions) {
        prefs(context).edit()
                .putString("default_style", widgetOptions.layout)
                .putString("default_layout", widgetOptions.layout)
                .putString("default_density", widgetOptions.density)
                .putString("default_surface_style", widgetOptions.surfaceStyle)
                .putString("default_graphic_scale", widgetOptions.graphicScale)
                .putString("default_theme", widgetOptions.theme)
                .putString("default_accent", widgetOptions.accent)
                .putInt("default_opacity", widgetOptions.opacity)
                .putString("default_reset_mode", widgetOptions.resetMode)
                .putString("default_display_mode", widgetOptions.displayMode)
                .putString("default_metric_mode", widgetOptions.metricMode)
                .putString("default_visible_meters", widgetOptions.visibleMeters)
                .putBoolean("default_show_title", widgetOptions.showTitle)
                .putBoolean("default_show_plan", widgetOptions.showPlan)
                .putBoolean("default_show_updated", widgetOptions.showUpdated)
                .putBoolean("default_show_refresh", widgetOptions.showRefresh)
                .putBoolean("default_show_reset_credits", widgetOptions.showResetCredits)
                .putBoolean("default_show_reset_action", widgetOptions.showResetAction)
                .putBoolean("default_show_percent_symbol", widgetOptions.showPercentSymbol)
                .apply();
    }

    public static WidgetOptions loadWidgetOptions(Context context, int i) {
        if (i == 0) {
            return loadDefaultWidgetOptions(context);
        }
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        WidgetOptions widgetOptionsLoadDefaultWidgetOptions = loadDefaultWidgetOptions(context);
        String str = "widget_" + i + "_";
        return normalizeLoaded(new WidgetOptions(
                sharedPreferencesPrefs.getString(str + "style",
                        widgetOptionsLoadDefaultWidgetOptions.layout),
                sharedPreferencesPrefs.getString(str + "density",
                        widgetOptionsLoadDefaultWidgetOptions.density),
                sharedPreferencesPrefs.getString(str + "surface_style",
                        widgetOptionsLoadDefaultWidgetOptions.surfaceStyle),
                sharedPreferencesPrefs.getString(str + "graphic_scale",
                        widgetOptionsLoadDefaultWidgetOptions.graphicScale),
                sharedPreferencesPrefs.getString(str + "theme",
                        widgetOptionsLoadDefaultWidgetOptions.theme),
                sharedPreferencesPrefs.getString(str + "accent",
                        widgetOptionsLoadDefaultWidgetOptions.accent),
                sharedPreferencesPrefs.getInt(str + "opacity",
                        widgetOptionsLoadDefaultWidgetOptions.opacity),
                sharedPreferencesPrefs.getString(str + "reset_mode",
                        widgetOptionsLoadDefaultWidgetOptions.resetMode),
                sharedPreferencesPrefs.getString(str + "display_mode",
                        widgetOptionsLoadDefaultWidgetOptions.displayMode),
                sharedPreferencesPrefs.getString(str + "metric_mode",
                        widgetOptionsLoadDefaultWidgetOptions.metricMode),
                false,
                sharedPreferencesPrefs.getBoolean(str + "show_plan",
                        widgetOptionsLoadDefaultWidgetOptions.showPlan),
                sharedPreferencesPrefs.getBoolean(str + "show_updated",
                        widgetOptionsLoadDefaultWidgetOptions.showUpdated),
                sharedPreferencesPrefs.getBoolean(str + "show_refresh",
                        widgetOptionsLoadDefaultWidgetOptions.showRefresh),
                sharedPreferencesPrefs.getBoolean(str + "show_reset_credits",
                        widgetOptionsLoadDefaultWidgetOptions.showResetCredits),
                sharedPreferencesPrefs.getBoolean(str + "show_reset_action",
                        widgetOptionsLoadDefaultWidgetOptions.showResetAction))
                .withPercentSymbol(sharedPreferencesPrefs.getBoolean(str + "show_percent_symbol",
                        widgetOptionsLoadDefaultWidgetOptions.showPercentSymbol))
                .withVisibleMeters(sharedPreferencesPrefs.getString(str + "visible_meters",
                        widgetOptionsLoadDefaultWidgetOptions.visibleMeters)));
    }

    public static String getWidgetTapAction(Context context, int appWidgetId) {
        if (appWidgetId == 0) {
            return WidgetOptions.TAP_OPEN_APP;
        }
        return WidgetOptions.normalizeTapAction(
                prefs(context).getString("widget_" + appWidgetId + "_tap_action",
                        WidgetOptions.TAP_OPEN_APP));
    }

    public static void saveWidgetTapAction(Context context, int appWidgetId, String action) {
        if (appWidgetId != 0) {
            prefs(context).edit().putString("widget_" + appWidgetId + "_tap_action",
                    WidgetOptions.normalizeTapAction(action)).apply();
        }
    }

    /**
     * Keeps One UI surface defaults and hides unused chrome flags without wiping the user's
     * layout preference or visible-meters selection.
     */
    private static WidgetOptions normalizeLoaded(WidgetOptions options) {
        return new WidgetOptions(options.layout, WidgetOptions.DENSITY_AUTO,
                WidgetOptions.SURFACE_ONE_UI, "auto", options.theme, options.accent,
                options.opacity, WidgetOptions.RESET_HIDDEN, options.displayMode, options.metricMode,
                false, false, false, false, false, false)
                .withPercentSymbol(options.showPercentSymbol)
                .withVisibleMeters(options.visibleMeters);
    }

    public static void saveWidgetOptions(Context context, int i, WidgetOptions widgetOptions) {
        String str = "widget_" + i + "_";
        String metricMode = metricModeFromVisible(widgetOptions.effectiveVisibleMeters());
        prefs(context).edit()
                .putString(str + "style", widgetOptions.layout)
                .putString(str + "layout", widgetOptions.layout)
                .putString(str + "density", widgetOptions.density)
                .putString(str + "surface_style", widgetOptions.surfaceStyle)
                .putString(str + "graphic_scale", widgetOptions.graphicScale)
                .putString(str + "theme", widgetOptions.theme)
                .putString(str + "accent", widgetOptions.accent)
                .putInt(str + "opacity", widgetOptions.opacity)
                .putString(str + "reset_mode", widgetOptions.resetMode)
                .putString(str + "display_mode", widgetOptions.displayMode)
                .putString(str + "metric_mode", metricMode)
                .putString(str + "visible_meters", widgetOptions.visibleMeters)
                .putBoolean(str + "show_title", widgetOptions.showTitle)
                .putBoolean(str + "show_plan", widgetOptions.showPlan)
                .putBoolean(str + "show_updated", widgetOptions.showUpdated)
                .putBoolean(str + "show_refresh", widgetOptions.showRefresh)
                .putBoolean(str + "show_reset_credits", widgetOptions.showResetCredits)
                .putBoolean(str + "show_reset_action", widgetOptions.showResetAction)
                .putBoolean(str + "show_percent_symbol", widgetOptions.showPercentSymbol)
                .apply();
    }

    private static String metricModeFromVisible(String visibleCsv) {
        java.util.List<String> keys = WidgetMeters.parse(visibleCsv);
        boolean five = WidgetMeters.contains(keys, WidgetMeters.FIVE_HOUR);
        boolean weekly = WidgetMeters.contains(keys, WidgetMeters.WEEKLY);
        if (five && !weekly) {
            return WidgetOptions.METRIC_FIVE_HOUR;
        }
        if (weekly && !five) {
            return WidgetOptions.METRIC_WEEKLY;
        }
        return WidgetOptions.METRIC_BOTH;
    }

    public static void deleteWidgetOptions(Context context, int i) {
        String str = "widget_" + i + "_";
        SharedPreferences.Editor editorEdit = prefs(context).edit();
        for (String str2 : new String[]{"style", "layout", "density", "surface_style",
                "graphic_scale", "theme", "accent", "opacity", "reset_mode", "display_mode",
                "metric_mode", "visible_meters", "show_title", "show_plan", "show_updated",
                "show_refresh", "show_reset_credits", "show_reset_action", "show_percent_symbol",
                "tap_action"}) {
            editorEdit.remove(str + str2);
        }
        editorEdit.apply();
    }

    public static LockWidgetOptions loadLockWidgetOptions(Context context, int i) {
        if (i == 0) {
            return LockWidgetOptions.defaults();
        }
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        String str = "lock_widget_" + i + "_";
        return new LockWidgetOptions(
                sharedPreferencesPrefs.getString(str + "metric_mode", "both"),
                sharedPreferencesPrefs.getBoolean(str + "show_reset_credits", false),
                sharedPreferencesPrefs.getBoolean(str + "show_reset_action", false),
                sharedPreferencesPrefs.getBoolean(str + "show_countdown", true),
                sharedPreferencesPrefs.getString(str + "visible_meters", ""));
    }

    public static void saveLockWidgetOptions(Context context, int i, LockWidgetOptions lockWidgetOptions) {
        if (i != 0 && lockWidgetOptions != null) {
            String str = "lock_widget_" + i + "_";
            prefs(context).edit()
                    .putString(str + "metric_mode", lockWidgetOptions.metricMode)
                    .putBoolean(str + "show_reset_credits", lockWidgetOptions.showResetCredits)
                    .putBoolean(str + "show_reset_action", lockWidgetOptions.showResetAction)
                    .putBoolean(str + "show_countdown", lockWidgetOptions.showCountdown)
                    .putString(str + "visible_meters", lockWidgetOptions.visibleMeters)
                    .apply();
        }
    }

    public static void deleteLockWidgetOptions(Context context, int i) {
        String str = "lock_widget_" + i + "_";
        prefs(context).edit()
                .remove(str + "metric_mode")
                .remove(str + "show_reset_credits")
                .remove(str + "show_reset_action")
                .remove(str + "show_countdown")
                .remove(str + "visible_meters")
                .apply();
    }

    public static void setOAuthPending(Context context, boolean z, String str) {
        SharedPreferences.Editor editorPutBoolean = prefs(context).edit().putBoolean(KEY_OAUTH_PENDING, z);
        if (str == null) {
            str = "";
        }
        SharedPreferences.Editor editorPutString = editorPutBoolean.putString(KEY_OAUTH_URL, str);
        if (z) {
            editorPutString.putLong(KEY_OAUTH_STARTED_AT, System.currentTimeMillis());
        } else {
            editorPutString.remove(KEY_OAUTH_STARTED_AT);
        }
        editorPutString.apply();
    }

    public static boolean isOAuthPending(Context context) {
        SharedPreferences sharedPreferencesPrefs = prefs(context);
        if (!sharedPreferencesPrefs.getBoolean(KEY_OAUTH_PENDING, false)) {
            return false;
        }
        long j = sharedPreferencesPrefs.getLong(KEY_OAUTH_STARTED_AT, 0L);
        if (j <= 0 || System.currentTimeMillis() - j > OAUTH_STALE_AFTER_MS) {
            setOAuthPending(context, false, "");
            return false;
        }
        return true;
    }

    public static String getOAuthUrl(Context context) {
        return isOAuthPending(context) ? prefs(context).getString(KEY_OAUTH_URL, "") : "";
    }

    public static boolean isOnboardingComplete(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false);
    }

    /** The saved setup step; the activity clamps it to the providers chosen so far. */
    public static int getOnboardingStep(Context context) {
        return prefs(context).getInt(KEY_ONBOARDING_STEP, OnboardingFlow.STEP_WELCOME);
    }

    public static void setOnboardingStep(Context context, int step) {
        prefs(context).edit().putInt(KEY_ONBOARDING_STEP, step).apply();
    }

    /** Providers picked during setup, in enum order. Empty until the user picks. */
    public static java.util.List<UsageProvider> getOnboardingProviders(Context context) {
        java.util.List<UsageProvider> chosen = new java.util.ArrayList<>();
        String saved = prefs(context).getString(KEY_ONBOARDING_PROVIDERS, "");
        for (UsageProvider provider : UsageProvider.values()) {
            for (String id : saved.split(",")) {
                if (provider.id.equals(id.trim())) {
                    chosen.add(provider);
                    break;
                }
            }
        }
        return chosen;
    }

    public static void setOnboardingProviders(Context context,
            java.util.List<UsageProvider> providers) {
        StringBuilder ids = new StringBuilder();
        for (UsageProvider provider : providers) {
            if (ids.length() > 0) ids.append(',');
            ids.append(provider.id);
        }
        prefs(context).edit().putString(KEY_ONBOARDING_PROVIDERS, ids.toString()).apply();
    }

    public static void completeOnboarding(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_ONBOARDING_COMPLETE, true)
                .remove(KEY_ONBOARDING_STEP)
                .remove(KEY_ONBOARDING_PROVIDERS)
                .apply();
    }

    private static String trim(String str, String str2) {
        if (str != null && !str.trim().isEmpty()) {
            str2 = str.trim();
        }
        return str2.length() > 240 ? str2.substring(0, 240) : str2;
    }
}
