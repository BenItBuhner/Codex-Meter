package dev.bennett.codexmeter;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TileUpdateRequester;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

public final class WearSurfaceUpdater {
    private static final String TAG = "CodexWearSurfaces";

    @SuppressWarnings("unchecked")
    private static final Class<? extends TileService>[] TILE_SERVICES = new Class[] {
            UsageOverviewTileService.class,
            FiveHourTileService.class,
            WeeklyTileService.class,
            ResetCountdownTileService.class,
            MonitorStatusTileService.class
    };

    @SuppressWarnings("unchecked")
    private static final Class<? extends ComplicationDataSourceService>[] COMPLICATION_SERVICES =
            new Class[] {
                    FiveHourComplicationService.class,
                    WeeklyComplicationService.class,
                    DualUsageComplicationService.class,
                    NextResetComplicationService.class
            };

    private WearSurfaceUpdater() {
    }

    public static void requestAll(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        requestTileUpdates(app);
        requestComplicationUpdates(app);
    }

    private static void requestTileUpdates(Context context) {
        TileUpdateRequester updater = TileService.getUpdater(context);
        for (Class<? extends TileService> service : TILE_SERVICES) {
            try {
                updater.requestUpdate(service);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not request tile update for " + service.getSimpleName(),
                        exception);
            }
        }
    }

    private static void requestComplicationUpdates(Context context) {
        for (Class<? extends ComplicationDataSourceService> service : COMPLICATION_SERVICES) {
            try {
                ComplicationDataSourceUpdateRequester.create(context,
                        new ComponentName(context, service)).requestUpdateAll();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not request complication update for "
                        + service.getSimpleName(), exception);
            }
        }
    }
}
