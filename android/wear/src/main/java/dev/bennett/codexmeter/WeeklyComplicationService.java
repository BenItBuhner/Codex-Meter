package dev.bennett.codexmeter;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class WeeklyComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageWindow window = WearGlanceFormat.currentWeekly(snapshot(preview));
        String percent = WearGlanceFormat.remainingPercentText(window);
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(WearGlanceFormat.remainingPercentOrZero(window), percent, "Week",
                    "Weekly Codex usage remaining");
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(percent, "Week", "Weekly Codex usage remaining");
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText("Weekly " + percent + " left",
                    "Weekly Codex usage remaining");
        }
        return null;
    }
}
