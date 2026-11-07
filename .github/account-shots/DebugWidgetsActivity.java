package dev.bennett.codexmeter;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.util.Collections;

/**
 * Debug-only (temporary screenshot job): draws real Codex Meter widgets the way a home screen
 * or lock screen would, side by side: every Claude Code account combined, and one account.
 * Extra: mode (home|lock).
 */
public class DebugWidgetsActivity extends Activity {
    private static final int HOME_ALL = 900_001;
    private static final int HOME_ONE = 900_002;
    private static final int HOME_ALL_SMALL = 900_003;
    private static final int HOME_ONE_SMALL = 900_004;
    private static final int LOCK_ALL = 900_011;
    private static final int LOCK_ONE = 900_012;
    private static final String ONE = "sam.lee@gmail.com";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean lock = "lock".equals(getIntent().getStringExtra("mode"));
        for (int id : new int[]{HOME_ALL, HOME_ONE, HOME_ALL_SMALL, HOME_ONE_SMALL, LOCK_ALL,
                LOCK_ONE}) {
            ProviderPreferences.setWidgetProviders(this, id,
                    Collections.singletonList(UsageProvider.CLAUDE_CODE));
        }
        AccountVault.setWidgetScope(this, HOME_ALL, AccountVault.SCOPE_ALL);
        AccountVault.setWidgetScope(this, HOME_ALL_SMALL, AccountVault.SCOPE_ALL);
        AccountVault.setWidgetScope(this, LOCK_ALL, AccountVault.SCOPE_ALL);
        AccountVault.setWidgetScope(this, HOME_ONE, ONE);
        AccountVault.setWidgetScope(this, HOME_ONE_SMALL, ONE);
        AccountVault.setWidgetScope(this, LOCK_ONE, ONE);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable wallpaper = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                lock ? new int[]{Color.rgb(18, 24, 48), Color.rgb(52, 30, 74),
                        Color.rgb(12, 12, 20)}
                        : new int[]{Color.rgb(120, 162, 236), Color.rgb(176, 140, 214),
                                Color.rgb(240, 182, 168)});
        root.setBackground(wallpaper);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        column.setPadding(pad, dp(64), pad, pad);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        if (lock) {
            TextView clock = text("9:41", 84, true);
            clock.setGravity(Gravity.CENTER);
            column.addView(clock, new LinearLayout.LayoutParams(-1, -2));
            TextView date = text("Tuesday, October 27", 18, false);
            date.setGravity(Gravity.CENTER);
            column.addView(date, new LinearLayout.LayoutParams(-1, -2));
            space(column, 36);
            addLock(column, LOCK_ALL, "All 3 accounts combined");
            space(column, 22);
            addLock(column, LOCK_ONE, "Only sam.lee@gmail.com");
        } else {
            addHome(column, HOME_ALL, 4, 2, "All 3 accounts combined");
            space(column, 18);
            addHome(column, HOME_ONE, 4, 2, "Only sam.lee@gmail.com");
            space(column, 18);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            addHome(left, HOME_ALL_SMALL, 2, 2, "All accounts");
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            addHome(right, HOME_ONE_SMALL, 2, 2, "Only sam.lee");
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
            row.addView(left, half);
            LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1f);
            half2.setMarginStart(dp(16));
            row.addView(right, half2);
            column.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(root);
    }

    private void addHome(LinearLayout parent, int id, int columns, int rows, String caption) {
        Bundle size = new Bundle();
        size.putInt("semAppWidgetColumnSpan", columns);
        size.putInt("semAppWidgetRowSpan", rows);
        size.putInt("appWidgetMinWidth", columns * 90);
        size.putInt("appWidgetMinHeight", rows * 80);
        size.putInt("appWidgetMaxWidth", columns * 100);
        size.putInt("appWidgetMaxHeight", rows * 95);
        WidgetOptions options = AppPreferences.loadWidgetOptions(this, id);
        RemoteViews views = WidgetRenderer.buildPreview(this, id, options, size);
        FrameLayout surface = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable();
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int alpha = Math.round(options.opacity * 2.55f);
        background.setColor(dark ? Color.argb(alpha, 0, 0, 0)
                : Color.argb(alpha, 255, 255, 255));
        background.setCornerRadius(dp(28));
        surface.setBackground(background);
        surface.setClipToOutline(true);
        View widget = views.apply(getApplicationContext(), surface);
        surface.addView(widget, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(surface, new LinearLayout.LayoutParams(-1, dp(rows == 2 ? 170 : 90)));
        caption(parent, caption);
    }

    private void addLock(LinearLayout parent, int id, String caption) {
        RemoteViews views = SamsungLockWidgetSupport.buildViews(this,
                AppWidgetManager.getInstance(this), id, SamsungLockWidgetSupport.Shape.WIDE,
                SamsungLockWidgetSupport.Style.DIALS, SamsungLockWidgetSupport.Metric.BOTH);
        FrameLayout surface = new FrameLayout(this);
        View widget = views.apply(getApplicationContext(), surface);
        surface.addView(widget, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(300), dp(120));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        parent.addView(surface, params);
        caption(parent, caption);
    }

    private void caption(LinearLayout parent, String text) {
        TextView label = text(text, 13, true);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.argb(110, 0, 0, 0));
        pill.setCornerRadius(dp(14));
        label.setBackground(pill);
        label.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(8);
        parent.addView(label, params);
    }

    private TextView text(String value, float sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.WHITE);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private void space(ViewGroup parent, int heightDp) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
