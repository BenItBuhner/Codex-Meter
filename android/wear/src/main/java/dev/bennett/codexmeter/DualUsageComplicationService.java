package dev.bennett.codexmeter;

import android.os.Build;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;

/** Both of the followed provider's main meters in one slot, ranged on the tighter one. */
public final class DualUsageComplicationService extends CodexComplicationService {
    @Override
    protected ComplicationData dataForType(ComplicationType type, boolean preview) {
        UsageSnapshot snapshot = snapshot(preview);
        long now = System.currentTimeMillis();
        String provider = providerName(preview);
        String title = shortProviderName(provider);
        String shortValue = surfaceText(preview, WatchMeters.pairShortText(snapshot, now));
        float constrained = WatchMeters.constrainedRemaining(snapshot, now);
        String description = provider + " usage remaining, "
                + WatchMeters.pairLongText(snapshot, now);
        if (type == ComplicationType.RANGED_VALUE) {
            return rangedValue(constrained, shortValue, title, description);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && type == ComplicationType.GOAL_PROGRESS) {
            return goalProgress(constrained, shortValue, title, description);
        } else if (type == ComplicationType.SHORT_TEXT) {
            return shortText(shortValue, title, description);
        } else if (type == ComplicationType.LONG_TEXT) {
            return longText(surfaceText(preview, WatchMeters.pairLongText(snapshot, now)),
                    description);
        }
        return imageForType(type, provider + " usage");
    }
}
