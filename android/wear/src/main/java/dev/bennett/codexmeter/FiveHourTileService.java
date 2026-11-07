package dev.bennett.codexmeter;

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ProtoLayoutScope;

public final class FiveHourTileService extends CodexTileService {
    @Override
    protected LayoutElement tileLayout(DeviceParameters deviceParameters, ProtoLayoutScope scope) {
        // The followed provider's first meter: 5-hour for Codex, Daily or Monthly for others.
        return CodexTileLayouts.meter(this, deviceParameters, 0, scope);
    }
}
