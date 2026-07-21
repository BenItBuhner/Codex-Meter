package dev.bennett.codexmeter;

import android.os.Build;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class NextResetComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageSnapshot snapshot = snapshot(preview);
        long now = System.currentTimeMillis();
        String shortText = surfaceText(preview,
                WearGlanceFormat.nextResetRelativeText(snapshot, now));
        String longText = surfaceText(preview,
                WearGlanceFormat.nextResetLongText(snapshot, now));
        float progress = resetProgress(snapshot, now);
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(progress, shortText, "Reset", "Codex next reset");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && type == ComplicationType.GOAL_PROGRESS) {
            return goalProgress(progress, shortText, "Reset", "Codex next reset");
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(shortText, "Reset", "Codex next reset");
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(longText, "Codex next reset");
        }
        return imageForType(type, "Codex next reset");
    }

    private static float resetProgress(UsageSnapshot snapshot, long nowMillis) {
        if (snapshot == null) return 0f;
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);
        UsageWindow next = earlierReset(fiveHour, weekly, snapshot.fetchedAtMillis, nowMillis);
        if (next == null || next.windowSeconds <= 0L) return 0f;
        long resetAt = next.effectiveResetAtMillis(snapshot.fetchedAtMillis);
        long totalMillis = next.windowSeconds * 1000L;
        long remainingMillis = Math.max(0L, resetAt - nowMillis);
        return Math.max(0f, Math.min(100f,
                100f * (1f - (float) remainingMillis / (float) totalMillis)));
    }

    private static UsageWindow earlierReset(UsageWindow first, UsageWindow second,
            long observedAtMillis, long nowMillis) {
        long firstAt = futureReset(first, observedAtMillis, nowMillis);
        long secondAt = futureReset(second, observedAtMillis, nowMillis);
        if (firstAt == Long.MAX_VALUE) return secondAt == Long.MAX_VALUE ? null : second;
        if (secondAt == Long.MAX_VALUE) return first;
        return firstAt <= secondAt ? first : second;
    }

    private static long futureReset(UsageWindow window, long observedAtMillis, long nowMillis) {
        if (window == null) return Long.MAX_VALUE;
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        return resetAt > nowMillis ? resetAt : Long.MAX_VALUE;
    }
}
