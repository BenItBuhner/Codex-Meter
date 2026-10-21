package dev.bennett.codexmeter;

import android.widget.LinearLayout;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Metric;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Shape;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Style;

/** "Before" (alpha): lock widgets that were never set up, as placed on a phone. */
final class Gallery {
    private Gallery() {
    }

    static void prepareConfig(WidgetGalleryActivity a, int id, String page) {
    }

    static void openList(WidgetGalleryActivity a) {
    }

    static void render(WidgetGalleryActivity a, String page) {
        boolean all = !"cursor-claude".equals(a.getIntent().getStringExtra("signedin"));
        a.heading(all ? "Signed in to Codex, Claude Code, Cursor"
                : "Signed in to Cursor and Claude Code (not Codex)");
        a.heading("Lock widgets as placed (never opened their settings)");
        LinearLayout r = a.row();
        a.lock(r, "Gauge", SamsungLockWidgetSupport.buildViews(a, null, 0, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR), 56, 56);
        r = a.row();
        a.lock(r, "Gauges", SamsungLockWidgetSupport.buildViews(a, null, 0, Shape.WIDE, Style.DIALS, Metric.BOTH), 124, 56);
        r = a.row();
        a.lock(r, "Bars", SamsungLockWidgetSupport.buildViews(a, null, 0, Shape.WIDE, Style.BARS, Metric.BOTH), 124, 56);
    }
}
