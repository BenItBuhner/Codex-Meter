package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.util.Log;
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ProtoLayoutScope;
import androidx.wear.protolayout.ResourceBuilders.ImageResource;
import androidx.wear.protolayout.ResourceBuilders.Resources;
import androidx.wear.protolayout.proto.ResourceProto;
import androidx.wear.protolayout.TimelineBuilders.Timeline;
import androidx.wear.tiles.RequestBuilders.ResourcesRequest;
import androidx.wear.tiles.RequestBuilders.TileRequest;
import androidx.wear.tiles.TileBuilders.Tile;
import androidx.wear.tiles.TileService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// ProtoLayout does not expose a public serializer, but exact resource bytes must survive a
// service restart so an older layout can always retrieve its matching rasterized text.
@SuppressLint("RestrictedApi")
abstract class CodexTileService extends TileService {
    private static final String TAG = "CodexTileService";
    private static final String RESOURCES_VERSION_PREFIX = "5-";
    private static final int MEMORY_CACHE_ENTRIES = 8;
    private static final int DISK_CACHE_ENTRIES = 32;
    private static final long FRESHNESS_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private final Map<String, Resources> resourceCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Resources>(MEMORY_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Resources> eldest) {
                    return size() > MEMORY_CACHE_ENTRIES;
                }
            });

    @Override
    protected final ListenableFuture<Tile> onTileRequest(TileRequest requestParams) {
        DeviceParameters deviceParameters = requestParams == null ? null
                : requestParams.getDeviceConfiguration();
        ProtoLayoutScope scope = new ProtoLayoutScope();
        LayoutElement root = tileLayout(deviceParameters, scope);
        Resources collected = scope.collectResources();
        String resourcesVersion = versionFor(collected);
        Resources resources = withVersion(collected, resourcesVersion);
        cache(resources);
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
        Resources persisted = readPersisted(requestedVersion);
        if (persisted != null) {
            resourceCache.put(requestedVersion, persisted);
            return Futures.immediateFuture(persisted);
        }

        ProtoLayoutScope scope = new ProtoLayoutScope();
        DeviceParameters parameters = requestParams == null ? null
                : requestParams.getDeviceConfiguration();
        tileLayout(parameters, scope);
        Resources collected = scope.collectResources();
        String version = versionFor(collected);
        Resources resources = withVersion(collected, version);
        cache(resources);
        if (!requestedVersion.isEmpty() && !requestedVersion.equals(version)) {
            Log.w(TAG, "Requested tile resource version is no longer available; "
                    + "requesting a fresh tile");
            TileService.getUpdater(this).requestUpdate(getClass().asSubclass(TileService.class));
        }
        return Futures.immediateFuture(resources);
    }

    private synchronized void cache(Resources resources) {
        resourceCache.put(resources.getVersion(), resources);
        File directory = resourceDirectory();
        if (!directory.exists() && !directory.mkdirs()) return;
        File target = new File(directory, safeFileName(resources.getVersion()));
        if (target.isFile()) {
            target.setLastModified(System.currentTimeMillis());
            return;
        }
        File temporary = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            resources.toProto().writeTo(output);
            output.getFD().sync();
            if (!temporary.renameTo(target)) {
                throw new IOException("Could not replace " + target.getName());
            }
            trimDiskCache(directory);
        } catch (IOException error) {
            Log.w(TAG, "Unable to persist tile resources", error);
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Unable to remove temporary tile resources");
            }
        }
    }

    private synchronized Resources readPersisted(String version) {
        if (version.isEmpty()) return null;
        File source = new File(resourceDirectory(), safeFileName(version));
        if (!source.isFile()) return null;
        try (FileInputStream input = new FileInputStream(source)) {
            Resources resources = Resources.fromProto(ResourceProto.Resources.parseFrom(input));
            if (!version.equals(resources.getVersion())) return null;
            source.setLastModified(System.currentTimeMillis());
            return resources;
        } catch (IOException error) {
            Log.w(TAG, "Unable to restore tile resources", error);
            return null;
        }
    }

    private File resourceDirectory() {
        return new File(new File(getFilesDir(), "tile_resources"), getClass().getSimpleName());
    }

    private static void trimDiskCache(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && !file.getName().endsWith(".tmp"));
        if (files == null || files.length <= DISK_CACHE_ENTRIES) return;
        Arrays.sort(files, (left, right) -> Long.compare(
                right.lastModified(), left.lastModified()));
        for (int i = DISK_CACHE_ENTRIES; i < files.length; i++) {
            if (!files[i].delete()) Log.w(TAG, "Unable to trim old tile resources");
        }
    }

    private static String safeFileName(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_") + ".pb";
    }

    private static String versionFor(Resources resources) {
        byte[] bytes = resources.toProto().toByteArray();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder version = new StringBuilder(RESOURCES_VERSION_PREFIX);
            for (int i = 0; i < 8; i++) {
                version.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            }
            return version.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return RESOURCES_VERSION_PREFIX + Integer.toHexString(Arrays.hashCode(bytes));
        }
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
