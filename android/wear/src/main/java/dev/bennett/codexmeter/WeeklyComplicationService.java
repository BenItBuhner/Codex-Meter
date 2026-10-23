package dev.bennett.codexmeter;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

/** The followed provider's second meter: weekly (monthly on Free) for Codex. */
public final class WeeklyComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        return meterData(type, preview, 1);
    }
}
