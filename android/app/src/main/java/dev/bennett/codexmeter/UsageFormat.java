package dev.bennett.codexmeter;

import android.content.Context;
import android.text.format.DateFormat;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes.dex */
public final class UsageFormat {
    private UsageFormat() {
    }

    public static String planLabel(String plan) {
        if (plan == null || plan.trim().isEmpty()) {
            return "";
        }
        String normalized = plan.trim().toLowerCase(Locale.US).replace("_", "").replace("-", "")
                .replace(" ", "");
        switch (normalized) {
            case "free": return "Free";
            case "go": return "Go";
            case "plus": return "Plus";
            // OpenAI's Pro tiers are $100, $200 and $500 (learn.chatgpt.com/docs/pricing);
            // "prolite" was Pro 5x and "pro" was Pro 20x before the rename.
            case "prolite":
            case "pro5x":
            case "pro100": return "Pro 100";
            case "pro":
            case "pro20x":
            case "pro200": return "Pro 200";
            case "pro500": return "Pro 500";
            default: return "";
        }
    }

    public static String percent(UsageWindow window, String displayMode, boolean compact) {
        if (window == null) {
            return compact ? "—" : "Unavailable";
        }
        boolean showUsed = WidgetOptions.DISPLAY_USED.equals(displayMode);
        int shown = showUsed ? window.usedPercent : window.remainingPercent();
        if (compact) {
            return shown + "%";
        }
        return shown + "% " + (showUsed ? WidgetOptions.DISPLAY_USED : "left");
    }

    public static String reset(Context context, UsageWindow window, String resetFormat, long nowMillis) {
        return reset(context, window, resetFormat, nowMillis, nowMillis);
    }

    public static String reset(Context context, UsageWindow window, String resetFormat,
            long observedAtMillis, long nowMillis) {
        if (window == null || WidgetOptions.RESET_HIDDEN.equals(resetFormat)
                || !window.showsResetCountdown()) {
            return "";
        }
        return resetAt(context, window.effectiveResetAtMillis(observedAtMillis), resetFormat,
                nowMillis);
    }

    /** Reset copy for an absolute instant that has already been resolved by the caller. */
    public static String resetAt(Context context, long resetAtMillis, String resetFormat,
            long nowMillis) {
        if (resetAtMillis <= 0) {
            return "Reset time unavailable";
        }
        String absoluteText = absolute(context, resetAtMillis, nowMillis);
        String relativeText = relative(resetAtMillis, nowMillis);
        if (WidgetOptions.RESET_RELATIVE.equals(resetFormat)) {
            return "Resets " + relativeText;
        }
        return "both".equals(resetFormat) ? "Resets " + absoluteText + " (" + relativeText + ")" : "Resets " + absoluteText;
    }

    /**
     * Monthly credit limit headline matching the Codex clients: "8,000 of 25,000 credits used",
     * falling back to a percentage when the amounts are missing.
     */
    public static String spendControlUsage(Context context, SpendControl control) {
        BigDecimal used = control.numericUsed();
        BigDecimal limit = control.numericLimit();
        if (used != null && limit != null) {
            return context.getResources().getQuantityString(
                    R.plurals.spend_control_credits_used, SpendControl.pluralQuantity(limit),
                    credits(used), credits(limit));
        }
        int percent = control.effectiveUsedPercent();
        return percent >= 0 ? percent + "% of monthly credits used" : "Usage details unavailable";
    }

    /**
     * Trailing "32% used" for the card header. Empty when the headline already states the
     * percentage because no amounts were reported.
     */
    public static String spendControlPercentUsed(SpendControl control) {
        int percent = control.effectiveUsedPercent();
        boolean hasAmounts = control.numericUsed() != null && control.numericLimit() != null;
        return hasAmounts && percent >= 0 ? percent + "% used" : "";
    }

    /**
     * Secondary line: the workspace reached state when OpenAI flags it (that flag lives beside
     * the per-member limit, so it replaces the remainder rather than contradicting it), else
     * "17,000 credits remaining", else a percentage.
     */
    public static String spendControlRemaining(Context context, SpendControl control) {
        if (control.reached) {
            return "Workspace credit limit reached";
        }
        BigDecimal remaining = control.numericRemaining();
        if (remaining != null) {
            BigDecimal shown = remaining.max(BigDecimal.ZERO);
            return context.getResources().getQuantityString(
                    R.plurals.spend_control_credits_remaining,
                    SpendControl.pluralQuantity(shown), credits(shown));
        }
        int percent = control.effectiveRemainingPercent();
        return percent >= 0 ? percent + "% remaining" : "";
    }

    private static String credits(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    public static String estimatedRemaining(UsagePace.Assessment assessment) {
        // A depleted window already says 0%, so the estimate would only repeat it.
        if (assessment == null || !assessment.available
                || assessment.estimatedRemainingMillis <= 0L || assessment.usedPercent >= 100) {
            return "";
        }
        return "Est. " + compactDuration(assessment.estimatedRemainingMillis);
    }

    static String compactDuration(long millis) {
        long minutes = Math.max(1L, TimeUnit.MILLISECONDS.toMinutes(Math.max(0L, millis)));
        long days = minutes / 1440L;
        long hours = (minutes % 1440L) / 60L;
        long remainingMinutes = minutes % 60L;
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + remainingMinutes + "m";
        }
        return minutes + "m";
    }

    public static String absolute(Context context, long atMillis, long nowMillis) {
        String pattern;
        boolean clock24 = DateFormat.is24HourFormat(context);
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(atMillis);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(nowMillis);
        Calendar tomorrow = (Calendar) today.clone();
        tomorrow.add(6, 1);
        if (sameDay(at, today)) {
            pattern = clock24 ? "'today at' HH:mm" : "'today at' h:mm a";
        } else if (sameDay(at, tomorrow)) {
            pattern = clock24 ? "'tomorrow at' HH:mm" : "'tomorrow at' h:mm a";
        } else {
            pattern = clock24 ? "EEE, MMM d 'at' HH:mm" : "EEE, MMM d 'at' h:mm a";
        }
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(atMillis));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(0) == b.get(0) && a.get(1) == b.get(1) && a.get(6) == b.get(6);
    }

    public static String relative(long atMillis, long nowMillis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(Math.max(0L, atMillis - nowMillis));
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return "in " + days + "d " + hours + "h";
        }
        if (hours > 0) {
            return "in " + hours + "h " + mins + "m";
        }
        return minutes > 0 ? "in " + minutes + "m" : "now";
    }

    public static String updated(long updatedAtMillis, long nowMillis) {
        if (updatedAtMillis <= 0) {
            return "Not updated yet";
        }
        long minutes = Math.max(0L, TimeUnit.MILLISECONDS.toMinutes(nowMillis - updatedAtMillis));
        if (minutes < 1) {
            return "Updated just now";
        }
        if (minutes < 60) {
            return "Updated " + minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours < 24 ? "Updated " + hours + "h ago" : "Updated " + (hours / 24) + "d ago";
    }
}
