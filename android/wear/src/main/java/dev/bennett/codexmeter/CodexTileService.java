package dev.bennett.codexmeter;

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ProtoLayoutScope;
import androidx.wear.protolayout.ResourceBuilders.ImageResource;
import androidx.wear.protolayout.ResourceBuilders.Resources;
import androidx.wear.protolayout.TimelineBuilders.Timeline;
import androidx.wear.tiles.RequestBuilders.ResourcesRequest;
import androidx.wear.tiles.RequestBuilders.TileRequest;
import androidx.wear.tiles.TileBuilders.Tile;
import androidx.wear.tiles.TileService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

abstract class CodexTileService extends TileService {
    private static final String RESOURCES_VERSION_PREFIX = "4-";
    private static final long FRESHNESS_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private final Map<String, Resources> resourceCache = new ConcurrentHashMap<>();

    @Override
    protected final ListenableFuture<Tile> onTileRequest(TileRequest requestParams) {
        DeviceParameters deviceParameters = requestParams == null ? null
                : requestParams.getDeviceConfiguration();
        ProtoLayoutScope scope = new ProtoLayoutScope();
        LayoutElement root = tileLayout(deviceParameters, scope);
        Resources collected = scope.collectResources();
        String resourcesVersion = RESOURCES_VERSION_PREFIX
                + Integer.toHexString(collected.getIdToImageMapping().hashCode());
        Resources resources = withVersion(collected, resourcesVersion);
        if (resourceCache.size() >= 8) resourceCache.clear();
        resourceCache.put(resourcesVersion, resources);
        return Futures.immediateFuture(new Tile.Builder()
                .setResourcesVersion(resourcesVersion)
                .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MILLIS)
                .setTileTimeline(Timeline.fromLayoutElement(root))
                .build());
    }

    @Override
    protected final ListenableFuture<Resources> onTileResourcesRequest(
            ResourcesRequest requestParams) {
        String requestedVersion = requestParams == null ? "" : requestParams.getVersion();
        Resources cached = resourceCache.get(requestedVersion);
        if (cached != null) return Futures.immediateFuture(cached);

        ProtoLayoutScope scope = new ProtoLayoutScope();
        DeviceParameters parameters = requestParams == null ? null
                : requestParams.getDeviceConfiguration();
        tileLayout(parameters, scope);
        Resources collected = scope.collectResources();
        String version = requestedVersion.isEmpty()
                ? RESOURCES_VERSION_PREFIX
                        + Integer.toHexString(collected.getIdToImageMapping().hashCode())
                : requestedVersion;
        Resources resources = withVersion(collected, version);
        resourceCache.put(version, resources);
        return Futures.immediateFuture(resources);
    }

    private static Resources withVersion(Resources source, String version) {
        Resources.Builder builder = new Resources.Builder().setVersion(version);
        for (Map.Entry<String, ImageResource> entry
                : source.getIdToImageMapping().entrySet()) {
            builder.addIdToImageMapping(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    protected abstract LayoutElement tileLayout(DeviceParameters deviceParameters,
            ProtoLayoutScope scope);
}
