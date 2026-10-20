package dev.bennett.codexmeter;

import android.widget.LinearLayout;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Metric;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Shape;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Style;

/** "Before": every lock widget in the picker today, and Codex home widgets. */
final class Gallery {
    private Gallery() {
    }

    static void prepareConfig(WidgetGalleryActivity a, int id, String page) {
    }

    static void render(WidgetGalleryActivity a, String page) {
        if ("lock".equals(page)) {
            Shape SQ = Shape.SQUARE, WI = Shape.WIDE;
            a.heading("Lock screen picker today: 10 widgets, Codex only");
            LinearLayout r = a.row();
            a.lock(r, "Numbers · Square", SamsungLockWidgetSupport.buildViews(a, SQ, Style.NUMBERS), 56, 56);
            a.lock(r, "Rings · Square", SamsungLockWidgetSupport.buildViews(a, SQ, Style.RINGS), 56, 56);
            a.lock(r, "Gauges · Square", SamsungLockWidgetSupport.buildViews(a, SQ, Style.DIALS), 56, 56);
            r = a.row();
            a.lock(r, "Bars · Square", SamsungLockWidgetSupport.buildViews(a, SQ, Style.BARS), 56, 56);
            a.lock(r, "5-hour", SamsungLockWidgetSupport.buildViews(a, null, 0, SQ, Style.DIALS, Metric.FIVE_HOUR), 56, 56);
            a.lock(r, "Weekly", SamsungLockWidgetSupport.buildViews(a, null, 0, SQ, Style.DIALS, Metric.WEEKLY), 56, 56);
            r = a.row();
            a.lock(r, "Numbers · Wide", SamsungLockWidgetSupport.buildViews(a, WI, Style.NUMBERS), 124, 56);
            a.lock(r, "Rings · Wide", SamsungLockWidgetSupport.buildViews(a, WI, Style.RINGS), 124, 56);
            r = a.row();
            a.lock(r, "Gauges · Wide", SamsungLockWidgetSupport.buildViews(a, WI, Style.DIALS), 124, 56);
            a.lock(r, "Bars · Wide", SamsungLockWidgetSupport.buildViews(a, WI, Style.BARS), 124, 56);
            return;
        }
        boolean dark = a.getIntent().getBooleanExtra("dark", false);
        WidgetOptions o = WidgetGalleryActivity.homeOptions(dark);
        for (UsageProvider p : new UsageProvider[]{UsageProvider.CODEX, UsageProvider.CLAUDE_CODE}) {
            a.heading("Home widget today: one provider (" + p.displayName + ")");
            LinearLayout r = a.row();
            a.home(r, "2×1", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(150, 70), p), 150, 70);
            a.home(r, "2×2", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(150, 156), p), 150, 156);
            r = a.row();
            a.home(r, "4×1", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(330, 70), p), 330, 70);
            r = a.row();
            a.home(r, "4×2", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(330, 156), p), 330, 156);
        }
    }
}
