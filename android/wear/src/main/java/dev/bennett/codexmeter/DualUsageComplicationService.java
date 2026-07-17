package dev.bennett.codexmeter;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

public final class DualUsageComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageSnapshot snapshot = snapshot(preview);
        if (type == ComplicationType.SHORT_TEXT) {
            return shortText(WearGlanceFormat.dualShortText(snapshot), null,
                    "Five-hour and weekly Codex usage remaining");
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(WearGlanceFormat.dualLongText(snapshot),
                    "Five-hour and weekly Codex usage remaining");
        }
        return null;
    }
}
