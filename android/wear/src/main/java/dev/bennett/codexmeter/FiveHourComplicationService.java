package dev.bennett.codexmeter;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

/** The followed provider's first meter: 5-hour for Codex, the daily or monthly one elsewhere. */
public final class FiveHourComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        return meterData(type, preview, 0);
    }
}
