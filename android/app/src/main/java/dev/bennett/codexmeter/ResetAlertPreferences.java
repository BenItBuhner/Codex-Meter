package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* JADX INFO: loaded from: classes.dex */
public final class ResetAlertPreferences {
    private static final String KEY_METRIC = "metric";
    private static final String KEY_RESET_CREDIT_EXPIRY = "reset_credit_expiry";
    private static final String KEY_RESET_CREDIT_EXPIRY_AUTO_APPLY =
            "reset_credit_expiry_auto_apply";
    private static final String KEY_RESET_CREDIT_EXPIRY_AUTO_APPLY_LEAD =
            "reset_credit_expiry_auto_apply_lead_ms";
    private static final String KEY_RESET_CREDIT_LOW_USAGE_AUTO_USE =
            "reset_credit_low_usage_auto_use";
    private static final String KEY_RESET_CREDIT_LOW_USAGE_THRESHOLD =
            "reset_credit_low_usage_threshold";
    private static final String KEY_RESET_CREDIT_LOW_USAGE_METRIC =
            "reset_credit_low_usage_metric";
    private static final String KEY_CLAUDE_SAVED_RESET_EXPIRY = "claude_saved_reset_expiry";
    private static final String KEY_CLAUDE_SAVED_RESET_EXPIRY_LEAD =
            "claude_saved_reset_expiry_lead_ms";
    private static final String KEY_CLAUDE_SAVED_RESET_LOW_USAGE = "claude_saved_reset_low_usage";
    private static final String KEY_CLAUDE_SAVED_RESET_THRESHOLD = "claude_saved_reset_threshold";
    private static final String KEY_CLAUDE_SAVED_RESET_METRIC = "claude_saved_reset_metric";
    private static final String KEY_RESET_CREDIT_EXPIRY_LEAD_TIMES =
            "reset_credit_expiry_lead_times";
    private static final String KEY_RESET_CREDIT_INCREASES = "reset_credit_increases";
    private static final String KEY_STYLE = "style";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_UNEXPECTED_REFILLS = "unexpected_refills";
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";
    private static final String PREFS = "codex_meter_reset_alerts_v1";
    public static final String STYLE_ALARM = "alarm";
    public static final String STYLE_NOTIFICATION = "notification";
    public static final String STYLE_OFF = "off";
    public static final String STYLE_SILENT = "silent";
    public static final long DEFAULT_RESET_CREDIT_EXPIRY_LEAD_TIME_MS =
            24L * 60L * 60L * 1000L;

    private ResetAlertPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    public static String getStyle(Context context) {
        String string = prefs(context).getString(KEY_STYLE, STYLE_OFF);
        return (STYLE_SILENT.equals(string) || STYLE_NOTIFICATION.equals(string) || STYLE_ALARM.equals(string)) ? string : STYLE_OFF;
    }

    public static String getMetric(Context context) {
        String string = prefs(context).getString(KEY_METRIC, "both");
        return ("five_hour".equals(string) || "weekly".equals(string)) ? string : "both";
    }

    public static int getThreshold(Context context) {
        int i = prefs(context).getInt(KEY_THRESHOLD, 25);
        if (isValidThreshold(i)) {
            return i;
        }
        return 25;
    }

    public static void save(Context context, String str, String str2, int i) {
        if (!STYLE_SILENT.equals(str) && !STYLE_NOTIFICATION.equals(str) && !STYLE_ALARM.equals(str)) {
            str = STYLE_OFF;
        }
        if (!"five_hour".equals(str2) && !"weekly".equals(str2)) {
            str2 = "both";
        }
        if (!isValidThreshold(i)) {
            i = 25;
        }
        prefs(context).edit().putString(KEY_STYLE, str).putString(KEY_METRIC, str2).putInt(KEY_THRESHOLD, i).apply();
    }

    public static boolean enabled(Context context) {
        return !STYLE_OFF.equals(getStyle(context));
    }

    public static boolean unexpectedRefillsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_UNEXPECTED_REFILLS, true);
    }

    public static void setUnexpectedRefillsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_UNEXPECTED_REFILLS, enabled).apply();
    }

    public static boolean resetCreditIncreasesEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RESET_CREDIT_INCREASES, true);
    }

    public static void setResetCreditIncreasesEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RESET_CREDIT_INCREASES, enabled).apply();
    }

    public static boolean resetCreditExpiryEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RESET_CREDIT_EXPIRY, true);
    }

    public static void setResetCreditExpiryEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RESET_CREDIT_EXPIRY, enabled).apply();
    }

    public static boolean resetCreditExpiryAutoApplyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RESET_CREDIT_EXPIRY_AUTO_APPLY, true);
    }

    public static void setResetCreditExpiryAutoApplyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RESET_CREDIT_EXPIRY_AUTO_APPLY, enabled).apply();
    }

    /** How long before expiry an unused credit is used automatically. */
    public static long resetCreditExpiryAutoApplyLeadMillis(Context context) {
        return ResetCreditExpiryApply.normalizeLead(prefs(context).getLong(
                KEY_RESET_CREDIT_EXPIRY_AUTO_APPLY_LEAD, ResetCreditExpiryApply.DEFAULT_LEAD_MS));
    }

    public static void setResetCreditExpiryAutoApplyLeadMillis(Context context, long leadMillis) {
        prefs(context).edit().putLong(KEY_RESET_CREDIT_EXPIRY_AUTO_APPLY_LEAD,
                ResetCreditExpiryApply.normalizeLead(leadMillis)).apply();
    }

    /** Off by default: spending a credit early is a choice the user makes. */
    public static boolean resetCreditLowUsageAutoUseEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RESET_CREDIT_LOW_USAGE_AUTO_USE, false);
    }

    public static void setResetCreditLowUsageAutoUseEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RESET_CREDIT_LOW_USAGE_AUTO_USE, enabled).apply();
    }

    public static int resetCreditLowUsageThreshold(Context context) {
        return ResetCreditLowUsageAutoUse.normalizeThreshold(prefs(context).getInt(
                KEY_RESET_CREDIT_LOW_USAGE_THRESHOLD,
                ResetCreditLowUsageAutoUse.DEFAULT_THRESHOLD));
    }

    public static void setResetCreditLowUsageThreshold(Context context, int threshold) {
        prefs(context).edit().putInt(KEY_RESET_CREDIT_LOW_USAGE_THRESHOLD,
                ResetCreditLowUsageAutoUse.normalizeThreshold(threshold)).apply();
    }

    public static String resetCreditLowUsageMetric(Context context) {
        return ResetCreditLowUsageAutoUse.normalizeMetric(prefs(context).getString(
                KEY_RESET_CREDIT_LOW_USAGE_METRIC, ResetCreditLowUsageAutoUse.METRIC_BOTH));
    }

    public static void setResetCreditLowUsageMetric(Context context, String metric) {
        prefs(context).edit().putString(KEY_RESET_CREDIT_LOW_USAGE_METRIC,
                ResetCreditLowUsageAutoUse.normalizeMetric(metric)).apply();
    }

    /** On by default, like Codex: a reset about to expire is otherwise lost. */
    public static boolean claudeSavedResetExpiryEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CLAUDE_SAVED_RESET_EXPIRY, true);
    }

    public static void setClaudeSavedResetExpiryEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CLAUDE_SAVED_RESET_EXPIRY, enabled).apply();
    }

    public static long claudeSavedResetExpiryLeadMillis(Context context) {
        return ResetCreditExpiryApply.normalizeLead(prefs(context).getLong(
                KEY_CLAUDE_SAVED_RESET_EXPIRY_LEAD, ResetCreditExpiryApply.DEFAULT_LEAD_MS));
    }

    public static void setClaudeSavedResetExpiryLeadMillis(Context context, long leadMillis) {
        prefs(context).edit().putLong(KEY_CLAUDE_SAVED_RESET_EXPIRY_LEAD,
                ResetCreditExpiryApply.normalizeLead(leadMillis)).apply();
    }

    /** Off by default: spending a saved reset early is a choice the user makes. */
    public static boolean claudeSavedResetLowUsageEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CLAUDE_SAVED_RESET_LOW_USAGE, false);
    }

    public static void setClaudeSavedResetLowUsageEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CLAUDE_SAVED_RESET_LOW_USAGE, enabled).apply();
    }

    public static int claudeSavedResetThreshold(Context context) {
        return ResetCreditLowUsageAutoUse.normalizeThreshold(prefs(context).getInt(
                KEY_CLAUDE_SAVED_RESET_THRESHOLD, ResetCreditLowUsageAutoUse.DEFAULT_THRESHOLD));
    }

    public static void setClaudeSavedResetThreshold(Context context, int threshold) {
        prefs(context).edit().putInt(KEY_CLAUDE_SAVED_RESET_THRESHOLD,
                ResetCreditLowUsageAutoUse.normalizeThreshold(threshold)).apply();
    }

    public static String claudeSavedResetMetric(Context context) {
        return ResetCreditLowUsageAutoUse.normalizeMetric(prefs(context).getString(
                KEY_CLAUDE_SAVED_RESET_METRIC, ResetCreditLowUsageAutoUse.METRIC_BOTH));
    }

    public static void setClaudeSavedResetMetric(Context context, String metric) {
        prefs(context).edit().putString(KEY_CLAUDE_SAVED_RESET_METRIC,
                ResetCreditLowUsageAutoUse.normalizeMetric(metric)).apply();
    }

    public static List<Long> getResetCreditExpiryLeadTimes(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_RESET_CREDIT_EXPIRY_LEAD_TIMES)) {
            return Collections.singletonList(DEFAULT_RESET_CREDIT_EXPIRY_LEAD_TIME_MS);
        }
        Set<String> stored = preferences.getStringSet(KEY_RESET_CREDIT_EXPIRY_LEAD_TIMES,
                Collections.emptySet());
        List<Long> values = new ArrayList<>();
        if (stored != null) {
            for (String value : stored) {
                try {
                    long parsed = Long.parseLong(value);
                    if (validExpiryLeadTime(parsed)) values.add(parsed);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Collections.sort(values);
        return values;
    }

    public static void setResetCreditExpiryLeadTimes(Context context, List<Long> leadTimes) {
        Set<String> stored = new HashSet<>();
        if (leadTimes != null) {
            for (Long leadTime : leadTimes) {
                if (leadTime != null && validExpiryLeadTime(leadTime)) {
                    stored.add(String.valueOf(leadTime));
                }
            }
        }
        prefs(context).edit().putStringSet(KEY_RESET_CREDIT_EXPIRY_LEAD_TIMES, stored).apply();
    }

    private static boolean validExpiryLeadTime(long value) {
        return value >= ResetCreditExpiryReminder.MIN_LEAD_TIME_MS
                && value <= ResetCreditExpiryReminder.MAX_LEAD_TIME_MS;
    }

    private static boolean isValidThreshold(int i) {
        return i == 10 || i == 25 || i == 50 || i == 75 || i == 100;
    }
}
