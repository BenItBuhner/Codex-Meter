package dev.bennett.codexmeter;

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;

public final class FiveHourTileService extends CodexTileService {
    @Override
    protected LayoutElement tileLayout(DeviceParameters deviceParameters) {
        return CodexTileLayouts.progress(this, deviceParameters, "5-hour",
                CodexTileLayouts.fiveHour(this));
    }
}
