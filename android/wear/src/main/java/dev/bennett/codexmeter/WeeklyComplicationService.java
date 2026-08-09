package dev.bennett.codexmeter;

import android.os.Build;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class WeeklyComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageSnapshot snapshot = snapshot(preview);
        UsageWindow window = WearGlanceFormat.currentLongWindow(snapshot);
        String shortLabel = WearGlanceFormat.longWindowShortLabel(snapshot);
        String label = WearGlanceFormat.longWindowLabel(snapshot);
        String description = label + " Codex usage remaining";
        String percent = surfaceText(preview, WearGlanceFormat.remainingPercentText(window));
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(WearGlanceFormat.remainingPercentOrZero(window), percent,
                    shortLabel, description);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && type == ComplicationType.GOAL_PROGRESS) {
            return goalProgress(WearGlanceFormat.remainingPercentOrZero(window), percent,
                    shortLabel, description);
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(percent, shortLabel, description);
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(label + " " + percent + " left", description);
        }
        return imageForType(type, label + " Codex usage");
    }
}
