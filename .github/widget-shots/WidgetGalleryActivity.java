package dev.bennett.codexmeter;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Temporary screenshot gallery: renders widget RemoteViews with sample data. */
public class WidgetGalleryActivity extends Activity {
    private LinearLayout column;
    private boolean dark;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void seed() throws Exception {
        long now = System.currentTimeMillis();
        String who = getIntent().getStringExtra("signedin");
        boolean all = who == null || "all".equals(who);
        ProviderPreferences.setEnabledProviders(this, list(UsageProvider.CODEX,
                UsageProvider.CURSOR, UsageProvider.CLAUDE_CODE));
        ProviderPreferences.setOrder(this, list(UsageProvider.CODEX, UsageProvider.CURSOR,
                UsageProvider.CLAUDE_CODE));
        long s = now / 1000L;
        SecureTokenStore.save(this, UsageProvider.CODEX, new AuthTokens("demo", "demo", "",
                now + 30L * 86400000L, "acct", "demo@example.com"));
        String codex = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":true,"
                + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":37,"
                + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                + "{\"used_percent\":61,\"limit_window_seconds\":604800,"
                + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}}}";
        AppPreferences.saveSnapshot(this, UsageParser.parse(codex, now));
        if (!all) {
            SecureTokenStore.clear(this, UsageProvider.CODEX);
        }
        SecureTokenStore.save(this, UsageProvider.CLAUDE_CODE, new AuthTokens("demo", "demo",
                "", now + 30L * 86400000L, "", "dev@example.com"));
        String iso5 = java.time.Instant.ofEpochSecond(s + 7200).toString();
        String iso7 = java.time.Instant.ofEpochSecond(s + 300000).toString();
        String claude = "{\"five_hour\":{\"utilization\":18.0,\"resets_at\":\"" + iso5 + "\"},"
                + "\"seven_day\":{\"utilization\":52.0,\"resets_at\":\"" + iso7 + "\"}}";
        ProviderPreferences.saveSnapshot(this, UsageProvider.CLAUDE_CODE,
                ClaudeUsageParser.parse(claude, "Max 5x", now));
        SecureTokenStore.save(this, UsageProvider.CURSOR, new AuthTokens("demo", "demo", "",
                now + 50L * 86400000L, "", "dev@example.com"));
        long start = (s - 18L * 86400L) * 1000L;
        long end = (s + 12L * 86400L) * 1000L;
        String cursor = "{\"billingCycleStart\":\"" + start + "\",\"billingCycleEnd\":\"" + end
                + "\",\"planUsage\":{\"totalSpend\":1160,\"includedSpend\":1160,"
                + "\"remaining\":840,\"limit\":2000,\"totalPercentUsed\":58,"
                + "\"autoPercentUsed\":34,\"apiPercentUsed\":71},\"enabled\":true}";
        ProviderPreferences.saveSnapshot(this, UsageProvider.CURSOR,
                CursorUsageParser.parse(cursor, "Pro", now));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String page = getIntent().getStringExtra("page");
        this.dark = getIntent().getBooleanExtra("dark", false);
        try {
            seed();
        } catch (Exception e) {
            Log.e("WidgetGallery", "seed failed", e);
        }
        if (page != null && page.startsWith("config")) {
            openConfig(page);
            return;
        }
        if (page != null && page.startsWith("list")) {
            Gallery.openList(this);
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(this.dark ? 0xFF101114 : 0xFFF2F2F6);
        this.column = new LinearLayout(this);
        this.column.setOrientation(LinearLayout.VERTICAL);
        this.column.setPadding(dp(16), dp(36), dp(16), dp(24));
        scroll.addView(this.column);
        setContentView(scroll);
        try {
            Gallery.render(this, page == null ? "lock" : page);
        } catch (RuntimeException e) {
            Log.e("WidgetGallery", "render failed", e);
            heading("Render failed: " + e);
        }
    }

    /** Binds a real widget id for {@code component}; needs `appwidget grantbind`. */
    int bind(Class<?> component) {
        AppWidgetHost host = new AppWidgetHost(this, 4242);
        int id = host.allocateAppWidgetId();
        boolean bound = AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id,
                new ComponentName(this, component));
        Log.i("WidgetGallery", "bound " + component + " -> " + bound);
        return id;
    }

    /** Binds a real widget id (needs `appwidget grantbind`) and opens its settings screen. */
    private void openConfig(String page) {
        try {
            AppWidgetHost host = new AppWidgetHost(this, 4242);
            int id = host.allocateAppWidgetId();
            boolean lock = page.contains("lock");
            ComponentName component = new ComponentName(this, lock
                    ? SamsungLockDialsWideWidget.class : CodexUsageWidget.class);
            boolean bound = AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id,
                    component);
            Log.i("WidgetGallery", "bound " + component + " -> " + bound);
            Gallery.prepareConfig(this, id, page);
            Intent intent = new Intent(this, lock ? LockWidgetConfigActivity.class
                    : WidgetConfigActivity.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.e("WidgetGallery", "config failed", e);
        }
        finish();
    }

    /** Diagnostic: progress values and drawable identity for every bar in a widget. */
    private static void logBars(String caption, View v) {
        if (v instanceof android.widget.ProgressBar) {
            android.widget.ProgressBar bar = (android.widget.ProgressBar) v;
            android.graphics.drawable.Drawable d = bar.getProgressDrawable();
            String layers = "";
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                android.graphics.drawable.Drawable p = ld.findDrawableByLayerId(android.R.id.progress);
                android.graphics.drawable.Drawable sp = ld.findDrawableByLayerId(android.R.id.secondaryProgress);
                layers = " p@" + System.identityHashCode(p) + " lvl=" + (p == null ? -1 : p.getLevel())
                        + " s@" + System.identityHashCode(sp) + " lvl=" + (sp == null ? -1 : sp.getLevel());
            }
            Log.i("WidgetGallery", "bar [" + caption + "] id=" + v.getId() + " progress="
                    + bar.getProgress() + " secondary=" + bar.getSecondaryProgress()
                    + " d@" + System.identityHashCode(d) + layers);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) logBars(caption, g.getChildAt(i));
        }
    }

    void heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17);
        view.setTextColor(this.dark ? Color.WHITE : 0xFF111111);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(14), 0, dp(6));
        this.column.addView(view);
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        this.column.addView(row);
        return row;
    }

    /** A home widget at w×h dp on a wallpaper-like card. */
    void home(LinearLayout row, String caption, RemoteViews views, int w, int h) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(this.dark ? 0xE0000000 : 0xE0FFFFFF);
        bg.setCornerRadius(dp(26));
        add(row, caption, views, w, h, bg, 1.0f);
    }

    /** A monotone lock widget at w×h dp, enlarged, on a light tile (hosts tint it). */
    void lock(LinearLayout row, String caption, RemoteViews views, int w, int h) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFDDE3EA);
        bg.setCornerRadius(dp(14));
        add(row, caption, views, w, h, bg, 1.7f);
    }

    private void add(LinearLayout row, String caption, RemoteViews views, int w, int h,
            GradientDrawable bg, float scale) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(0, 0, dp(12), 0);
        FrameLayout outer = new FrameLayout(this);
        outer.setBackground(bg);
        FrameLayout frame = new FrameLayout(this);
        try {
            View widget = views.apply(getApplicationContext(), frame);
            frame.addView(widget, new FrameLayout.LayoutParams(-1, -1));
            final View root = widget;
            root.postDelayed(() -> logBars(caption, root), 2500);
        } catch (RuntimeException e) {
            Log.e("WidgetGallery", "apply failed: " + caption, e);
            TextView error = new TextView(this);
            error.setText("apply failed");
            frame.addView(error);
        }
        frame.setPivotX(0);
        frame.setPivotY(0);
        frame.setScaleX(scale);
        frame.setScaleY(scale);
        outer.addView(frame, new FrameLayout.LayoutParams(dp(w), dp(h)));
        cell.addView(outer, new LinearLayout.LayoutParams(Math.round(dp(w) * scale),
                Math.round(dp(h) * scale)));
        TextView label = new TextView(this);
        label.setText(caption);
        label.setTextSize(11);
        label.setTextColor(this.dark ? 0xFFBBBBBB : 0xFF555555);
        label.setPadding(0, dp(4), 0, 0);
        cell.addView(label);
        row.addView(cell);
    }

    static Bundle size(int w, int h) {
        Bundle bundle = new Bundle();
        bundle.putInt("appWidgetMinWidth", w);
        bundle.putInt("appWidgetMaxWidth", w);
        bundle.putInt("appWidgetMinHeight", h);
        bundle.putInt("appWidgetMaxHeight", h);
        return bundle;
    }

    static WidgetOptions homeOptions(boolean dark) {
        WidgetOptions d = WidgetOptions.defaults();
        return new WidgetOptions(d.layout, d.density, d.surfaceStyle, d.graphicScale,
                dark ? WidgetOptions.THEME_DARK : WidgetOptions.THEME_LIGHT, d.accent, d.opacity,
                d.resetMode, d.displayMode, d.metricMode, d.showTitle, d.showPlan, d.showUpdated,
                d.showRefresh, d.showResetCredits, d.showResetAction)
                .withVisibleMeters(d.visibleMeters);
    }

    static List<UsageProvider> list(UsageProvider... providers) {
        return new ArrayList<>(Arrays.asList(providers));
    }
}
