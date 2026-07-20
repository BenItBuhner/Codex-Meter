package dev.bennett.codexmeter;

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
        if (type == ComplicationType.SHORT_TEXT) {
            return shortText(shortText, null, "Codex next reset");
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(longText, "Codex next reset");
        }
        return null;
    }
}
