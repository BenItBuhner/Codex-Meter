package dev.bennett.codexmeter;

import android.os.Build;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class DualUsageComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageSnapshot snapshot = snapshot(preview);
        String shortValue = surfaceText(preview, WearGlanceFormat.dualShortText(snapshot));
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);
        float constrained = constrainedRemaining(fiveHour, weekly);
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(constrained, shortValue, "Codex",
                    "Five-hour and weekly Codex usage remaining");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && type == ComplicationType.GOAL_PROGRESS) {
            return goalProgress(constrained, shortValue, "Codex",
                    "Five-hour and weekly Codex usage remaining");
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(shortValue, "Codex",
                    "Five-hour and weekly Codex usage remaining");
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(surfaceText(preview, WearGlanceFormat.dualLongText(snapshot)),
                    "Five-hour and weekly Codex usage remaining");
        }
        return imageForType(type, "Codex usage");
    }

    private static float constrainedRemaining(UsageWindow first, UsageWindow second) {
        if (first == null) return WearGlanceFormat.remainingPercentOrZero(second);
        if (second == null) return WearGlanceFormat.remainingPercentOrZero(first);
        return Math.min(first.remainingPercent(), second.remainingPercent());
    }
}
