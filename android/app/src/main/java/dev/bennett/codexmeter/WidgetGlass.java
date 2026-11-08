package dev.bennett.codexmeter;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.RemoteViews;
import com.benitbuhner.glasse.android.outside.SkBitmaps;
import dev.bennett.codexmeter.glass.GlassWidgetArt;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home-screen widgets in the Liquid Glass design sit on Glassé's glass (GlassWidgetArt). The
 * glass is a bitmap the widget's size, baked off the main thread and kept until the widget's size,
 * theme or background strength changes, so a usage refresh only reuses it. Until a widget's glass is
 * ready it draws as before and is updated again once the glass is baked.
 */
final class WidgetGlass {
    private WidgetGlass() {}

    private static final String TAG = "CodexMeterWidget";
    private static final ExecutorService BAKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "widget-glass");
        t.setDaemon(true);
        return t;
    });
    /** Baked glass by key, a few widgets' worth. */
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 8;
        }
    };
    private static final Set<String> PENDING = new HashSet<>();

    /** True when widget {@code options} draw on glass: the Liquid Glass design, with a background. */
    static boolean applies(Context context, WidgetOptions options) {
        return options.opacity > 0 && AppPreferences.isLiquidGlassDesign(context);
    }

    /**
     * The glass for widget {@code id} if it is baked; otherwise null, and it is baked in the
     * background and the widget updated when it is ready.
     */
    static Bitmap forWidget(Context context, AppWidgetManager manager, int id, WidgetOptions options,
            boolean dark) {
        if (!applies(context, options)) {
            return null;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        float density = app.getResources().getDisplayMetrics().density;
        int[] dp = sizeDp(app, manager.getAppWidgetOptions(id));
        int w = Math.round(dp[0] * density);
        int h = Math.round(dp[1] * density);
        String key = id + "|" + w + "x" + h + "|" + dark + "|" + options.opacity;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
            if (!PENDING.add(key)) {
                return null;
            }
        }
        BAKER.execute(() -> {
            try {
                long t0 = System.nanoTime();
                Bitmap bmp = SkBitmaps.toAndroid(
                        GlassWidgetArt.render(null, 0, 0, w, h, density, dark, options.opacity), null);
                synchronized (CACHE) {
                    CACHE.put(key, bmp);
                }
                Log.i(TAG, "glass for widget " + id + " " + w + "x" + h + " in "
                        + (System.nanoTime() - t0) / 1_000_000 + " ms");
                WidgetRenderer.update(app, manager, id);
            } catch (Throwable e) {
                DiagnosticLog.info(app, "widget", "glass_failed", "widget_id", id,
                        "error", e.getClass().getSimpleName());
                Log.w(TAG, "Widget glass failed", e);
            } finally {
                synchronized (CACHE) {
                    PENDING.remove(key);
                }
            }
        });
        return null;
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The glass for a widget preview of {@code w} x {@code h} px drawn at ({@code x}, {@code y})
     * over {@code backdrop}, which the glass refracts and blurs. Null until baked; then
     * {@code ready} runs on the main thread. The backdrop is the same for every preview, so the
     * glass is kept by size, theme and background strength.
     */
    static Bitmap forPreview(Bitmap backdrop, int x, int y, int w, int h, float density, boolean dark,
            int opacity, Runnable ready) {
        if (opacity <= 0) {
            return null;
        }
        String key = "preview|" + backdrop.getWidth() + "x" + backdrop.getHeight() + "|" + x + "," + y
                + "|" + w + "x" + h + "|" + dark + "|" + opacity;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
            if (!PENDING.add(key)) {
                return null;
            }
        }
        Bitmap copy = backdrop.copy(Bitmap.Config.ARGB_8888, false);
        BAKER.execute(() -> {
            try {
                Bitmap bmp = SkBitmaps.toAndroid(GlassWidgetArt.render(SkBitmaps.toSkia(copy), x, y, w, h,
                        density, dark, opacity), null);
                synchronized (CACHE) {
                    CACHE.put(key, bmp);
                }
                MAIN.post(ready);
            } catch (Throwable e) {
                Log.w(TAG, "Widget preview glass failed", e);
            } finally {
                synchronized (CACHE) {
                    PENDING.remove(key);
                }
            }
        });
        return null;
    }

    /** {@code content} (a widget layout) on {@code glass}. */
    static RemoteViews wrap(Context context, RemoteViews content, Bitmap glass) {
        RemoteViews frame = new RemoteViews(context.getPackageName(), R.layout.widget_glass_frame);
        frame.setImageViewBitmap(R.id.widget_glass, glass);
        content.setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT);
        frame.removeAllViews(R.id.widget_glass_content);
        frame.addView(R.id.widget_glass_content, content);
        return frame;
    }

    /** The widget's size in dp now, as launchers report it (portrait: min width, max height). */
    static int[] sizeDp(Context context, Bundle options) {
        boolean portrait = context.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;
        int w = options == null ? 0 : options.getInt(portrait
                ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int h = options == null ? 0 : options.getInt(portrait
                ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        return new int[] {Math.max(w, 110), Math.max(h, 60)};
    }
}
