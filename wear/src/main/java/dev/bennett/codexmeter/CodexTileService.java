package dev.bennett.codexmeter;

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ResourceBuilders.Resources;
import androidx.wear.protolayout.TimelineBuilders.Timeline;
import androidx.wear.tiles.RequestBuilders.ResourcesRequest;
import androidx.wear.tiles.RequestBuilders.TileRequest;
import androidx.wear.tiles.TileBuilders.Tile;
import androidx.wear.tiles.TileService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.TimeUnit;

abstract class CodexTileService extends TileService {
    static final String RESOURCES_VERSION = "1";
    private static final long FRESHNESS_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    @Override
    protected final ListenableFuture<Tile> onTileRequest(TileRequest requestParams) {
        DeviceParameters deviceParameters = requestParams == null ? null
                : requestParams.getDeviceConfiguration();
        LayoutElement root = tileLayout(deviceParameters);
        return Futures.immediateFuture(new Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MILLIS)
                .setTileTimeline(Timeline.fromLayoutElement(root))
                .build());
    }

    @Override
    protected final ListenableFuture<Resources> onTileResourcesRequest(
            ResourcesRequest requestParams) {
        return Futures.immediateFuture(new Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build());
    }

    protected abstract LayoutElement tileLayout(DeviceParameters deviceParameters);
}
