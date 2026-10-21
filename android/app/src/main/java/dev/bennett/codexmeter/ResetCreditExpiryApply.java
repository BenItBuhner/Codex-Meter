package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pure planner: an unused reset credit that is about to expire is used automatically a chosen
 * lead time before its expiry, while the API still accepts it, instead of being lost.
 */
public final class ResetCreditExpiryApply {
    /** Lead-time choices offered in Settings, shortest first. */
    public static final long[] LEAD_OPTIONS_MS = {
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.MINUTES.toMillis(15),
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(6),
            TimeUnit.HOURS.toMillis(12),
            TimeUnit.HOURS.toMillis(24),
    };
    /** 30 minutes leaves time for a retry if the phone is offline at the first attempt. */
    public static final long DEFAULT_LEAD_MS = TimeUnit.MINUTES.toMillis(30);

    public final String creditId;
    public final long expiresAtMillis;
    public final long triggerAtMillis;

    private ResetCreditExpiryApply(String creditId, long expiresAtMillis, long leadMillis) {
        this.creditId = creditId == null ? "" : creditId;
        this.expiresAtMillis = expiresAtMillis;
        this.triggerAtMillis = Math.max(0L, expiresAtMillis - normalizeLead(leadMillis));
    }

    public String token() {
        return token(creditId, expiresAtMillis);
    }

    public static String token(String creditId, long expiresAtMillis) {
        return (creditId == null ? "" : creditId) + ":" + expiresAtMillis;
    }

    /** Snaps any stored value to the nearest offered option so old or imported values stay valid. */
    public static long normalizeLead(long leadMillis) {
        if (leadMillis <= 0L) return DEFAULT_LEAD_MS;
        long best = LEAD_OPTIONS_MS[0];
        for (long option : LEAD_OPTIONS_MS) {
            if (Math.abs(option - leadMillis) < Math.abs(best - leadMillis)) best = option;
        }
        return best;
    }

    /** "1 minute", "30 minutes", "1 hour", "24 hours". */
    public static String leadLabel(long leadMillis) {
        long lead = normalizeLead(leadMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(lead);
        if (minutes < 60L) return minutes + (minutes == 1L ? " minute" : " minutes");
        long hours = minutes / 60L;
        return hours + (hours == 1L ? " hour" : " hours");
    }

    public static List<ResetCreditExpiryApply> plan(List<RateLimitResetCredit> credits,
            long nowMillis, long leadMillis) {
        List<ResetCreditExpiryApply> planned = new ArrayList<>();
        if (credits == null) return planned;
        for (RateLimitResetCredit credit : credits) {
            if (credit == null || !credit.isAvailable()
                    || credit.expiresAtMillis <= nowMillis) {
                continue;
            }
            planned.add(new ResetCreditExpiryApply(credit.id, credit.expiresAtMillis, leadMillis));
        }
        planned.sort(Comparator
                .comparingLong((ResetCreditExpiryApply apply) -> apply.triggerAtMillis)
                .thenComparingLong(apply -> apply.expiresAtMillis)
                .thenComparing(apply -> apply.creditId));
        return planned;
    }

    public static List<ResetCreditExpiryApply> due(List<RateLimitResetCredit> credits,
            long nowMillis, long leadMillis) {
        List<ResetCreditExpiryApply> due = new ArrayList<>();
        for (ResetCreditExpiryApply apply : plan(credits, nowMillis, leadMillis)) {
            if (isDue(apply.expiresAtMillis, nowMillis, leadMillis)) {
                due.add(apply);
            }
        }
        return Collections.unmodifiableList(due);
    }

    public static boolean isDue(long expiresAtMillis, long nowMillis, long leadMillis) {
        return expiresAtMillis > nowMillis
                && expiresAtMillis - nowMillis <= normalizeLead(leadMillis);
    }
}
