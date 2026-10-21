package dev.bennett.codexmeter;

import android.content.Intent;
import android.widget.LinearLayout;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Metric;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Shape;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Style;
import java.util.List;

/** "After": unsaved lock widgets follow signed-in providers; dials only; in-app setup list. */
final class Gallery {
    private static final UsageProvider CODEX = UsageProvider.CODEX;
    private static final UsageProvider CLAUDE = UsageProvider.CLAUDE_CODE;
    private static final UsageProvider CURSOR = UsageProvider.CURSOR;

    private Gallery() {
    }

    static void prepareConfig(WidgetGalleryActivity a, int id, String page) {
        ProviderPreferences.clearWidgetProvider(a, id);
    }

    static void openList(WidgetGalleryActivity a) {
        int gauges = a.bind(SamsungLockDialsWideWidget.class);
        a.bind(SamsungLockFiveHourWidget.class);
        ProviderPreferences.setWidgetProviders(a, gauges, WidgetGalleryActivity.list(CLAUDE, CURSOR));
        a.startActivity(new Intent(a, LockWidgetsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private static android.widget.RemoteViews chosen(WidgetGalleryActivity a, Shape shape,
            Metric metric, List<UsageProvider> providers) {
        return SamsungLockWidgetSupport.buildViews(a, null, 0, shape, Style.DIALS, metric,
                providers, LockWidgetOptions.defaults());
    }

    static void render(WidgetGalleryActivity a, String page) {
        if ("picked".equals(page)) {
            renderPicked(a);
            return;
        }
        boolean all = !"cursor-claude".equals(a.getIntent().getStringExtra("signedin"));
        a.heading(all ? "Signed in to Codex, Claude Code, Cursor"
                : "Signed in to Cursor and Claude Code (not Codex)");
        a.heading("Lock widgets as placed (never opened their settings)");
        LinearLayout r = a.row();
        a.lock(r, "Gauge", SamsungLockWidgetSupport.buildViews(a, null, 0, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR), 56, 56);
        r = a.row();
        a.lock(r, "Gauges", SamsungLockWidgetSupport.buildViews(a, null, 0, Shape.WIDE, Style.DIALS, Metric.BOTH), 124, 56);
        r = a.row();
        a.lock(r, "Old Bars tile (now draws dials)", SamsungLockWidgetSupport.buildViews(a, null, 0, Shape.WIDE, Style.BARS, Metric.BOTH), 124, 56);
    }

    static void renderPicked(WidgetGalleryActivity a) {
        a.heading("Picked in Settings → Lock screen widgets");
        LinearLayout r = a.row();
        a.lock(r, "Gauge · Cursor", chosen(a, Shape.SQUARE, Metric.FIVE_HOUR, WidgetGalleryActivity.list(CURSOR)), 56, 56);
        a.lock(r, "Gauge · Claude", chosen(a, Shape.SQUARE, Metric.FIVE_HOUR, WidgetGalleryActivity.list(CLAUDE)), 56, 56);
        r = a.row();
        a.lock(r, "Gauges · Cursor only", chosen(a, Shape.WIDE, Metric.BOTH, WidgetGalleryActivity.list(CURSOR)), 124, 56);
        r = a.row();
        a.lock(r, "Gauges · Codex + Cursor", chosen(a, Shape.WIDE, Metric.BOTH, WidgetGalleryActivity.list(CODEX, CURSOR)), 124, 56);
        r = a.row();
        a.lock(r, "Gauges · Claude Code only", chosen(a, Shape.WIDE, Metric.BOTH, WidgetGalleryActivity.list(CLAUDE)), 124, 56);
    }
}
