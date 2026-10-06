package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure planner: unused available reset credits that are about to expire should be
 * consumed in the last minute, while the API still accepts them.
 */
public final class ResetCreditExpiryApply {
    public static final long APPLY_LEAD_MS = 60_000L;

    public final String creditId;
    public final long expiresAtMillis;
    public final long triggerAtMillis;

    private ResetCreditExpiryApply(String creditId, long expiresAtMillis) {
        this.creditId = creditId == null ? "" : creditId;
        this.expiresAtMillis = expiresAtMillis;
        this.triggerAtMillis = Math.max(0L, expiresAtMillis - APPLY_LEAD_MS);
    }

    public String token() {
        return token(creditId, expiresAtMillis);
    }

    public static String token(String creditId, long expiresAtMillis) {
        return (creditId == null ? "" : creditId) + ":" + expiresAtMillis;
    }

    public static List<ResetCreditExpiryApply> plan(List<RateLimitResetCredit> credits,
            long nowMillis) {
        List<ResetCreditExpiryApply> planned = new ArrayList<>();
        if (credits == null) return planned;
        for (RateLimitResetCredit credit : credits) {
            if (credit == null || !credit.isAvailable()
                    || credit.expiresAtMillis <= nowMillis) {
                continue;
            }
            planned.add(new ResetCreditExpiryApply(credit.id, credit.expiresAtMillis));
        }
        planned.sort(Comparator
                .comparingLong((ResetCreditExpiryApply apply) -> apply.triggerAtMillis)
                .thenComparingLong(apply -> apply.expiresAtMillis)
                .thenComparing(apply -> apply.creditId));
        return planned;
    }

    public static List<ResetCreditExpiryApply> due(List<RateLimitResetCredit> credits,
            long nowMillis) {
        List<ResetCreditExpiryApply> due = new ArrayList<>();
        for (ResetCreditExpiryApply apply : plan(credits, nowMillis)) {
            if (isDue(apply.expiresAtMillis, nowMillis)) {
                due.add(apply);
            }
        }
        return Collections.unmodifiableList(due);
    }

    public static boolean isDue(long expiresAtMillis, long nowMillis) {
        return expiresAtMillis > nowMillis
                && expiresAtMillis - nowMillis <= APPLY_LEAD_MS;
    }
}
