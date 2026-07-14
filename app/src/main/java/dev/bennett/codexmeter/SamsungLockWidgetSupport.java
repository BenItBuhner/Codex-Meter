package dev.bennett.codexmeter;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
final class SamsungLockWidgetSupport {
    private static final ProviderSpec[] PROVIDERS = {
            new ProviderSpec(SamsungLockSquareWidget.class, Shape.SQUARE, Style.NUMBERS, Metric.BOTH),
            new ProviderSpec(SamsungLockWideWidget.class, Shape.WIDE, Style.NUMBERS, Metric.BOTH),
            new ProviderSpec(SamsungLockRingsSquareWidget.class, Shape.SQUARE, Style.RINGS, Metric.BOTH),
            new ProviderSpec(SamsungLockRingsWideWidget.class, Shape.WIDE, Style.RINGS, Metric.BOTH),
            new ProviderSpec(SamsungLockDialsSquareWidget.class, Shape.SQUARE, Style.DIALS, Metric.BOTH),
            new ProviderSpec(SamsungLockDialsWideWidget.class, Shape.WIDE, Style.DIALS, Metric.BOTH),
            new ProviderSpec(SamsungLockBarsSquareWidget.class, Shape.SQUARE, Style.BARS, Metric.BOTH),
            new ProviderSpec(SamsungLockBarsWideWidget.class, Shape.WIDE, Style.BARS, Metric.BOTH),
            new ProviderSpec(SamsungLockFiveHourWidget.class, Shape.SQUARE, Style.DIALS, Metric.FIVE_HOUR),
            new ProviderSpec(SamsungLockWeeklyWidget.class, Shape.SQUARE, Style.DIALS, Metric.WEEKLY)
    };

    enum Shape {
        WIDE,
        SQUARE
    }

    enum Style {
        NUMBERS,
        RINGS,
        DIALS,
        BARS
    }

    enum Metric {
        BOTH,
        FIVE_HOUR,
        WEEKLY
    }

    static final class LockInstance {
        final int appWidgetId;
        final Shape shape;
        final Style style;
        final Metric metric;

        LockInstance(int i, Shape shape, Style style, Metric metric) {
            this.appWidgetId = i;
            this.shape = shape;
            this.style = style;
            this.metric = metric;
        }

        String label() {
            if (this.metric == Metric.FIVE_HOUR) {
                return "5-hour · Tiny";
            }
            if (this.metric == Metric.WEEKLY) {
                return "Weekly · Tiny";
            }
            return SamsungLockWidgetSupport.styleLabel(this.style) + " · " + (this.shape == Shape.SQUARE ? "Square" : "Wide");
        }
    }

    private static final class ProviderSpec {
        final Class<?> provider;
        final Shape shape;
        final Style style;
        final Metric metric;

        ProviderSpec(Class<?> cls, Shape shape, Style style, Metric metric) {
            this.provider = cls;
            this.shape = shape;
            this.style = style;
            this.metric = metric;
        }
    }

    private SamsungLockWidgetSupport() {
    }

    static void updateAll(Context context) {
        if (context != null) {
            Context contextApplication = application(context);
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(contextApplication);
            for (ProviderSpec providerSpec : PROVIDERS) {
                updateComponent(contextApplication, appWidgetManager, providerSpec.provider, providerSpec.shape, providerSpec.style, providerSpec.metric);
            }
        }
    }

    static void updateById(Context context, int i) {
        Context contextApplication;
        AppWidgetManager appWidgetManager;
        ProviderSpec providerSpecFindSpec;
        if (context != null && i != 0 && (providerSpecFindSpec = findSpec((appWidgetManager = AppWidgetManager.getInstance((contextApplication = application(context)))), contextApplication, i)) != null) {
            update(contextApplication, appWidgetManager, i, providerSpecFindSpec.shape, providerSpecFindSpec.style, providerSpecFindSpec.metric);
        }
    }

    static int countAll(Context context) {
        return placedWidgets(context).size();
    }

    static void enableAllProviders(Context context) {
        Context app = application(context);
        PackageManager packageManager = app.getPackageManager();
        for (ProviderSpec providerSpec : PROVIDERS) {
            try {
                packageManager.setComponentEnabledSetting(
                        new ComponentName(app, providerSpec.provider),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (RuntimeException exception) {
                Log.w("CodexMeterLock", "Unable to re-enable a lock widget provider", exception);
            }
        }
    }

    static List<LockInstance> placedWidgets(Context context) {
        ArrayList arrayList = new ArrayList();
        if (context != null) {
            Context contextApplication = application(context);
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(contextApplication);
            for (ProviderSpec providerSpec : PROVIDERS) {
                try {
                    for (int i : appWidgetManager.getAppWidgetIds(new ComponentName(contextApplication, providerSpec.provider))) {
                        arrayList.add(new LockInstance(i, providerSpec.shape, providerSpec.style, providerSpec.metric));
                    }
                } catch (RuntimeException e) {
                    Log.w("CodexMeterLock", "Unable to enumerate lock widgets", e);
                }
            }
        }
        return arrayList;
    }

    static void updateIds(Context context, AppWidgetManager appWidgetManager, int[] iArr, Shape shape, Style style) {
        updateIds(context, appWidgetManager, iArr, shape, style, Metric.BOTH);
    }

    static void updateIds(Context context, AppWidgetManager appWidgetManager, int[] iArr, Shape shape, Style style, Metric metric) {
        if (context != null && appWidgetManager != null && iArr != null) {
            for (int i : iArr) {
                update(context, appWidgetManager, i, shape, style, metric);
            }
        }
    }

    static void update(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style) {
        update(context, appWidgetManager, i, shape, style, Metric.BOTH);
    }

    static void update(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style, Metric metric) {
        if (context != null && appWidgetManager != null && i != 0) {
            try {
                appWidgetManager.updateAppWidget(i, buildViews(context, appWidgetManager, i, shape, style, metric));
            } catch (RuntimeException e) {
                Log.w("CodexMeterLock", "Samsung lock widget update failed", e);
            }
        }
    }

    static RemoteViews buildViews(Context context, Shape shape, Style style) {
        return buildViews(context, null, 0, shape, style, Metric.BOTH);
    }

    static RemoteViews buildViews(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style) {
        return buildViews(context, appWidgetManager, i, shape, style, Metric.BOTH);
    }

    static RemoteViews buildViews(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style, Metric metric) {
        RemoteViews remoteViewsBuildArcViews;
        int i2;
        boolean zIsSignedIn = SecureTokenStore.isSignedIn(context);
        UsageSnapshot usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(context);
        int iRemaining = remaining(usageSnapshotLoadSnapshot == null ? null : usageSnapshotLoadSnapshot.fiveHour);
        int iRemaining2 = remaining(usageSnapshotLoadSnapshot == null ? null : usageSnapshotLoadSnapshot.weekly);
        LockWidgetOptions lockWidgetOptionsLoadLockWidgetOptions = AppPreferences.loadLockWidgetOptions(context, i);
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(context);
        int i3 = resetCreditsSnapshotLoadResetCredits == null ? 0 : resetCreditsSnapshotLoadResetCredits.availableCount;
        if (metric != Metric.BOTH) {
            int value = metric == Metric.FIVE_HOUR ? iRemaining : iRemaining2;
            int[] size = grantedSize(appWidgetManager, i, Shape.SQUARE);
            RemoteViews single = new RemoteViews(context.getPackageName(), R.layout.widget_lock_dial_single);
            single.setImageViewBitmap(R.id.lock_graphic_image,
                    SamsungLockGraphics.renderSingle(context, metric, value, zIsSignedIn, size[0], size[1]));
            String metricName = metric == Metric.FIVE_HOUR ? "five hour" : "weekly";
            single.setContentDescription(R.id.lock_graphic_root, zIsSignedIn
                    ? "Codex " + metricName + " " + value(value) + " remaining"
                    : "Codex Meter, sign in required");
            applyOpenIntent(context, single, R.id.lock_graphic_root, i, shape, style,
                    lockWidgetOptionsLoadLockWidgetOptions, zIsSignedIn, i3);
            return single;
        }
        if (style == Style.NUMBERS) {
            remoteViewsBuildArcViews = buildNumberViews(context, shape, zIsSignedIn, iRemaining,
                    iRemaining2, lockWidgetOptionsLoadLockWidgetOptions, i3);
            i2 = shape == Shape.SQUARE ? R.id.lock_square_root : R.id.lock_wide_root;
        } else if (style == Style.BARS) {
            remoteViewsBuildArcViews = buildNativeBarViews(context, shape, zIsSignedIn, iRemaining,
                    iRemaining2, lockWidgetOptionsLoadLockWidgetOptions, i3);
            i2 = R.id.lock_graphic_root;
        } else {
            remoteViewsBuildArcViews = buildArcViews(context, appWidgetManager, i, shape, style,
                    zIsSignedIn, iRemaining, iRemaining2, lockWidgetOptionsLoadLockWidgetOptions, i3);
            i2 = R.id.lock_graphic_root;
        }
        applyCountdowns(remoteViewsBuildArcViews, shape, style, lockWidgetOptionsLoadLockWidgetOptions, usageSnapshotLoadSnapshot == null ? null : usageSnapshotLoadSnapshot.fiveHour, usageSnapshotLoadSnapshot == null ? null : usageSnapshotLoadSnapshot.weekly);
        remoteViewsBuildArcViews.setContentDescription(i2, contentDescription(zIsSignedIn, iRemaining, iRemaining2, style, lockWidgetOptionsLoadLockWidgetOptions, i3));
        applyOpenIntent(context, remoteViewsBuildArcViews, i2, i, shape, style, lockWidgetOptionsLoadLockWidgetOptions, zIsSignedIn, i3);
        return remoteViewsBuildArcViews;
    }

    private static RemoteViews buildNumberViews(Context context, Shape shape, boolean z, int i, int i2, LockWidgetOptions lockWidgetOptions, int i3) {
        int i4 = shape == Shape.SQUARE ? R.layout.widget_lock_square : R.layout.widget_lock_wide;
        int i5 = shape == Shape.SQUARE ? R.id.lock_square_value : R.id.lock_wide_value;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), i4);
        boolean z2 = lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction;
        remoteViews.setTextViewText(i5, numberText(z, i, i2, shape, lockWidgetOptions, i3));
        remoteViews.setTextViewTextSize(i5, 2, numberTextSize(shape, lockWidgetOptions, z2));
        return remoteViews;
    }

    private static RemoteViews buildNativeBarViews(Context context, Shape shape, boolean z, int i, int i2, LockWidgetOptions lockWidgetOptions, int i3) {
        float f;
        float f2;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), shape == Shape.SQUARE ? R.layout.widget_lock_bars_square : R.layout.widget_lock_bars_wide);
        boolean z2 = lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction;
        if (!z) {
            remoteViews.setViewVisibility(R.id.lock_bar_primary_group, View.VISIBLE);
            remoteViews.setViewVisibility(R.id.lock_bar_secondary_group, View.GONE);
            remoteViews.setTextViewText(R.id.lock_bar_primary_label, "");
            remoteViews.setTextViewText(R.id.lock_bar_primary_value, "SIGN IN");
            remoteViews.setTextViewTextSize(R.id.lock_bar_primary_value, 2, shape == Shape.SQUARE ? 10.0f : 12.0f);
            remoteViews.setViewVisibility(R.id.lock_bar_primary_progress, View.GONE);
            return remoteViews;
        }
        boolean zShowsFiveHour = lockWidgetOptions.showsFiveHour();
        boolean zShowsWeekly = lockWidgetOptions.showsWeekly();
        remoteViews.setViewVisibility(R.id.lock_bar_primary_group, zShowsFiveHour ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.lock_bar_secondary_group, zShowsWeekly ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.lock_bar_primary_progress, zShowsFiveHour ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.lock_bar_secondary_progress, zShowsWeekly ? View.VISIBLE : View.GONE);
        boolean z3 = z2 && zShowsFiveHour;
        boolean z4 = z2 && !zShowsFiveHour && zShowsWeekly;
        remoteViews.setTextViewText(R.id.lock_bar_primary_label, labelWithReset("5H", z3, i3));
        remoteViews.setTextViewText(R.id.lock_bar_secondary_label, labelWithReset("W", z4, i3));
        remoteViews.setTextViewText(R.id.lock_bar_primary_value, compactValue(i));
        remoteViews.setTextViewText(R.id.lock_bar_secondary_value, compactValue(i2));
        remoteViews.setProgressBar(R.id.lock_bar_primary_progress, 100, progress(i), false);
        remoteViews.setProgressBar(R.id.lock_bar_secondary_progress, 100, progress(i2), false);
        if (lockWidgetOptions.singleMetric()) {
            f = shape == Shape.SQUARE ? 15.0f : 17.0f;
        } else {
            f = shape == Shape.SQUARE ? 10.0f : 11.0f;
        }
        if (lockWidgetOptions.singleMetric()) {
            f2 = shape == Shape.SQUARE ? 9.0f : 10.0f;
        } else {
            f2 = shape == Shape.SQUARE ? 7.5f : 8.0f;
        }
        remoteViews.setTextViewTextSize(R.id.lock_bar_primary_value, 2, f);
        remoteViews.setTextViewTextSize(R.id.lock_bar_secondary_value, 2, f);
        remoteViews.setTextViewTextSize(R.id.lock_bar_primary_label, 2, f2);
        remoteViews.setTextViewTextSize(R.id.lock_bar_secondary_label, 2, f2);
        return remoteViews;
    }

    private static RemoteViews buildArcViews(Context context, AppWidgetManager appWidgetManager, int i, Shape shape, Style style, boolean z, int i2, int i3, LockWidgetOptions lockWidgetOptions, int i4) {
        String strSquareGraphicText;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), graphicLayout(shape, style));
        if (shape == Shape.WIDE) {
            int[] size = grantedSize(appWidgetManager, i, shape);
            remoteViews.setImageViewBitmap(R.id.lock_graphic_image,
                    SamsungLockGraphics.render(context, shape, style, i2, i3, z, size[0], size[1],
                            lockWidgetOptions, i4));
            if (!z) {
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.VISIBLE);
                remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_progress, View.GONE);
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_icon, View.GONE);
                remoteViews.setTextViewText(R.id.lock_graphic_primary_value, "SIGN IN");
                remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_value, 2, 11.0f);
                return remoteViews;
            }
            boolean showFiveHour = lockWidgetOptions.showsFiveHour();
            boolean showWeekly = lockWidgetOptions.showsWeekly();
            remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.GONE);
            remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
            return remoteViews;
        }
        int[] iArrGrantedSize = grantedSize(appWidgetManager, i, shape);
        remoteViews.setImageViewBitmap(R.id.lock_graphic_image, SamsungLockGraphics.render(context, shape, style, i2, i3, z, iArrGrantedSize[0], iArrGrantedSize[1], lockWidgetOptions, i4));
        if (z) {
            if (shape == Shape.SQUARE) {
                remoteViews.setViewVisibility(R.id.lock_graphic_center_value, View.GONE);
            } else {
                remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.GONE);
                remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
            }
            return remoteViews;
        }
        boolean z2 = lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction;
        if (shape == Shape.SQUARE) {
            if (z) {
                strSquareGraphicText = squareGraphicText(i2, i3, lockWidgetOptions, z2, i4);
            } else {
                strSquareGraphicText = "SIGN IN";
            }
            remoteViews.setTextViewText(R.id.lock_graphic_center_value, strSquareGraphicText);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_center_value, 2, z ? squareGraphicTextSize(lockWidgetOptions, z2) : 10.0f);
        } else if (!z) {
            remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, View.VISIBLE);
            remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, View.GONE);
            remoteViews.setTextViewText(R.id.lock_graphic_primary_value, "SIGN IN");
            remoteViews.setTextViewText(R.id.lock_graphic_primary_label, "");
            remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_value, 2, 11.0f);
        } else {
            boolean zShowsFiveHour = lockWidgetOptions.showsFiveHour();
            boolean zShowsWeekly = lockWidgetOptions.showsWeekly();
            remoteViews.setViewVisibility(R.id.lock_graphic_primary_group, zShowsFiveHour ? View.VISIBLE : View.GONE);
            remoteViews.setViewVisibility(R.id.lock_graphic_secondary_group, zShowsWeekly ? View.VISIBLE : View.GONE);
            remoteViews.setTextViewText(R.id.lock_graphic_primary_value, compactValue(i2));
            remoteViews.setTextViewText(R.id.lock_graphic_secondary_value, compactValue(i3));
            remoteViews.setTextViewText(R.id.lock_graphic_primary_label, labelWithReset("5H", z2 && zShowsFiveHour, i4));
            remoteViews.setTextViewText(R.id.lock_graphic_secondary_label, labelWithReset("W", z2 && !zShowsFiveHour && zShowsWeekly, i4));
            float f = lockWidgetOptions.singleMetric() ? 20.0f : 17.0f;
            if (lockWidgetOptions.showCountdown) {
                f -= 2.0f;
            }
            float f2 = lockWidgetOptions.singleMetric() ? 9.5f : 8.0f;
            remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_value, 2, f);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_secondary_value, 2, f);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_primary_label, 2, f2);
            remoteViews.setTextViewTextSize(R.id.lock_graphic_secondary_label, 2, f2);
        }
        return remoteViews;
    }

    private static String squareGraphicText(int i, int i2, LockWidgetOptions lockWidgetOptions, boolean z, int i3) {
        String str;
        if ("five_hour".equals(lockWidgetOptions.metricMode)) {
            str = compactValue(i) + "\n5H";
        } else if ("weekly".equals(lockWidgetOptions.metricMode)) {
            str = compactValue(i2) + "\nW";
        } else {
            str = "5H " + compactValue(i) + "\nW " + compactValue(i2);
        }
        return z ? str + "\nR" + Math.max(0, i3) : str;
    }

    private static float squareGraphicTextSize(LockWidgetOptions lockWidgetOptions, boolean z) {
        if (lockWidgetOptions.singleMetric()) {
            if (z) {
                return 9.5f;
            }
            return lockWidgetOptions.showCountdown ? 11.5f : 12.5f;
        }
        if (z) {
            return 8.1f;
        }
        return lockWidgetOptions.showCountdown ? 8.8f : 9.5f;
    }

    private static String labelWithReset(String str, boolean z, int i) {
        return z ? str + " · R" + Math.max(0, i) : str;
    }

    private static void applyCountdowns(RemoteViews remoteViews, Shape shape, Style style, LockWidgetOptions lockWidgetOptions, UsageWindow usageWindow, UsageWindow usageWindow2) {
        if (style == Style.BARS || (shape == Shape.WIDE && (style == Style.RINGS || style == Style.DIALS))) {
            applyCountdown(remoteViews, R.id.lock_primary_countdown, lockWidgetOptions.showCountdown && lockWidgetOptions.showsFiveHour(), usageWindow);
            applyCountdown(remoteViews, R.id.lock_secondary_countdown, lockWidgetOptions.showCountdown && lockWidgetOptions.showsWeekly(), usageWindow2);
        } else {
            if (!"five_hour".equals(lockWidgetOptions.metricMode)) {
                usageWindow = "weekly".equals(lockWidgetOptions.metricMode) ? usageWindow2 : earlierWindow(usageWindow, usageWindow2);
            }
            applyCountdown(remoteViews, R.id.lock_countdown, lockWidgetOptions.showCountdown, usageWindow);
        }
    }

    private static UsageWindow earlierWindow(UsageWindow usageWindow, UsageWindow usageWindow2) {
        long jCurrentTimeMillis = System.currentTimeMillis();
        long jResetAtMillis = usageWindow == null ? 0L : usageWindow.resetAtMillis();
        long jResetAtMillis2 = usageWindow2 != null ? usageWindow2.resetAtMillis() : 0L;
        if (jResetAtMillis > jCurrentTimeMillis) {
            return (jResetAtMillis2 <= jCurrentTimeMillis || jResetAtMillis <= jResetAtMillis2) ? usageWindow : usageWindow2;
        }
        if (jResetAtMillis2 <= jCurrentTimeMillis) {
            usageWindow2 = null;
        }
        return usageWindow2;
    }

    private static void applyCountdown(RemoteViews remoteViews, int i, boolean z, UsageWindow usageWindow) {
        long jCurrentTimeMillis = System.currentTimeMillis();
        long jResetAtMillis = usageWindow == null ? 0L : usageWindow.resetAtMillis();
        if (!z || jResetAtMillis <= jCurrentTimeMillis) {
            remoteViews.setViewVisibility(i, View.GONE);
            return;
        }
        long jElapsedRealtime = SystemClock.elapsedRealtime() + Math.max(1000L, jResetAtMillis - jCurrentTimeMillis);
        remoteViews.setViewVisibility(i, View.VISIBLE);
        remoteViews.setChronometer(i, jElapsedRealtime, null, true);
        remoteViews.setChronometerCountDown(i, true);
    }

    private static void updateComponent(Context context, AppWidgetManager appWidgetManager, Class<?> cls, Shape shape, Style style, Metric metric) {
        try {
            updateIds(context, appWidgetManager, appWidgetManager.getAppWidgetIds(new ComponentName(context, cls)), shape, style, metric);
        } catch (RuntimeException e) {
            Log.w("CodexMeterLock", "Unable to enumerate lock widgets", e);
        }
    }

    private static void applyOpenIntent(Context context, RemoteViews remoteViews, int i, int i2, Shape shape, Style style, LockWidgetOptions lockWidgetOptions, boolean z, int i3) {
        boolean z2 = lockWidgetOptions.showResetAction && z && i3 > 0;
        String target = z2 ? "reset" : "open";
        Intent intentAddFlags = new Intent(context,
                (Class<?>) (z2 ? ResetCreditActivity.class : MainActivity.class))
                .setAction("dev.bennett.codexmeter.action.LOCK_WIDGET_"
                        + target.toUpperCase(Locale.US))
                .setData(Uri.parse("codexmeter://widget/lock/v" + AppConstants.VERSION_CODE + "/"
                        + i2 + "/"
                        + shape.name().toLowerCase(Locale.US) + "/"
                        + style.name().toLowerCase(Locale.US)
                        + "/" + target))
                .addFlags(335544320);
        if (i2 == 0) {
            i2 = 0;
        }
        remoteViews.setOnClickPendingIntent(i, PendingIntent.getActivity(context, 82000 + i2 + (shape.ordinal() * 1000) + (style.ordinal() * 100) + (z2 ? 50000 : 0), intentAddFlags, 201326592));
    }

    private static int graphicLayout(Shape shape, Style style) {
        return style == Style.RINGS ? shape == Shape.SQUARE ? R.layout.widget_lock_rings_square : R.layout.widget_lock_rings_wide : shape == Shape.SQUARE ? R.layout.widget_lock_dials_square : R.layout.widget_lock_dials_wide;
    }

    private static String numberText(boolean z, int i, int i2, Shape shape, LockWidgetOptions lockWidgetOptions, int i3) {
        String str;
        if (!z) {
            return shape == Shape.SQUARE ? "SIGN\nIN" : "SIGN IN";
        }
        if ("five_hour".equals(lockWidgetOptions.metricMode)) {
            str = shape == Shape.SQUARE ? "5H\n" + value(i) : "5H " + value(i);
        } else if ("weekly".equals(lockWidgetOptions.metricMode)) {
            str = shape == Shape.SQUARE ? "W\n" + value(i2) : "W " + value(i2);
        } else if (shape == Shape.SQUARE) {
            str = "5H " + compactValue(i) + "\nW " + compactValue(i2);
        } else {
            str = "5H " + value(i) + "  ·  W " + value(i2);
        }
        if (lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction) {
            return str + (shape == Shape.SQUARE ? "\nR" + Math.max(0, i3) : "  ·  R" + Math.max(0, i3));
        }
        return str;
    }

    private static float numberTextSize(Shape shape, LockWidgetOptions lockWidgetOptions, boolean z) {
        return shape == Shape.WIDE ? lockWidgetOptions.singleMetric() ? z ? 14.0f : 16.0f : z ? 12.0f : 14.0f : lockWidgetOptions.singleMetric() ? z ? 10.5f : 13.0f : z ? 9.2f : 11.0f;
    }

    private static String contentDescription(boolean z, int i, int i2, Style style, LockWidgetOptions lockWidgetOptions, int i3) {
        if (!z) {
            return "Codex Meter, sign in required";
        }
        StringBuilder sbAppend = new StringBuilder("Codex ").append(styleLabel(style).toLowerCase()).append(", ");
        if (lockWidgetOptions.showsFiveHour()) {
            sbAppend.append("five hour ").append(value(i)).append(" remaining");
        }
        if (lockWidgetOptions.showsFiveHour() && lockWidgetOptions.showsWeekly()) {
            sbAppend.append(", ");
        }
        if (lockWidgetOptions.showsWeekly()) {
            sbAppend.append("weekly ").append(value(i2)).append(" remaining");
        }
        if (lockWidgetOptions.showCountdown) {
            sbAppend.append(", live reset countdown");
        }
        if (lockWidgetOptions.showResetCredits || lockWidgetOptions.showResetAction) {
            sbAppend.append(", ").append(i3).append(" reset credit").append(i3 == 1 ? "" : "s");
        }
        if (lockWidgetOptions.showResetAction && i3 > 0) {
            sbAppend.append("; tap to open reset confirmation");
        }
        return sbAppend.toString();
    }

    public static String styleLabel(Style style) {
        if (style == Style.RINGS) {
            return "Rings";
        }
        if (style == Style.DIALS) {
            return "Gauges";
        }
        return style == Style.BARS ? "Bars" : "Numbers";
    }

    private static String value(int i) {
        return i < 0 ? "—" : i + "%";
    }

    private static String compactValue(int i) {
        return i < 0 ? "—" : Integer.toString(i);
    }

    private static int progress(int i) {
        if (i < 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, i));
    }

    private static int remaining(UsageWindow usageWindow) {
        if (usageWindow == null) {
            return -1;
        }
        return Math.max(0, Math.min(100, usageWindow.remainingPercent()));
    }

    private static ProviderSpec findSpec(AppWidgetManager appWidgetManager, Context context, int i) {
        for (ProviderSpec providerSpec : PROVIDERS) {
            try {
                for (int i2 : appWidgetManager.getAppWidgetIds(new ComponentName(context, providerSpec.provider))) {
                    if (i2 == i) {
                        return providerSpec;
                    }
                }
            } catch (RuntimeException e) {
            }
        }
        return null;
    }

    private static int[] grantedSize(AppWidgetManager appWidgetManager, int i, Shape shape) {
        int[] iArr;
        SizeF sizeFBestSize;
        int i2 = shape == Shape.SQUARE ? 56 : 124;
        if (appWidgetManager == null || i == 0) {
            return new int[]{i2, 56};
        }
        try {
            Bundle appWidgetOptions = appWidgetManager.getAppWidgetOptions(i);
            ArrayList parcelableArrayList = appWidgetOptions.getParcelableArrayList("appWidgetSizes");
            if (parcelableArrayList != null && !parcelableArrayList.isEmpty() && (sizeFBestSize = bestSize(parcelableArrayList, shape)) != null) {
                iArr = new int[]{Math.round(sizeFBestSize.getWidth()), Math.round(sizeFBestSize.getHeight())};
            } else {
                int i3 = appWidgetOptions.getInt("appWidgetMinWidth", i2);
                int i4 = appWidgetOptions.getInt("appWidgetMinHeight", 56);
                iArr = new int[2];
                if (i3 <= 0) {
                    i3 = i2;
                }
                iArr[0] = i3;
                if (i4 <= 0) {
                    i4 = 56;
                }
                iArr[1] = i4;
            }
            return iArr;
        } catch (RuntimeException e) {
            Log.w("CodexMeterLock", "Unable to read lock widget size", e);
            return new int[]{i2, 56};
        }
    }

    private static SizeF bestSize(ArrayList<SizeF> arrayList, Shape shape) {
        float f;
        SizeF sizeF;
        float f2 = shape == Shape.SQUARE ? 1.0f : 2.2142856f;
        SizeF sizeF2 = null;
        float f3 = Float.MAX_VALUE;
        for (SizeF sizeF3 : arrayList) {
            if (sizeF3 == null || sizeF3.getWidth() <= 0.0f || sizeF3.getHeight() <= 0.0f) {
                f = f3;
                sizeF = sizeF2;
            } else {
                float fAbs = (Math.abs((sizeF3.getWidth() / sizeF3.getHeight()) - f2) * 100.0f) + (Math.abs(sizeF3.getHeight() - 56.0f) * 0.2f);
                if (sizeF2 == null || fAbs < f3) {
                    sizeF = sizeF3;
                    f = fAbs;
                } else {
                    f = f3;
                    sizeF = sizeF2;
                }
            }
            sizeF2 = sizeF;
            f3 = f;
        }
        return sizeF2;
    }

    private static Context application(Context context) {
        Context applicationContext = context.getApplicationContext();
        return applicationContext == null ? context : applicationContext;
    }
}
