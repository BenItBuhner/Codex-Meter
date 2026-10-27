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
        AppPreferences.saveWidgetOptions(a, id, WidgetOptions.defaults());
        ProviderPreferences.setWidgetProviders(a, id,
                WidgetGalleryActivity.list(CODEX, CLAUDE, CURSOR));
    }

    static void openList(WidgetGalleryActivity a) {
    }

    private static RemoteViews lock(WidgetGalleryActivity a, Shape shape, Metric metric,
            UsageProvider... providers) {
        return SamsungLockWidgetSupport.buildViews(a, null, 0, shape, Style.DIALS, metric,
                WidgetGalleryActivity.list(providers), LockWidgetOptions.defaults());
    }

    /** Saves a Codex snapshot with these remaining percentages (5-hour, weekly). */
    private static void codex(WidgetGalleryActivity a, int five, int week) {
        long now = System.currentTimeMillis();
        long s = now / 1000L;
        String json = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":true,"
                + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":" + (100 - five) + ","
                + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                + "{\"used_percent\":" + (100 - week) + ",\"limit_window_seconds\":604800,"
                + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}}}";
        try {
            AppPreferences.saveSnapshot(a, UsageParser.parse(json, now));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final int[][] EDGES = {{63, 39}, {50, 50}, {50, 47}, {20, 70}, {0, 45}, {100, 100}};
    private static final String[] EDGE_NAMES = {"Typical", "Same value", "Close (3 apart)",
            "5-hour lower than weekly", "5-hour used up", "Both full"};

    static void render(WidgetGalleryActivity a, String page) {
        if ("lock".equals(page)) {
            a.heading("Lock screen (already in alpha): weekly ring, 5-hour as a lighter inner ring");
            LinearLayout r = a.row();
            a.lock(r, "Gauge · Codex", lock(a, Shape.SQUARE, Metric.FIVE_HOUR, CODEX), 56, 56);
            a.lock(r, "Gauge · Claude", lock(a, Shape.SQUARE, Metric.FIVE_HOUR, CLAUDE), 56, 56);
            r = a.row();
            a.lock(r, "Gauges · Codex + Claude", lock(a, Shape.WIDE, Metric.BOTH, CODEX, CLAUDE), 124, 56);
            return;
        }
        boolean dark = a.getIntent().getBooleanExtra("dark", false);
        WidgetOptions plain = WidgetGalleryActivity.homeOptions(dark).withOverlapMeters(false);
        WidgetOptions over = plain.withOverlapMeters(true);
        List<UsageProvider> one = WidgetGalleryActivity.list(CODEX);
        List<UsageProvider> three = WidgetGalleryActivity.list(CODEX, CLAUDE, CURSOR);
        if ("edge-bars".equals(page) || "edge-dials".equals(page)) {
            boolean bars = "edge-bars".equals(page);
            a.heading(bars ? "Overlapped bars: edge cases (5-hour · weekly)"
                    : "Overlapped dials: edge cases (5-hour · weekly)");
            LinearLayout r = null;
            for (int i = 0; i < EDGES.length; i++) {
                codex(a, EDGES[i][0], EDGES[i][1]);
                String label = EDGE_NAMES[i] + " · " + EDGES[i][0] + " · " + EDGES[i][1];
                if (bars) {
                    if (i % 2 == 0) r = a.row();
                    a.home(r, label, WidgetRenderer.buildPreview(a, 0, over.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(150, 156), one), 150, 156);
                } else {
                    r = a.row();
                    a.home(r, label, WidgetRenderer.buildPreview(a, 0, over.withVisibleMeters("five_hour,weekly"), WidgetGalleryActivity.size(330, 70), one), 330, 70);
                }
            }
            return;
        }
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
