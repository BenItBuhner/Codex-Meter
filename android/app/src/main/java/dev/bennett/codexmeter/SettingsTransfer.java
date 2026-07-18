package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Portable Codex Meter transfer document. Pure JSON — no Android APIs — so the
 * format can be round-tripped in {@link ParserSelfTest}.
 *
 * <p>Authentication sections contain usable ChatGPT OAuth tokens and must never
 * be shared. The document always embeds {@link #SECURITY_WARNING} when auth is
 * present so a casual file open still surfaces the risk.
 */
public final class SettingsTransfer {
    public static final String FORMAT = "codex_meter_transfer";
    public static final int VERSION = 1;

    public static final String SECTION_APP_SETTINGS = "app_settings";
    public static final String SECTION_NOTIFICATIONS = "notifications";
    public static final String SECTION_NOW_BAR = "now_bar";
    public static final String SECTION_AUTHENTICATION = "authentication";

    public static final String SECURITY_WARNING =
            "IMPORTANT: This file contains ChatGPT authentication tokens. "
                    + "Anyone with this file can access your ChatGPT account. "
                    + "Do not share it, upload it, or send it to anyone. "
                    + "Keep it only on devices you trust.";

    public static final String[] ALL_SECTIONS = {
            SECTION_APP_SETTINGS,
            SECTION_NOTIFICATIONS,
            SECTION_NOW_BAR,
            SECTION_AUTHENTICATION
    };

    public static final String[] SETTINGS_SECTIONS = {
            SECTION_APP_SETTINGS,
            SECTION_NOTIFICATIONS,
            SECTION_NOW_BAR
    };

    private SettingsTransfer() {
    }

    public static boolean isKnownSection(String section) {
        if (section == null || section.isEmpty()) {
            return false;
        }
        for (String known : ALL_SECTIONS) {
            if (known.equals(section)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAuthenticationSection(String section) {
        return SECTION_AUTHENTICATION.equals(section);
    }

    public static Document create(long exportedAtMillis, JSONObject appSettings,
            JSONObject notifications, JSONObject nowBar, JSONObject authentication)
            throws JSONException {
        Document document = new Document();
        document.exportedAtMillis = Math.max(0L, exportedAtMillis);
        document.appSettings = copyObject(appSettings);
        document.notifications = copyObject(notifications);
        document.nowBar = copyObject(nowBar);
        document.authentication = copyObject(authentication);
        return document;
    }

    public static Document parse(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Transfer file is empty.");
        }
        return parse(new JSONObject(json));
    }

    public static Document parse(JSONObject json) throws Exception {
        if (json == null) {
            throw new IllegalArgumentException("Transfer file is empty.");
        }
        String format = json.optString("format", "");
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("Not a Codex Meter transfer file.");
        }
        int version = json.optInt("version", 0);
        if (version < 1 || version > VERSION) {
            throw new IllegalArgumentException("Unsupported transfer file version: " + version);
        }
        Document document = new Document();
        document.exportedAtMillis = Math.max(0L, json.optLong("exported_at", 0L));
        JSONObject sections = json.optJSONObject("sections");
        if (sections == null) {
            sections = new JSONObject();
        }
        document.appSettings = copyObject(sections.optJSONObject(SECTION_APP_SETTINGS));
        document.notifications = copyObject(sections.optJSONObject(SECTION_NOTIFICATIONS));
        document.nowBar = copyObject(sections.optJSONObject(SECTION_NOW_BAR));
        document.authentication = copyObject(sections.optJSONObject(SECTION_AUTHENTICATION));
        if (!document.hasAnySection()) {
            throw new IllegalArgumentException("Transfer file does not contain any sections.");
        }
        return document;
    }

    public static JSONObject widgetOptionsToJson(WidgetOptions options) throws JSONException {
        WidgetOptions safe = options == null ? WidgetOptions.defaults() : options;
        JSONObject json = new JSONObject();
        json.put("style", safe.layout);
        json.put("density", safe.density);
        json.put("surface_style", safe.surfaceStyle);
        json.put("graphic_scale", safe.graphicScale);
        json.put("theme", safe.theme);
        json.put("accent", safe.accent);
        json.put("opacity", safe.opacity);
        json.put("reset_mode", safe.resetMode);
        json.put("display_mode", safe.displayMode);
        json.put("metric_mode", safe.metricMode);
        json.put("show_title", safe.showTitle);
        json.put("show_plan", safe.showPlan);
        json.put("show_updated", safe.showUpdated);
        json.put("show_refresh", safe.showRefresh);
        json.put("show_reset_credits", safe.showResetCredits);
        json.put("show_reset_action", safe.showResetAction);
        json.put("show_percent_symbol", safe.showPercentSymbol);
        return json;
    }

    public static WidgetOptions widgetOptionsFromJson(JSONObject json) {
        return widgetOptionsFromJson(json, WidgetOptions.defaults());
    }

    /**
     * Parses widget options, filling only missing keys from {@code base} so partial
     * transfer objects cannot silently reset device defaults to product defaults.
     */
    public static WidgetOptions widgetOptionsFromJson(JSONObject json, WidgetOptions base) {
        WidgetOptions fallback = base == null ? WidgetOptions.defaults() : base;
        if (json == null) {
            return fallback;
        }
        WidgetOptions options = new WidgetOptions(
                stringOr(json, "style", fallback.layout),
                stringOr(json, "density", fallback.density),
                stringOr(json, "surface_style", fallback.surfaceStyle),
                stringOr(json, "graphic_scale", fallback.graphicScale),
                stringOr(json, "theme", fallback.theme),
                stringOr(json, "accent", fallback.accent),
                intOr(json, "opacity", fallback.opacity),
                stringOr(json, "reset_mode", fallback.resetMode),
                stringOr(json, "display_mode", fallback.displayMode),
                stringOr(json, "metric_mode", fallback.metricMode),
                booleanOr(json, "show_title", fallback.showTitle),
                booleanOr(json, "show_plan", fallback.showPlan),
                booleanOr(json, "show_updated", fallback.showUpdated),
                booleanOr(json, "show_refresh", fallback.showRefresh),
                booleanOr(json, "show_reset_credits", fallback.showResetCredits),
                booleanOr(json, "show_reset_action", fallback.showResetAction));
        return options.withPercentSymbol(
                booleanOr(json, "show_percent_symbol", fallback.showPercentSymbol));
    }

    public static JSONArray leadTimesToJson(List<Long> leadTimes) {
        JSONArray array = new JSONArray();
        if (leadTimes != null) {
            for (Long leadTime : leadTimes) {
                if (leadTime != null && leadTime > 0L) {
                    array.put(leadTime.longValue());
                }
            }
        }
        return array;
    }

    public static List<Long> leadTimesFromJson(JSONArray array) {
        if (array == null) {
            throw new IllegalArgumentException(
                    "reset_credit_expiry_lead_times must be a JSON array.");
        }
        if (array.length() == 0) {
            return Collections.emptyList();
        }
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            long value = array.optLong(i, -1L);
            if (value > 0L) {
                values.add(value);
            }
        }
        Collections.sort(values);
        return values;
    }

    /** Requires a JSON array when the lead-times key is present; rejects wrong types. */
    public static List<Long> requireLeadTimes(JSONObject json, String key) throws JSONException {
        if (json == null || !json.has(key) || json.isNull(key)) {
            throw new IllegalArgumentException(key + " must be a JSON array.");
        }
        Object raw = json.get(key);
        if (!(raw instanceof JSONArray)) {
            throw new IllegalArgumentException(key + " must be a JSON array.");
        }
        return leadTimesFromJson((JSONArray) raw);
    }

    private static String stringOr(JSONObject json, String key, String fallback) {
        return json.has(key) ? json.optString(key, fallback) : fallback;
    }

    private static int intOr(JSONObject json, String key, int fallback) {
        return json.has(key) ? json.optInt(key, fallback) : fallback;
    }

    private static boolean booleanOr(JSONObject json, String key, boolean fallback) {
        return json.has(key) ? json.optBoolean(key, fallback) : fallback;
    }

    public static String sectionTitle(String section) {
        if (SECTION_APP_SETTINGS.equals(section)) {
            return "App settings";
        }
        if (SECTION_NOTIFICATIONS.equals(section)) {
            return "Notifications";
        }
        if (SECTION_NOW_BAR.equals(section)) {
            return "Now Bar";
        }
        if (SECTION_AUTHENTICATION.equals(section)) {
            return "Authentication";
        }
        return section == null ? "" : section;
    }

    public static String sectionSummary(String section) {
        if (SECTION_APP_SETTINGS.equals(section)) {
            return "Theme, refresh, updates, and default widget look";
        }
        if (SECTION_NOTIFICATIONS.equals(section)) {
            return "Low-usage alerts and reset-credit reminders";
        }
        if (SECTION_NOW_BAR.equals(section)) {
            return "Display mode, percentage mode, and auto-start";
        }
        if (SECTION_AUTHENTICATION.equals(section)) {
            return "ChatGPT sign-in tokens (sensitive)";
        }
        return "";
    }

    private static JSONObject copyObject(JSONObject json) throws JSONException {
        if (json == null) {
            return null;
        }
        return new JSONObject(json.toString());
    }

    public static final class Document {
        public long exportedAtMillis;
        public JSONObject appSettings;
        public JSONObject notifications;
        public JSONObject nowBar;
        public JSONObject authentication;

        public boolean hasAppSettings() {
            return appSettings != null;
        }

        public boolean hasNotifications() {
            return notifications != null;
        }

        public boolean hasNowBar() {
            return nowBar != null;
        }

        public boolean hasAuthentication() {
            return authentication != null;
        }

        public boolean hasAnySection() {
            return hasAppSettings() || hasNotifications() || hasNowBar() || hasAuthentication();
        }

        public boolean containsSection(String section) {
            if (SECTION_APP_SETTINGS.equals(section)) {
                return hasAppSettings();
            }
            if (SECTION_NOTIFICATIONS.equals(section)) {
                return hasNotifications();
            }
            if (SECTION_NOW_BAR.equals(section)) {
                return hasNowBar();
            }
            if (SECTION_AUTHENTICATION.equals(section)) {
                return hasAuthentication();
            }
            return false;
        }

        public List<String> presentSections() {
            List<String> sections = new ArrayList<>();
            for (String section : ALL_SECTIONS) {
                if (containsSection(section)) {
                    sections.add(section);
                }
            }
            return sections;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("exported_at", exportedAtMillis);
            JSONObject sections = new JSONObject();
            if (appSettings != null) {
                sections.put(SECTION_APP_SETTINGS, appSettings);
            }
            if (notifications != null) {
                sections.put(SECTION_NOTIFICATIONS, notifications);
            }
            if (nowBar != null) {
                sections.put(SECTION_NOW_BAR, nowBar);
            }
            if (authentication != null) {
                sections.put(SECTION_AUTHENTICATION, authentication);
            }
            root.put("sections", sections);
            root.put("contains_authentication", authentication != null);
            if (authentication != null) {
                root.put("security_warning", SECURITY_WARNING);
            }
            return root;
        }

        public String toJsonString() throws JSONException {
            return toJson().toString(2);
        }

        public Document selecting(boolean appSettingsSelected, boolean notificationsSelected,
                boolean nowBarSelected, boolean authenticationSelected) throws JSONException {
            return create(
                    exportedAtMillis,
                    appSettingsSelected ? appSettings : null,
                    notificationsSelected ? notifications : null,
                    nowBarSelected ? nowBar : null,
                    authenticationSelected ? authentication : null);
        }
    }
}
