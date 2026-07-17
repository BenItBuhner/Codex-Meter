package dev.bennett.codexmeter;

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;

public final class MonitorStatusTileService extends CodexTileService {
    @Override
    protected LayoutElement tileLayout(DeviceParameters deviceParameters) {
        return CodexTileLayouts.monitor(this, deviceParameters);
    }
}
