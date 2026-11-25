package dev.bennett.codexmeter;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Debug-only: hosts real Codex Meter home-screen widgets the way a launcher does (an
 * AppWidgetHost and its AppWidgetHostViews, with Android 12+'s corner enforcement), one light and
 * one dark, over a flat backdrop, so a device check can zoom into the corners of the widget's
 * glass. Needs the bind permission first: {@code adb shell appwidget grantbind --package
 * dev.bennett.codexmeter}. Logs each widget's on-screen rectangle as
 * {@code WidgetHostDemo rect <theme> x y w h}.
 */
public final class WidgetHostDemoActivity extends Activity {
    private static final String TAG = "WidgetHostDemo";
    private static final int HOST_ID = 4242;
    private AppWidgetHost host;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String backdrop = getIntent().getStringExtra("backdrop");
        int opacity = getIntent().getIntExtra("opacity", 88);
        int wDp = getIntent().getIntExtra("width_dp", 330);
        int hDp = getIntent().getIntExtra("height_dp", 170);
        float density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(backdrop == null ? Color.rgb(236, 178, 64) : Color.parseColor(backdrop));
        setContentView(root);
        host = new AppWidgetHost(this, HOST_ID);
        host.deleteHost();
        host.startListening();
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, CodexUsageWidget.class);
        String[] themes = {WidgetOptions.THEME_LIGHT, WidgetOptions.THEME_DARK};
        for (int i = 0; i < themes.length; i++) {
            int id = host.allocateAppWidgetId();
            Bundle size = new Bundle();
            size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp);
            size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp);
            size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp);
            size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp);
            if (!manager.bindAppWidgetIdIfAllowed(id, provider, size)) {
                Log.w(TAG, "bind refused: run adb shell appwidget grantbind --package " + getPackageName());
                return;
            }
            WidgetOptions d = AppPreferences.loadDefaultWidgetOptions(this);
            AppPreferences.saveWidgetOptions(this, id, new WidgetOptions(d.layout, d.density,
                    d.surfaceStyle, d.graphicScale, themes[i], d.accent, opacity, d.resetMode,
                    d.displayMode, d.metricMode, d.showTitle, d.showPlan, d.showUpdated,
                    d.showRefresh, d.showResetCredits, d.showResetAction));
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            AppWidgetHostView view = host.createView(getApplicationContext(), id, info);
            view.setPadding(0, 0, 0, 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.round(wDp * density), Math.round(hDp * density));
            lp.leftMargin = Math.round(24 * density);
            lp.topMargin = Math.round((120 + i * (hDp + 40)) * density);
            root.addView(view, lp);
            String theme = themes[i];
            view.addOnLayoutChangeListener((View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) -> {
                int[] at = new int[2];
                v.getLocationOnScreen(at);
                Log.i(TAG, "rect " + theme + " " + at[0] + " " + at[1] + " " + v.getWidth() + " " + v.getHeight());
            });
            WidgetRenderer.update(this, manager, id);
        }
    }

    @Override
    protected void onDestroy() {
        if (host != null) {
            host.stopListening();
        }
        super.onDestroy();
    }
}
