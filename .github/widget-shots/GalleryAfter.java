package dev.bennett.codexmeter;

import android.widget.LinearLayout;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Metric;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Shape;
import dev.bennett.codexmeter.SamsungLockWidgetSupport.Style;
import java.util.List;

/** "After": the three lock widgets with providers, and multi-provider home widgets. */
final class Gallery {
    private static final UsageProvider CODEX = UsageProvider.CODEX;
    private static final UsageProvider CLAUDE = UsageProvider.CLAUDE_CODE;
    private static final UsageProvider CURSOR = UsageProvider.CURSOR;

    private Gallery() {
    }

    static void prepareConfig(WidgetGalleryActivity a, int id, String page) {
        ProviderPreferences.setWidgetProviders(a, id, page.contains("lock")
                ? WidgetGalleryActivity.list(CODEX, CLAUDE)
                : WidgetGalleryActivity.list(CODEX, CLAUDE, CURSOR));
    }

    private static android.widget.RemoteViews lockViews(WidgetGalleryActivity a, Shape shape,
            Style style, Metric metric, List<UsageProvider> providers) {
        return SamsungLockWidgetSupport.buildViews(a, null, 0, shape, style, metric, providers,
                LockWidgetOptions.defaults());
    }

    static void render(WidgetGalleryActivity a, String page) {
        if ("lock".equals(page)) {
            a.heading("Lock screen picker now: 3 widgets, any provider");
            a.heading("Gauge (1×1): pick one provider");
            LinearLayout r = a.row();
            a.lock(r, "Codex", lockViews(a, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR, WidgetGalleryActivity.list(CODEX)), 56, 56);
            a.lock(r, "Claude Code", lockViews(a, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR, WidgetGalleryActivity.list(CLAUDE)), 56, 56);
            a.lock(r, "Cursor", lockViews(a, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR, WidgetGalleryActivity.list(CURSOR)), 56, 56);
            a.heading("Gauges (2×1): one provider, or two side by side");
            r = a.row();
            a.lock(r, "Codex", lockViews(a, Shape.WIDE, Style.DIALS, Metric.BOTH, WidgetGalleryActivity.list(CODEX)), 124, 56);
            a.lock(r, "Claude Code", lockViews(a, Shape.WIDE, Style.DIALS, Metric.BOTH, WidgetGalleryActivity.list(CLAUDE)), 124, 56);
            r = a.row();
            a.lock(r, "Codex + Claude Code", lockViews(a, Shape.WIDE, Style.DIALS, Metric.BOTH, WidgetGalleryActivity.list(CODEX, CLAUDE)), 124, 56);
            a.lock(r, "Claude Code + Cursor", lockViews(a, Shape.WIDE, Style.DIALS, Metric.BOTH, WidgetGalleryActivity.list(CLAUDE, CURSOR)), 124, 56);
            a.heading("Bars (2×1)");
            r = a.row();
            a.lock(r, "Codex", lockViews(a, Shape.WIDE, Style.BARS, Metric.BOTH, WidgetGalleryActivity.list(CODEX)), 124, 56);
            a.lock(r, "Codex + Claude Code", lockViews(a, Shape.WIDE, Style.BARS, Metric.BOTH, WidgetGalleryActivity.list(CODEX, CLAUDE)), 124, 56);
            return;
        }
        boolean dark = a.getIntent().getBooleanExtra("dark", false);
        WidgetOptions o = WidgetGalleryActivity.homeOptions(dark);
        if ("home-single".equals(page)) {
            for (UsageProvider p : new UsageProvider[]{CLAUDE, CURSOR}) {
                a.heading("Home widget, one provider: " + p.displayName);
                LinearLayout r = a.row();
                List<UsageProvider> ps = WidgetGalleryActivity.list(p);
                a.home(r, "2×1", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(150, 70), ps), 150, 70);
                a.home(r, "2×2", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(150, 156), ps), 150, 156);
                r = a.row();
                a.home(r, "4×2", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(330, 156), ps), 330, 156);
            }
            return;
        }
        List<UsageProvider> two = WidgetGalleryActivity.list(CODEX, CLAUDE);
        List<UsageProvider> three = WidgetGalleryActivity.list(CODEX, CLAUDE, CURSOR);
        a.heading("Home widget, several providers");
        LinearLayout r = a.row();
        a.home(r, "2×1 · Codex + Claude", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(150, 70), two), 150, 70);
        a.home(r, "2×2 · all three", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(150, 156), three), 150, 156);
        r = a.row();
        a.home(r, "4×1 · all three", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(330, 70), three), 330, 70);
        r = a.row();
        a.home(r, "4×2 · Codex + Claude (5h and weekly each)", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(330, 156), two), 330, 156);
        r = a.row();
        a.home(r, "4×2 · all three", WidgetRenderer.buildPreview(a, 0, o, WidgetGalleryActivity.size(330, 156), three), 330, 156);
    }
}
