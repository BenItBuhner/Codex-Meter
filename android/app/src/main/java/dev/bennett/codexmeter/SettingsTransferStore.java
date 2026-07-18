package dev.bennett.codexmeter;

import android.content.Context;
import android.net.Uri;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Reads and writes {@link SettingsTransfer} documents against on-device preferences. */
public final class SettingsTransferStore {
    private SettingsTransferStore() {
    }

    public static SettingsTransfer.Document collect(Context context, boolean includeAppSettings,
            boolean includeNotifications, boolean includeNowBar, boolean includeAuthentication)
            throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("Context is required.");
        }
        if (!includeAppSettings && !includeNotifications && !includeNowBar
                && !includeAuthentication) {
            throw new IllegalArgumentException("Select at least one section to export.");
        }
        Context app = context.getApplicationContext();
        JSONObject appSettings = includeAppSettings ? collectAppSettings(app) : null;
        JSONObject notifications = includeNotifications ? collectNotifications(app) : null;
        JSONObject nowBar = includeNowBar ? collectNowBar(app) : null;
        JSONObject authentication = null;
        if (includeAuthentication) {
            AuthTokens tokens = SecureTokenStore.load(app);
            if (tokens == null || !tokens.isUsable()) {
                throw new IllegalStateException(
                        "No ChatGPT authentication is saved on this device to export.");
            }
            authentication = tokens.toJson();
        }
        return SettingsTransfer.create(System.currentTimeMillis(), appSettings, notifications,
                nowBar, authentication);
    }

    public static void write(Context context, Uri uri, SettingsTransfer.Document document)
            throws Exception {
        if (context == null || uri == null || document == null) {
            throw new IllegalArgumentException("Export target is incomplete.");
        }
        if (!document.hasAnySection()) {
            throw new IllegalArgumentException("Select at least one section to export.");
        }
        byte[] bytes = document.toJsonString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new Exception("Could not open the export file for writing.");
            }
            output.write(bytes);
            output.flush();
        }
    }

    public static SettingsTransfer.Document read(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) {
            throw new IllegalArgumentException("Import source is incomplete.");
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new Exception("Could not open the import file for reading.");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 1024 * 1024) {
                    throw new IllegalArgumentException("Transfer file is too large.");
                }
            }
            return SettingsTransfer.parse(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public static ApplyResult apply(Context context, SettingsTransfer.Document document,
            boolean applyAppSettings, boolean applyNotifications, boolean applyNowBar,
            boolean applyAuthentication) throws Exception {
        if (context == null || document == null) {
            throw new IllegalArgumentException("Import source is incomplete.");
        }
        Context app = context.getApplicationContext();
        List<String> applied = new ArrayList<>();
        boolean themeChanged = false;
        boolean authImported = false;

        if (applyAppSettings) {
            if (!document.hasAppSettings()) {
                throw new IllegalArgumentException("This file has no app settings to import.");
            }
            themeChanged = applyAppSettings(app, document.appSettings);
            applied.add(SettingsTransfer.SECTION_APP_SETTINGS);
        }
        if (applyNotifications) {
            if (!document.hasNotifications()) {
                throw new IllegalArgumentException("This file has no notification settings to import.");
            }
            applyNotifications(app, document.notifications);
            applied.add(SettingsTransfer.SECTION_NOTIFICATIONS);
        }
        if (applyNowBar) {
            if (!document.hasNowBar()) {
                throw new IllegalArgumentException("This file has no Now Bar settings to import.");
            }
            applyNowBar(app, document.nowBar);
            applied.add(SettingsTransfer.SECTION_NOW_BAR);
        }
        if (applyAuthentication) {
            if (!document.hasAuthentication()) {
                throw new IllegalArgumentException("This file has no authentication to import.");
            }
            applyAuthentication(app, document.authentication);
            applied.add(SettingsTransfer.SECTION_AUTHENTICATION);
            authImported = true;
        }
        if (applied.isEmpty()) {
            throw new IllegalArgumentException("Select at least one section to import.");
        }

        RefreshScheduler.schedulePeriodic(app);
        ResetAlertScheduler.scheduleFromSnapshot(app, AppPreferences.loadSnapshot(app));
        ResetNotificationManager.onResetCreditExpirySettingsChanged(app,
                AppPreferences.loadResetCredits(app));
        if (UpdatePreferences.automaticChecks(app)) {
            ReleaseUpdateScheduler.ensureScheduled(app);
        } else {
            ReleaseUpdateScheduler.cancel(app);
        }
        // Only reconcile an active Now Bar when that section was imported. Unrelated
        // imports must not stop a live monitor if a transient repost fails.
        if (applyNowBar && NowBarManager.isActive(app) && !NowBarManager.repostActive(app)) {
            NowBarManager.stop(app, false);
        }
        if (applyAppSettings) {
            NowBarManager.onPaceSettingsChanged(app);
        }
        WidgetRenderer.updateAll(app);
        PhoneWearSync.pushSettings(app);

        if (authImported) {
            new Thread(() -> {
                try {
                    UsageSnapshot snapshot = UsageApi.refreshAndCache(app);
                    RefreshScheduler.scheduleAtNextReset(app, snapshot);
                    WidgetRenderer.updateAll(app);
                } catch (Exception exception) {
                    String message = exception.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = "Imported authentication could not refresh usage.";
                    }
                    AppPreferences.setLastError(app, message);
                    WidgetRenderer.updateAll(app);
                }
            }, "codex-transfer-refresh").start();
        }

        return new ApplyResult(applied, themeChanged, authImported);
    }

    private static JSONObject collectAppSettings(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("app_theme", AppPreferences.getAppTheme(context));
        json.put("material_you", AppPreferences.isMaterialYouEnabled(context));
        json.put("refresh_minutes", AppPreferences.getRefreshMinutes(context));
        json.put("refresh_on_launch", AppPreferences.getRefreshOnLaunch(context));
        json.put("usage_pace_enabled", UsagePacePreferences.isEnabled(context));
        json.put("usage_pace_sensitivity", UsagePacePreferences.getSensitivity(context));
        json.put("automatic_update_checks", UpdatePreferences.automaticChecks(context));
        json.put("notify_updates", UpdatePreferences.notifyUpdatesEnabled(context));
        json.put("check_interval_hours", UpdatePreferences.checkIntervalHours(context));
        json.put("default_widget", SettingsTransfer.widgetOptionsToJson(
                AppPreferences.loadDefaultWidgetOptions(context)));
        return json;
    }

    private static JSONObject collectNotifications(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("style", ResetAlertPreferences.getStyle(context));
        json.put("metric", ResetAlertPreferences.getMetric(context));
        json.put("threshold", ResetAlertPreferences.getThreshold(context));
        json.put("unexpected_refills", ResetAlertPreferences.unexpectedRefillsEnabled(context));
        json.put("reset_credit_increases",
                ResetAlertPreferences.resetCreditIncreasesEnabled(context));
        json.put("reset_credit_expiry", ResetAlertPreferences.resetCreditExpiryEnabled(context));
        json.put("reset_credit_expiry_lead_times", SettingsTransfer.leadTimesToJson(
                ResetAlertPreferences.getResetCreditExpiryLeadTimes(context)));
        return json;
    }

    private static JSONObject collectNowBar(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("display_mode", NowBarPreferences.getDisplayMode(context));
        json.put("percent_mode", NowBarPreferences.getPercentMode(context));
        json.put("auto_enabled", NowBarPreferences.isAutoStartEnabled(context));
        json.put("accelerated_enabled",
                NowBarPreferences.isAcceleratedStartEnabled(context));
        json.put("metric", NowBarPreferences.getMetric(context));
        json.put("threshold", NowBarPreferences.getThreshold(context));
        return json;
    }

    private static boolean applyAppSettings(Context context, JSONObject json) throws Exception {
        String previousTheme = AppPreferences.getAppTheme(context);
        boolean previousMaterialYou = AppPreferences.isMaterialYouEnabled(context);
        String theme = json.optString("app_theme", previousTheme);
        AppPreferences.setAppTheme(context, theme);
        AppPreferences.setMaterialYouEnabled(context,
                json.optBoolean("material_you", previousMaterialYou));
        AppPreferences.setRefreshMinutes(context, json.optInt("refresh_minutes",
                AppPreferences.getRefreshMinutes(context)));
        AppPreferences.setRefreshOnLaunch(context, json.optBoolean("refresh_on_launch",
                AppPreferences.getRefreshOnLaunch(context)));
        UsagePacePreferences.setEnabled(context, json.optBoolean("usage_pace_enabled",
                UsagePacePreferences.isEnabled(context)));
        UsagePacePreferences.setSensitivity(context, json.optString("usage_pace_sensitivity",
                UsagePacePreferences.getSensitivity(context)));
        boolean automatic = json.optBoolean("automatic_update_checks",
                UpdatePreferences.automaticChecks(context));
        UpdatePreferences.setAutomaticChecks(context, automatic);
        UpdatePreferences.setCheckIntervalHours(context, json.optInt("check_interval_hours",
                UpdatePreferences.checkIntervalHours(context)));
        UpdatePreferences.setNotifyUpdatesEnabled(context,
                json.optBoolean("notify_updates", UpdatePreferences.notifyUpdatesEnabled(context)));
        JSONObject widget = json.optJSONObject("default_widget");
        if (widget != null) {
            AppPreferences.saveDefaultWidgetOptions(context,
                    SettingsTransfer.widgetOptionsFromJson(widget,
                            AppPreferences.loadDefaultWidgetOptions(context)));
        }
        return !previousTheme.equals(AppPreferences.getAppTheme(context))
                || previousMaterialYou != AppPreferences.isMaterialYouEnabled(context);
    }

    private static void applyNotifications(Context context, JSONObject json) throws Exception {
        // Fail closed before any preference writes: malformed lead times must leave
        // style/metric/threshold/enablement flags completely untouched.
        final List<Long> leadTimes = json.has("reset_credit_expiry_lead_times")
                ? SettingsTransfer.requireLeadTimes(json, "reset_credit_expiry_lead_times")
                : null;
        ResetAlertPreferences.save(context,
                json.optString("style", ResetAlertPreferences.getStyle(context)),
                json.optString("metric", ResetAlertPreferences.getMetric(context)),
                json.optInt("threshold", ResetAlertPreferences.getThreshold(context)));
        ResetAlertPreferences.setUnexpectedRefillsEnabled(context,
                json.optBoolean("unexpected_refills",
                        ResetAlertPreferences.unexpectedRefillsEnabled(context)));
        ResetAlertPreferences.setResetCreditIncreasesEnabled(context,
                json.optBoolean("reset_credit_increases",
                        ResetAlertPreferences.resetCreditIncreasesEnabled(context)));
        ResetAlertPreferences.setResetCreditExpiryEnabled(context,
                json.optBoolean("reset_credit_expiry",
                        ResetAlertPreferences.resetCreditExpiryEnabled(context)));
        if (leadTimes != null) {
            ResetAlertPreferences.setResetCreditExpiryLeadTimes(context, leadTimes);
        }
        if (ResetAlertPreferences.enabled(context)) {
            ResetNotificationManager.ensureChannel(context);
            ResetNotificationManager.onUsageUpdated(context, AppPreferences.loadSnapshot(context));
            ResetNotificationManager.onResetCreditsUpdated(context,
                    AppPreferences.loadResetCredits(context));
        } else {
            ResetNotificationManager.clearNotificationHistory(context);
        }
    }

    private static void applyNowBar(Context context, JSONObject json) {
        NowBarPreferences.setDisplayMode(context, json.optString("display_mode",
                NowBarPreferences.getDisplayMode(context)));
        NowBarPreferences.setPercentMode(context, json.optString("percent_mode",
                NowBarPreferences.getPercentMode(context)));
        NowBarPreferences.save(context,
                json.optBoolean("auto_enabled", NowBarPreferences.isAutoStartEnabled(context)),
                json.optString("metric", NowBarPreferences.getMetric(context)),
                json.optInt("threshold", NowBarPreferences.getThreshold(context)));
        NowBarPreferences.setAcceleratedStartEnabled(context,
                json.optBoolean("accelerated_enabled",
                        NowBarPreferences.isAcceleratedStartEnabled(context)));
        NowBarPreferences.clearSuppression(context);
        if (NowBarPreferences.isAutoStartEnabled(context)
                || NowBarPreferences.isAcceleratedStartEnabled(context)) {
            NowBarManager.maybeAutoStart(context, AppPreferences.loadSnapshot(context));
        }
    }

    private static void applyAuthentication(Context context, JSONObject json) throws Exception {
        AuthTokens tokens = AuthTokens.fromJson(json);
        if (!tokens.isUsable()) {
            throw new IllegalArgumentException(
                    "Imported authentication is incomplete or invalid.");
        }
        SecureTokenStore.save(context, tokens);
        AppPreferences.clearSnapshot(context);
        AppPreferences.setOAuthPending(context, false, "");
        AppPreferences.completeOnboarding(context);
    }

    public static final class ApplyResult {
        public final List<String> appliedSections;
        public final boolean themeChanged;
        public final boolean authenticationImported;

        ApplyResult(List<String> appliedSections, boolean themeChanged,
                boolean authenticationImported) {
            this.appliedSections = appliedSections == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(appliedSections));
            this.themeChanged = themeChanged;
            this.authenticationImported = authenticationImported;
        }
    }
}
