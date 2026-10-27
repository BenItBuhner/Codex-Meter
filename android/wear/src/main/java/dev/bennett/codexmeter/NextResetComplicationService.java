package dev.bennett.codexmeter;

import android.os.Build;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class NextResetComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageSnapshot snapshot = snapshot(preview);
        long now = System.currentTimeMillis();
        WatchMeters.Meter next = WatchMeters.nextReset(snapshot, now);
        String relative = next == null ? "--" : durationUntil(next, snapshot, now);
        String shortText = surfaceText(preview, relative);
        String longText = surfaceText(preview, next == null ? "No reset yet"
                : next.shortLabel + " resets in " + relative);
        float progress = resetProgress(next, snapshot, now);
        String description = providerName(preview) + " next reset";
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(progress, shortText, "Reset", description);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && type == ComplicationType.GOAL_PROGRESS) {
            return goalProgress(progress, shortText, "Reset", description);
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(shortText, "Reset", description);
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(longText, description);
        }
        return imageForType(type, description);
    }

    private static String durationUntil(WatchMeters.Meter meter, UsageSnapshot snapshot,
            long nowMillis) {
        long resetAt = meter.window.effectiveResetAtMillis(snapshot.fetchedAtMillis);
        return NowBarCopy.compactDuration(Math.max(0L, resetAt - nowMillis));
    }

    private static float resetProgress(WatchMeters.Meter next, UsageSnapshot snapshot,
            long nowMillis) {
        if (next == null || snapshot == null || next.window.windowSeconds <= 0L) return 0f;
        long resetAt = next.window.effectiveResetAtMillis(snapshot.fetchedAtMillis);
        long totalMillis = next.window.windowSeconds * 1000L;
        long remainingMillis = Math.max(0L, resetAt - nowMillis);
        return Math.max(0f, Math.min(100f,
                100f * (1f - (float) remainingMillis / (float) totalMillis)));
    }
}
