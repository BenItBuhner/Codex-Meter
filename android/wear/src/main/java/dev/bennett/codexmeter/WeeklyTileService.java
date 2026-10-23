package dev.bennett.codexmeter;

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ProtoLayoutScope;

public final class WeeklyTileService extends CodexTileService {
    @Override
    protected LayoutElement tileLayout(DeviceParameters deviceParameters, ProtoLayoutScope scope) {
        // The followed provider's second meter: weekly (or monthly on Free) for Codex.
        return CodexTileLayouts.meter(this, deviceParameters, 1, scope);
    }
}
