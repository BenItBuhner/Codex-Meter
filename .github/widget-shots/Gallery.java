package dev.bennett.codexmeter;

import android.widget.LinearLayout;
import android.widget.RemoteViews;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Metric;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Shape;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Style;
import java.util.List;

/** Overlapped meters next to today's separate meters, lock and home widgets. */
final class Gallery {
    private static final UsageProvider CODEX = UsageProvider.CODEX;
    private static final UsageProvider CLAUDE = UsageProvider.CLAUDE_CODE;
    private static final UsageProvider CURSOR = UsageProvider.CURSOR;

    private Gallery() {
    }

    static void prepareConfig(WidgetGalleryActivity a, int id, String page) {
        AppPreferences.saveLockWidgetOptions(a, id,
                LockWidgetOptions.defaults().withOverlapMeters(true));
        AppPreferences.saveWidgetOptions(a, id,
                WidgetOptions.defaults().withOverlapMeters(true));
        ProviderPreferences.setWidgetProviders(a, id, WidgetGalleryActivity.list(CODEX, CLAUDE));
    }

    static void openList(WidgetGalleryActivity a) {
    }

    private static RemoteViews lock(WidgetGalleryActivity a, Shape shape, Metric metric,
            boolean overlap, UsageProvider... providers) {
        return SamsungLockWidgetSupport.buildViews(a, null, 0, shape, Style.DIALS, metric,
                WidgetGalleryActivity.list(providers),
                LockWidgetOptions.defaults().withOverlapMeters(overlap));
    }

    private static RemoteViews bars(WidgetGalleryActivity a, int[][] rows) {
        RemoteViews views = new RemoteViews(a.getPackageName(), R.layout.widget_lock_dial_single);
        views.setImageViewBitmap(R.id.lock_graphic_image,
                SamsungLockGraphics.renderBars(a, rows, 124, 56));
        return views;
    }

    private static int[] row(UsageProvider p, int five, int weekly) {
        return new int[]{ProvidersActivity.iconRes(p), five, weekly};
    }

    static void render(WidgetGalleryActivity a, String page) {
        if ("lock".equals(page)) {
            a.heading("Lock Gauge (1×1): today vs overlapped");
            LinearLayout r = a.row();
            a.lock(r, "Today · Codex 5h", lock(a, Shape.SQUARE, Metric.FIVE_HOUR, false, CODEX), 56, 56);
            a.lock(r, "Overlapped · Codex", lock(a, Shape.SQUARE, Metric.FIVE_HOUR, true, CODEX), 56, 56);
            a.lock(r, "Overlapped · Claude", lock(a, Shape.SQUARE, Metric.FIVE_HOUR, true, CLAUDE), 56, 56);
            a.heading("Lock Gauges (2×1)");
            r = a.row();
            a.lock(r, "Today · Codex + Claude (one meter each)", lock(a, Shape.WIDE, Metric.BOTH, false, CODEX, CLAUDE), 124, 56);
            r = a.row();
            a.lock(r, "Overlapped · Codex + Claude (both meters each)", lock(a, Shape.WIDE, Metric.BOTH, true, CODEX, CLAUDE), 124, 56);
            r = a.row();
            a.lock(r, "Overlapped · Codex only", lock(a, Shape.WIDE, Metric.BOTH, true, CODEX), 124, 56);
            return;
        }
        if ("lock-bars".equals(page)) {
            a.heading("Option: overlapped bars on the lock screen (2×1)");
            LinearLayout r = a.row();
            a.lock(r, "One provider", bars(a, new int[][]{row(CODEX, 63, 39)}), 124, 56);
            r = a.row();
            a.lock(r, "Two providers", bars(a, new int[][]{row(CODEX, 63, 39), row(CLAUDE, 82, 48)}), 124, 56);
            r = a.row();
            a.lock(r, "Three providers", bars(a, new int[][]{row(CODEX, 63, 39), row(CLAUDE, 82, 48), row(CURSOR, -1, 42)}), 124, 56);
            return;
        }
        boolean dark = a.getIntent().getBooleanExtra("dark", false);
        WidgetOptions plain = WidgetGalleryActivity.homeOptions(dark).withOverlapMeters(false);
        WidgetOptions over = plain.withOverlapMeters(true);
        List<UsageProvider> one = WidgetGalleryActivity.list(CODEX);
        List<UsageProvider> three = WidgetGalleryActivity.list(CODEX, CLAUDE, CURSOR);
        if ("home-bars".equals(page)) {
            a.heading("Home bars (2×2): today vs overlapped");
            LinearLayout r = a.row();
            a.home(r, "Today · Codex", WidgetRenderer.buildPreview(a, 0, plain.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(150, 156), one), 150, 156);
            a.home(r, "Overlapped · Codex", WidgetRenderer.buildPreview(a, 0, over.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(150, 156), one), 150, 156);
            r = a.row();
            a.home(r, "Today · 3 providers", WidgetRenderer.buildPreview(a, 0, plain, WidgetGalleryActivity.size(150, 156), three), 150, 156);
            a.home(r, "Overlapped · 3 providers", WidgetRenderer.buildPreview(a, 0, over, WidgetGalleryActivity.size(150, 156), three), 150, 156);
            r = a.row();
            a.home(r, "Overlapped 4×2 · 3 providers", WidgetRenderer.buildPreview(a, 0, over, WidgetGalleryActivity.size(330, 156), three), 330, 156);
            return;
        }
        a.heading("Home dials: today vs overlapped");
        LinearLayout r = a.row();
        a.home(r, "Today 4×1 · Codex", WidgetRenderer.buildPreview(a, 0, plain.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(330, 70), one), 330, 70);
        r = a.row();
        a.home(r, "Overlapped 4×1 · Codex", WidgetRenderer.buildPreview(a, 0, over.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(330, 70), one), 330, 70);
        r = a.row();
        a.home(r, "Today 4×1 · 3 providers", WidgetRenderer.buildPreview(a, 0, plain, WidgetGalleryActivity.size(330, 70), three), 330, 70);
        r = a.row();
        a.home(r, "Overlapped 4×1 · 3 providers", WidgetRenderer.buildPreview(a, 0, over, WidgetGalleryActivity.size(330, 70), three), 330, 70);
        r = a.row();
        a.home(r, "Overlapped 2×2 · Codex", WidgetRenderer.buildPreview(a, 0, over.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(150, 156), one), 150, 156);
    }
}
