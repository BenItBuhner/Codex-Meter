package dev.bennett.codexmeter;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class FiveHourComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageWindow window = WearGlanceFormat.currentFiveHour(snapshot(preview));
        String percent = WearGlanceFormat.remainingPercentText(window);
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(WearGlanceFormat.remainingPercentOrZero(window), percent, "5h",
                    "5-hour Codex usage remaining");
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(percent, "5h", "5-hour Codex usage remaining");
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText("5-hour " + percent + " left",
                    "5-hour Codex usage remaining");
        }
        return null;
    }
}
