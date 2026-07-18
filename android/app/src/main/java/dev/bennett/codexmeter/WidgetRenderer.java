package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetRenderer {
    private static final int GRAPHIC_LARGE = 1;
    private static final int GRAPHIC_MAX = 2;
    private static final int GRAPHIC_STANDARD = 0;
    private static final String STYLE_BATTERY_LIST = "battery_list";
    private static final String STYLE_FOUR_DIALS = "four_dials";
    private static final String STYLE_MICRO = "micro";

    private WidgetRenderer() {
    }

    public static void updateAll(Context context) {
        if (context != null) {
            if (context.getApplicationContext() != null) {
                context = context.getApplicationContext();
            }
            try {
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, (Class<?>) CodexUsageWidget.class));
                int length = appWidgetIds.length;
                for (int i = GRAPHIC_STANDARD; i < length; i += GRAPHIC_LARGE) {
                    update(context, appWidgetManager, appWidgetIds[i]);
                }
                SamsungLockWidgetSupport.updateAll(context);
            } catch (RuntimeException e) {
                Log.w("CodexMeterWidget", "Widget update failed: " + safeMessage(e));
            }
        }
    }

    public static void update(Context context, AppWidgetManager appWidgetManager, int i) {
        RemoteViews remoteViewsBuildViews;
        if (context != null && appWidgetManager != null && i != 0) {
            try {
                WidgetOptions widgetOptionsLoadWidgetOptions = AppPreferences.loadWidgetOptions(context, i);
                Bundle appWidgetOptions = appWidgetManager.getAppWidgetOptions(i);
                if (Build.VERSION.SDK_INT >= 31) {
                    remoteViewsBuildViews = buildResponsiveWidget(context, i, widgetOptionsLoadWidgetOptions);
                } else {
                    remoteViewsBuildViews = buildViews(context, i, widgetOptionsLoadWidgetOptions,
                            styleForSize(context, widgetOptionsLoadWidgetOptions, appWidgetOptions),
                            appWidgetOptions);
                }
                appWidgetManager.updateAppWidget(i, remoteViewsBuildViews);
            } catch (RuntimeException e) {
                Log.w("CodexMeterWidget", "Widget render failed: " + safeMessage(e));
                try {
                    appWidgetManager.updateAppWidget(i, buildFallback(context, i));
                } catch (RuntimeException e2) {
                }
            }
        }
    }

    @SuppressLint({"NewApi", "UseRequiresApi"})
    private static RemoteViews buildResponsiveWidget(Context context, int i, WidgetOptions widgetOptions) {
        LinkedHashMap<SizeF, RemoteViews> linkedHashMap = new LinkedHashMap<>();
        boolean material = isMaterial(widgetOptions);
        linkedHashMap.put(new SizeF(110.0f, 60.0f),
                buildViews(context, i, widgetOptions,
                        material ? WidgetOptions.STYLE_MINIMAL : WidgetOptions.STYLE_RINGS,
                        sizeBundle(110, 70), GRAPHIC_STANDARD));
        linkedHashMap.put(new SizeF(250.0f, 60.0f),
                buildViews(context, i, widgetOptions,
                        material ? WidgetOptions.STYLE_MINIMAL : STYLE_FOUR_DIALS,
                        sizeBundle(250, 70), GRAPHIC_STANDARD));
        linkedHashMap.put(new SizeF(110.0f, 130.0f),
                buildViews(context, i, widgetOptions,
                        material ? WidgetOptions.STYLE_DIALS : STYLE_BATTERY_LIST,
                        sizeBundle(110, 156), GRAPHIC_STANDARD));
        linkedHashMap.put(new SizeF(250.0f, 130.0f),
                buildViews(context, i, widgetOptions,
                        material ? WidgetOptions.STYLE_DIALS : STYLE_BATTERY_LIST,
                        sizeBundle(250, 156), GRAPHIC_STANDARD));
        return new RemoteViews(linkedHashMap);
    }

    static RemoteViews buildPreview(Context context, int appWidgetId, WidgetOptions widgetOptions,
            Bundle appWidgetOptions) {
        RemoteViews preview = buildViews(context, appWidgetId, widgetOptions,
                styleForSize(context, widgetOptions, appWidgetOptions), appWidgetOptions);
        preview.setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT);
        return preview;
    }

    private static String styleForSize(Context context, WidgetOptions options, Bundle bundle) {
        int rows = option(bundle, "semAppWidgetRowSpan");
        int columns = option(bundle, "semAppWidgetColumnSpan");
        if (isMaterial(options)) {
            if (rows > 0) {
                return rows >= 2 ? WidgetOptions.STYLE_DIALS : WidgetOptions.STYLE_MINIMAL;
            }
            return currentHeight(context, bundle) >= 110
                    ? WidgetOptions.STYLE_DIALS : WidgetOptions.STYLE_MINIMAL;
        }
        if (rows > 0) {
            if (rows >= 2) {
                return STYLE_BATTERY_LIST;
            }
            return columns >= 3 ? STYLE_FOUR_DIALS : WidgetOptions.STYLE_RINGS;
        }
        if (currentHeight(context, bundle) >= 130) {
            return STYLE_BATTERY_LIST;
        }
        return currentWidth(context, bundle) >= 240
                ? STYLE_FOUR_DIALS : WidgetOptions.STYLE_RINGS;
    }

    private static Bundle sizeBundle(int i, int i2) {
        Bundle bundle = new Bundle();
        bundle.putInt("appWidgetMinWidth", i);
        bundle.putInt("appWidgetMinHeight", i2);
        return bundle;
    }

    private static RemoteViews buildViews(Context context, int i, WidgetOptions widgetOptions, String str, Bundle bundle) {
        return buildViews(context, i, widgetOptions, str, bundle, resolveGraphicTier(context, widgetOptions, bundle));
    }

    private static RemoteViews buildViews(Context context, int i, WidgetOptions widgetOptions, String str, Bundle bundle, int i2) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(),
                layoutForStyle(str, i2, widgetOptions));
        boolean zChooseDark = chooseDark(context, widgetOptions);
        WidgetState widgetStateFrom = WidgetState.from(context, widgetOptions);
        applyRootAndHeader(context, remoteViews, i, widgetOptions, zChooseDark, widgetStateFrom);
        if (STYLE_MICRO.equals(str)) {
            renderMicro(remoteViews, widgetOptions, zChooseDark, widgetStateFrom);
        } else if (WidgetOptions.STYLE_RINGS.equals(str)) {
            renderGraphic(context, remoteViews, widgetOptions, zChooseDark, widgetStateFrom,
                    !isMaterial(widgetOptions), i2);
        } else if (STYLE_FOUR_DIALS.equals(str)) {
            renderFourDials(context, remoteViews, widgetOptions, zChooseDark, widgetStateFrom);
        } else if (STYLE_BATTERY_LIST.equals(str)) {
            renderBatteryList(context, remoteViews, widgetOptions, zChooseDark, widgetStateFrom);
        } else if (WidgetOptions.STYLE_DIALS.equals(str)) {
            renderGraphic(context, remoteViews, widgetOptions, zChooseDark, widgetStateFrom, false, i2);
        } else if (WidgetOptions.STYLE_MINIMAL.equals(str)) {
            renderMinimal(context, remoteViews, widgetOptions, zChooseDark, widgetStateFrom);
        } else {
            renderBars(context, remoteViews, widgetOptions, zChooseDark, widgetStateFrom, bundle);
        }
        applyMetricVisibility(remoteViews, widgetOptions);
        applyResetCreditRow(context, remoteViews, i, widgetOptions, zChooseDark, str);
        return remoteViews;
    }

    private static RemoteViews buildFallback(Context context, int i) {
        WidgetOptions widgetOptionsDefaults = WidgetOptions.defaults();
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget_compact);
        boolean zChooseDark = chooseDark(context, widgetOptionsDefaults);
        WidgetState widgetStateError = WidgetState.error("Open Codex Meter to recover");
        applyRootAndHeader(context, remoteViews, i, widgetOptionsDefaults, zChooseDark, widgetStateError);
        renderMinimal(context, remoteViews, widgetOptionsDefaults, zChooseDark, widgetStateError);
        return remoteViews;
    }

    private static String resolveStyle(Context context, WidgetOptions widgetOptions, Bundle bundle) {
        int iCurrentWidth = currentWidth(context, bundle);
        int iCurrentHeight = currentHeight(context, bundle);
        String strNormalizeStyle = WidgetOptions.normalizeStyle(widgetOptions.layout);
        if ((iCurrentHeight <= 0 || iCurrentHeight >= 90) && (iCurrentWidth <= 0 || iCurrentWidth >= 145)) {
            return "auto".equals(strNormalizeStyle) ? ((iCurrentHeight <= 0 || iCurrentHeight >= 140) && (iCurrentWidth <= 0 || iCurrentWidth >= 215)) ? WidgetOptions.STYLE_BARS : WidgetOptions.STYLE_MINIMAL : (iCurrentHeight <= 0 || iCurrentHeight >= 130) ? strNormalizeStyle : WidgetOptions.STYLE_MINIMAL;
        }
        return STYLE_MICRO;
    }

    private static int option(Bundle bundle, String str) {
        return bundle == null ? GRAPHIC_STANDARD : bundle.getInt(str, GRAPHIC_STANDARD);
    }

    private static int currentWidth(Context context, Bundle bundle) {
        int iOption = option(bundle, "appWidgetMinWidth");
        int iOption2 = option(bundle, "appWidgetMaxWidth");
        return (context == null || context.getResources().getConfiguration().orientation != GRAPHIC_MAX) ? iOption > 0 ? iOption : iOption2 : iOption2 > 0 ? iOption2 : iOption;
    }

    private static int currentHeight(Context context, Bundle bundle) {
        int iOption = option(bundle, "appWidgetMinHeight");
        int iOption2 = option(bundle, "appWidgetMaxHeight");
        return (context == null || context.getResources().getConfiguration().orientation != GRAPHIC_MAX) ? iOption2 > 0 ? iOption2 : iOption : iOption > 0 ? iOption : iOption2;
    }

    private static int layoutForStyle(String str, int i, WidgetOptions options) {
        if (isMaterial(options)) {
            return WidgetOptions.STYLE_DIALS.equals(str)
                    ? R.layout.widget_material_dials : R.layout.widget_material_compact;
        }
        if (STYLE_BATTERY_LIST.equals(str)) {
            return R.layout.widget_battery_list;
        }
        if (STYLE_FOUR_DIALS.equals(str)) {
            return R.layout.widget_rings_four;
        }
        if (STYLE_MICRO.equals(str)) {
            return R.layout.widget_micro;
        }
        if (WidgetOptions.STYLE_RINGS.equals(str)) {
            // Samsung's battery widget keeps the 46dp small arc at every 2x1 host size.
            return R.layout.widget_rings;
        }
        if (!WidgetOptions.STYLE_DIALS.equals(str)) {
            return WidgetOptions.STYLE_MINIMAL.equals(str) ? R.layout.widget_compact : R.layout.widget_detailed;
        }
        if (i == GRAPHIC_MAX) {
            return R.layout.widget_dials_max;
        }
        return i == GRAPHIC_LARGE ? R.layout.widget_dials_large : R.layout.widget_dials;
    }

    private static int resolveGraphicTier(Context context, WidgetOptions widgetOptions, Bundle bundle) {
        int iCurrentWidth = currentWidth(context, bundle);
        int iCurrentHeight = currentHeight(context, bundle);
        return (iCurrentWidth <= 0 || iCurrentHeight <= 0) ? (WidgetOptions.GRAPHIC_MAX.equals(widgetOptions.graphicScale) || WidgetOptions.GRAPHIC_LARGE.equals(widgetOptions.graphicScale)) ? GRAPHIC_LARGE : GRAPHIC_STANDARD : WidgetOptions.GRAPHIC_MAX.equals(widgetOptions.graphicScale) ? (iCurrentWidth < 340 || iCurrentHeight < 198) ? (iCurrentWidth < 270 || iCurrentHeight < 158) ? GRAPHIC_STANDARD : GRAPHIC_LARGE : GRAPHIC_MAX : WidgetOptions.GRAPHIC_LARGE.equals(widgetOptions.graphicScale) ? (iCurrentWidth < 400 || iCurrentHeight < 208) ? (iCurrentWidth < 290 || iCurrentHeight < 164) ? GRAPHIC_STANDARD : GRAPHIC_LARGE : GRAPHIC_MAX : (iCurrentWidth < 460 || iCurrentHeight < 220) ? (iCurrentWidth < 340 || iCurrentHeight < 178) ? GRAPHIC_STANDARD : GRAPHIC_LARGE : GRAPHIC_MAX;
    }

    private static boolean chooseDark(Context context, WidgetOptions widgetOptions) {
        if (WidgetOptions.THEME_DARK.equals(widgetOptions.theme)) {
            return true;
        }
        return !WidgetOptions.THEME_LIGHT.equals(widgetOptions.theme) && (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    private static void applyRootAndHeader(Context context, RemoteViews remoteViews, int i, WidgetOptions widgetOptions, boolean z, WidgetState widgetState) {
        if (isMaterial(widgetOptions) && Build.VERSION.SDK_INT >= 31) {
            int alpha = Math.round(Math.max(0, Math.min(100, widgetOptions.opacity)) * 2.55f);
            int surface = Ui.materialSurface(context, z);
            remoteViews.setInt(android.R.id.background, "setBackgroundColor",
                    Color.argb(alpha, Color.red(surface), Color.green(surface),
                            Color.blue(surface)));
        } else if (WidgetOptions.SURFACE_ONE_UI.equals(widgetOptions.surfaceStyle) && isSamsung(context)) {
            int alpha = Math.round(Math.max(0, Math.min(100, widgetOptions.opacity)) * 2.55f);
            remoteViews.setInt(android.R.id.background, "setBackgroundColor",
                    z ? Color.argb(alpha, 0, 0, 0) : Color.argb(alpha, 255, 255, 255));
        } else {
            remoteViews.setInt(android.R.id.background, "setBackgroundResource",
                    backgroundResource(context, z, widgetOptions.opacity, widgetOptions.surfaceStyle));
        }
        remoteViews.setTextColor(R.id.widget_title, mainTextColor(context, widgetOptions, z));
        remoteViews.setViewVisibility(R.id.widget_title, widgetOptions.showTitle ? GRAPHIC_STANDARD : 8);
        remoteViews.setTextColor(R.id.plan_label, mutedColor(context, widgetOptions, z));
        remoteViews.setTextViewText(R.id.plan_label, widgetState.plan);
        remoteViews.setViewVisibility(R.id.plan_label, (!widgetOptions.showPlan || widgetState.plan.isEmpty()) ? 8 : GRAPHIC_STANDARD);
        remoteViews.setImageViewResource(R.id.refresh_button, isMaterial(widgetOptions)
                ? R.drawable.ic_refresh
                : (z ? R.drawable.ic_oui_refresh_widget_light
                        : R.drawable.ic_oui_refresh_widget_dark));
        remoteViews.setViewVisibility(R.id.refresh_button, widgetOptions.showRefresh ? GRAPHIC_STANDARD : 8);
        applyIntents(context, remoteViews, i);
    }

    private static void applyIntents(Context context, RemoteViews remoteViews, int i) {
        String tapAction = AppPreferences.getWidgetTapAction(context, i);
        PendingIntent rootAction;
        if (WidgetOptions.TAP_REFRESH.equals(tapAction)) {
            rootAction = PendingIntent.getBroadcast(context, 74000 + i,
                    new Intent(context, (Class<?>) WidgetRefreshReceiver.class)
                            .setAction(AppConstants.ACTION_REFRESH_WIDGET)
                            .setData(widgetUri(i, "root-refresh"))
                            .putExtra("appWidgetId", i),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (WidgetOptions.TAP_USE_RESET.equals(tapAction)) {
            rootAction = PendingIntent.getActivity(context, 74000 + i,
                    new Intent(context, (Class<?>) ResetCreditActivity.class)
                            .setAction("dev.bennett.codexmeter.action.WIDGET_RESET")
                            .setData(widgetUri(i, "root-reset"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            rootAction = PendingIntent.getActivity(context, 74000 + i,
                    new Intent(context, (Class<?>) MainActivity.class)
                            .setAction("dev.bennett.codexmeter.action.WIDGET_OPEN")
                            .setData(widgetUri(i, "root-open"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        remoteViews.setOnClickPendingIntent(android.R.id.background, rootAction);
        remoteViews.setOnClickPendingIntent(R.id.refresh_button,
                PendingIntent.getBroadcast(context, 75000 + i,
                        new Intent(context, (Class<?>) WidgetRefreshReceiver.class)
                                .setAction(AppConstants.ACTION_REFRESH_WIDGET)
                                .setData(widgetUri(i, "refresh"))
                                .putExtra("appWidgetId", i),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        remoteViews.setOnClickPendingIntent(R.id.reset_credit_button,
                PendingIntent.getActivity(context, 76000 + i,
                        new Intent(context, (Class<?>) ResetCreditActivity.class)
                                .setAction("dev.bennett.codexmeter.action.WIDGET_RESET")
                                .setData(widgetUri(i, "reset"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static Uri widgetUri(int appWidgetId, String action) {
        return Uri.parse("codexmeter://widget/home/v" + AppConstants.VERSION_CODE + "/"
                + appWidgetId + "/" + action);
    }

    private static void renderBars(Context context, RemoteViews remoteViews, WidgetOptions widgetOptions, boolean z, WidgetState widgetState, Bundle bundle) {
        boolean z2 = false;
        int iMainTextColor = WidgetGraphics.mainTextColor(z);
        int iSecondaryColor = secondaryColor(z);
        int iMutedColor = mutedColor(z);
        int iFaintColor = faintColor(z);
        remoteViews.setTextColor(R.id.primary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.secondary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.primary_percent, iMainTextColor);
        remoteViews.setTextColor(R.id.secondary_percent, iMainTextColor);
        remoteViews.setTextColor(R.id.primary_reset, iMutedColor);
        remoteViews.setTextColor(R.id.secondary_reset, iMutedColor);
        remoteViews.setTextColor(R.id.updated_label, iFaintColor);
        remoteViews.setImageViewResource(R.id.primary_track, z ? R.drawable.progress_track : R.drawable.progress_track_light);
        remoteViews.setImageViewResource(R.id.secondary_track, z ? R.drawable.progress_track : R.drawable.progress_track_light);
        int iProgressResource = progressResource(widgetOptions.accent, z);
        remoteViews.setImageViewResource(R.id.primary_progress, iProgressResource);
        remoteViews.setImageViewResource(R.id.secondary_progress, iProgressResource);
        applyAppAccentFilter(context, remoteViews, widgetOptions.accent,
                R.id.primary_progress, R.id.secondary_progress);
        remoteViews.setInt(R.id.primary_progress, "setImageLevel", Math.max(GRAPHIC_STANDARD, widgetState.primaryValue) * 100);
        remoteViews.setInt(R.id.secondary_progress, "setImageLevel", Math.max(GRAPHIC_STANDARD, widgetState.secondaryValue) * 100);
        remoteViews.setTextViewText(R.id.primary_label, "5-hour");
        remoteViews.setTextViewText(R.id.secondary_label, "Weekly");
        remoteViews.setTextViewText(R.id.primary_percent, widgetState.primaryText);
        remoteViews.setTextViewText(R.id.secondary_percent, widgetState.secondaryText);
        remoteViews.setTextViewText(R.id.primary_reset, widgetState.primaryReset);
        remoteViews.setTextViewText(R.id.secondary_reset, widgetState.secondaryReset);
        remoteViews.setViewVisibility(R.id.primary_reset, widgetState.primaryReset.isEmpty() ? 8 : GRAPHIC_STANDARD);
        remoteViews.setViewVisibility(R.id.secondary_reset, widgetState.secondaryReset.isEmpty() ? 8 : GRAPHIC_STANDARD);
        applyUpdated(remoteViews, widgetOptions, widgetState, iFaintColor);
        int iCurrentHeight = currentHeight(context, bundle);
        if ("compact".equals(widgetOptions.density) || ("auto".equals(widgetOptions.density) && iCurrentHeight > 0 && iCurrentHeight < 145)) {
            z2 = true;
        }
        if (z2) {
            remoteViews.setTextViewTextSize(R.id.primary_percent, GRAPHIC_MAX, 16.0f);
            remoteViews.setTextViewTextSize(R.id.secondary_percent, GRAPHIC_MAX, 16.0f);
            remoteViews.setTextViewTextSize(R.id.primary_reset, GRAPHIC_MAX, 9.0f);
            remoteViews.setTextViewTextSize(R.id.secondary_reset, GRAPHIC_MAX, 9.0f);
            return;
        }
        if (WidgetOptions.DENSITY_COMFORTABLE.equals(widgetOptions.density)) {
            remoteViews.setTextViewTextSize(R.id.primary_percent, GRAPHIC_MAX, 21.0f);
            remoteViews.setTextViewTextSize(R.id.secondary_percent, GRAPHIC_MAX, 21.0f);
        }
    }

    private static void renderMicro(RemoteViews remoteViews, WidgetOptions widgetOptions, boolean z, WidgetState widgetState) {
        int iMainTextColor = WidgetGraphics.mainTextColor(z);
        int iSecondaryColor = secondaryColor(z);
        remoteViews.setTextColor(R.id.primary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.secondary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.primary_percent, iMainTextColor);
        remoteViews.setTextColor(R.id.secondary_percent, iMainTextColor);
        remoteViews.setTextViewText(R.id.primary_label, "5h");
        remoteViews.setTextViewText(R.id.secondary_label, "Wk");
        remoteViews.setTextViewText(R.id.primary_percent, widgetState.primaryShort);
        remoteViews.setTextViewText(R.id.secondary_percent, widgetState.secondaryShort);
        remoteViews.setViewVisibility(R.id.primary_reset, 8);
        remoteViews.setViewVisibility(R.id.secondary_reset, 8);
        remoteViews.setViewVisibility(R.id.updated_label, 8);
    }

    private static void renderMinimal(Context context, RemoteViews remoteViews, WidgetOptions widgetOptions, boolean z, WidgetState widgetState) {
        String str;
        int i = R.drawable.progress_track_light;
        int iMainTextColor = mainTextColor(context, widgetOptions, z);
        int iSecondaryColor = secondaryColor(context, widgetOptions, z);
        int iMutedColor = mutedColor(context, widgetOptions, z);
        int iFaintColor = faintColor(context, widgetOptions, z);
        remoteViews.setTextColor(R.id.primary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.secondary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.primary_percent, iMainTextColor);
        remoteViews.setTextColor(R.id.secondary_percent, iMainTextColor);
        remoteViews.setTextColor(R.id.primary_reset, iMutedColor);
        remoteViews.setTextColor(R.id.updated_label, iFaintColor);
        remoteViews.setImageViewResource(R.id.primary_track, z ? R.drawable.progress_track : R.drawable.progress_track_light);
        if (z) {
            i = R.drawable.progress_track;
        }
        remoteViews.setImageViewResource(R.id.secondary_track, i);
        int iProgressResource = progressResource(widgetOptions.accent, z);
        remoteViews.setImageViewResource(R.id.primary_progress, iProgressResource);
        remoteViews.setImageViewResource(R.id.secondary_progress, iProgressResource);
        applyAppAccentFilter(context, remoteViews, widgetOptions,
                R.id.primary_progress, R.id.secondary_progress);
        remoteViews.setInt(R.id.primary_progress, "setImageLevel", Math.max(GRAPHIC_STANDARD, widgetState.primaryValue) * 100);
        remoteViews.setInt(R.id.secondary_progress, "setImageLevel", Math.max(GRAPHIC_STANDARD, widgetState.secondaryValue) * 100);
        remoteViews.setTextViewText(R.id.primary_label, "5h");
        remoteViews.setTextViewText(R.id.secondary_label, "Week");
        remoteViews.setTextViewText(R.id.primary_percent, widgetState.primaryShort);
        remoteViews.setTextViewText(R.id.secondary_percent, widgetState.secondaryShort);
        if ("five_hour".equals(widgetOptions.metricMode)) {
            str = widgetState.primaryShortReset.isEmpty() ? "" : "5h " + widgetState.primaryShortReset;
        } else if ("weekly".equals(widgetOptions.metricMode)) {
            str = widgetState.secondaryShortReset.isEmpty() ? "" : "Week " + widgetState.secondaryShortReset;
        } else {
            str = widgetState.combinedReset;
        }
        remoteViews.setTextViewText(R.id.primary_reset, str);
        remoteViews.setViewVisibility(R.id.primary_reset, str.isEmpty() ? 8 : GRAPHIC_STANDARD);
        remoteViews.setViewVisibility(R.id.secondary_reset, 8);
        applyUpdated(remoteViews, widgetOptions, widgetState, iFaintColor);
    }

    private static void renderGraphic(Context context, RemoteViews remoteViews, WidgetOptions widgetOptions, boolean z, WidgetState widgetState, boolean z2, int i) {
        float f;
        int iMainTextColor = mainTextColor(context, widgetOptions, z);
        int iSecondaryColor = secondaryColor(context, widgetOptions, z);
        int iMutedColor = mutedColor(context, widgetOptions, z);
        int iFaintColor = faintColor(context, widgetOptions, z);
        int iAccentColor = accentColor(context, widgetOptions, z);
        int iTrackColor = trackColor(context, widgetOptions, z);
        String str = WidgetOptions.DISPLAY_USED.equals(widgetOptions.displayMode) ? WidgetOptions.DISPLAY_USED : "left";
        if (i == GRAPHIC_MAX) {
            f = 1.36f;
        } else {
            f = i == GRAPHIC_LARGE ? 1.24f : 1.0f;
        }
        float fMin = widgetOptions.singleMetric() ? Math.min(1.36f, f * 1.16f) : f;
        if (z2) {
            applyProgressColors(remoteViews, R.id.primary_samsung_progress,
                    iAccentColor, iTrackColor);
            applyProgressColors(remoteViews, R.id.secondary_samsung_progress,
                    iAccentColor, iTrackColor);
            remoteViews.setProgressBar(R.id.primary_samsung_progress, 100,
                    Math.max(GRAPHIC_STANDARD, widgetState.primaryValue), false);
            remoteViews.setProgressBar(R.id.secondary_samsung_progress, 100,
                    Math.max(GRAPHIC_STANDARD, widgetState.secondaryValue), false);
            remoteViews.setTextViewText(R.id.primary_samsung_value,
                    dialValue(widgetState.primaryValue, widgetOptions.showPercentSymbol));
            remoteViews.setTextViewText(R.id.secondary_samsung_value,
                    dialValue(widgetState.secondaryValue, widgetOptions.showPercentSymbol));
            remoteViews.setTextColor(R.id.primary_samsung_value, iMainTextColor);
            remoteViews.setTextColor(R.id.secondary_samsung_value, iMainTextColor);
            remoteViews.setInt(R.id.primary_samsung_icon, "setColorFilter", iMainTextColor);
            remoteViews.setInt(R.id.secondary_samsung_icon, "setColorFilter", iMainTextColor);
        } else {
            remoteViews.setImageViewBitmap(R.id.primary_graphic, WidgetGraphics.dial(widgetState.primaryValue, iAccentColor, iTrackColor, iMainTextColor, str, fMin));
            remoteViews.setImageViewBitmap(R.id.secondary_graphic, WidgetGraphics.dial(widgetState.secondaryValue, iAccentColor, iTrackColor, iMainTextColor, str, fMin));
        }
        remoteViews.setTextColor(R.id.primary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.secondary_label, iSecondaryColor);
        remoteViews.setTextColor(R.id.primary_reset, iMutedColor);
        remoteViews.setTextColor(R.id.secondary_reset, iMutedColor);
        remoteViews.setTextColor(R.id.updated_label, iFaintColor);
        remoteViews.setTextViewText(R.id.primary_label, "5-hour");
        remoteViews.setTextViewText(R.id.secondary_label, "Weekly");
        remoteViews.setViewVisibility(R.id.primary_label, 8);
        remoteViews.setViewVisibility(R.id.secondary_label, 8);
        remoteViews.setTextViewText(R.id.primary_reset, widgetState.primaryShortReset);
        remoteViews.setTextViewText(R.id.secondary_reset, widgetState.secondaryShortReset);
        remoteViews.setViewVisibility(R.id.primary_reset, 8);
        remoteViews.setViewVisibility(R.id.secondary_reset, 8);
        applyUpdated(remoteViews, widgetOptions, widgetState, iFaintColor);
    }

    private static void renderFourDials(Context context, RemoteViews remoteViews,
            WidgetOptions options, boolean dark, WidgetState state) {
        int accent = WidgetGraphics.accentColor(context, options.accent, dark);
        int track = WidgetGraphics.trackColor(dark);
        int text = WidgetGraphics.mainTextColor(dark);
        remoteViews.setImageViewBitmap(R.id.primary_four_graphic,
                WidgetGraphics.compactDial(context, state.primaryValue, R.drawable.ic_oui_time,
                        accent, track, text,
                        dialValue(state.primaryValue, options.showPercentSymbol), 1.0f));
        remoteViews.setImageViewBitmap(R.id.secondary_four_graphic,
                WidgetGraphics.compactDial(context, state.secondaryValue,
                        R.drawable.ic_oui_calendar_week, accent, track, text,
                        dialValue(state.secondaryValue, options.showPercentSymbol), 1.0f));
        remoteViews.setImageViewBitmap(R.id.reset_time_four_graphic,
                WidgetGraphics.compactDial(context, state.nextResetProgress,
                        R.drawable.ic_oui_alarm, accent, track, text, state.nextResetText, 1.0f));
        ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(context);
        int resetCount = credits == null ? 0 : credits.availableCount;
        int resetProgress = Math.min(100, Math.round((Math.min(4, resetCount) / 4.0f) * 100));
        remoteViews.setImageViewBitmap(R.id.reset_count_four_graphic,
                WidgetGraphics.compactDial(context, resetProgress,
                        R.drawable.ic_oui_refresh, accent, track, text,
                        String.valueOf(resetCount), 1.0f));
    }

    private static void renderBatteryList(Context context, RemoteViews remoteViews,
            WidgetOptions options, boolean dark, WidgetState state) {
        int textColor = WidgetGraphics.mainTextColor(dark);
        int accent = WidgetGraphics.accentColor(context, options.accent, dark);
        int track = WidgetGraphics.trackColor(dark);
        applyProgressColors(remoteViews, R.id.primary_list_progress, accent, track);
        applyProgressColors(remoteViews, R.id.secondary_list_progress, accent, track);
        applyProgressColors(remoteViews, R.id.reset_time_list_progress, accent, track);
        applyProgressColors(remoteViews, R.id.reset_count_list_progress, accent, track);
        remoteViews.setProgressBar(R.id.primary_list_progress, 100,
                Math.max(GRAPHIC_STANDARD, state.primaryValue), false);
        remoteViews.setProgressBar(R.id.secondary_list_progress, 100,
                Math.max(GRAPHIC_STANDARD, state.secondaryValue), false);
        remoteViews.setTextViewText(R.id.primary_list_value,
                state.primaryValue < 0 ? "—" : state.primaryValue + "%");
        remoteViews.setTextViewText(R.id.secondary_list_value,
                state.secondaryValue < 0 ? "—" : state.secondaryValue + "%");
        remoteViews.setTextColor(R.id.primary_list_value, textColor);
        remoteViews.setTextColor(R.id.secondary_list_value, textColor);
        remoteViews.setProgressBar(R.id.reset_time_list_progress, 100,
                state.nextResetProgress, false);
        remoteViews.setTextViewText(R.id.reset_time_list_value, state.nextResetText);
        remoteViews.setTextColor(R.id.reset_time_list_value, textColor);
        ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(context);
        int resetCount = credits == null ? 0 : credits.availableCount;
        remoteViews.setProgressBar(R.id.reset_count_list_progress, Math.max(4, resetCount),
                resetCount, false);
        remoteViews.setTextViewText(R.id.reset_count_list_value, String.valueOf(resetCount));
        remoteViews.setTextColor(R.id.reset_count_list_value, textColor);
        remoteViews.setInt(R.id.primary_list_icon, "setColorFilter", Color.WHITE);
        remoteViews.setInt(R.id.secondary_list_icon, "setColorFilter", Color.WHITE);
        remoteViews.setInt(R.id.reset_time_list_icon, "setColorFilter", Color.WHITE);
        remoteViews.setInt(R.id.reset_count_list_icon, "setColorFilter", Color.WHITE);
        remoteViews.setViewVisibility(R.id.primary_label, View.GONE);
        remoteViews.setViewVisibility(R.id.secondary_label, View.GONE);
        remoteViews.setViewVisibility(R.id.primary_reset, View.GONE);
        remoteViews.setViewVisibility(R.id.secondary_reset, View.GONE);
        remoteViews.setViewVisibility(R.id.updated_label, View.GONE);
    }

    private static String percentText(int value) {
        return value < 0 ? "—" : value + "%";
    }

    private static String dialValue(int value, boolean showPercentSymbol) {
        if (value < 0) {
            return "—";
        }
        return Integer.toString(value) + (showPercentSymbol ? "%" : "");
    }

    private static void applyProgressColors(RemoteViews views, int viewId, int accent, int track) {
        if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(viewId, "setProgressTintList",
                    ColorStateList.valueOf(accent));
            views.setColorStateList(viewId, "setProgressBackgroundTintList",
                    ColorStateList.valueOf(track));
        }
    }

    private static void applyUpdated(RemoteViews remoteViews, WidgetOptions widgetOptions, WidgetState widgetState, int i) {
        remoteViews.setTextColor(R.id.updated_label, i);
        remoteViews.setTextViewText(R.id.updated_label, widgetState.updated);
        remoteViews.setViewVisibility(R.id.updated_label, (!widgetOptions.showUpdated || widgetState.updated.isEmpty()) ? 8 : GRAPHIC_STANDARD);
    }

    private static void applyMetricVisibility(RemoteViews remoteViews, WidgetOptions widgetOptions) {
        remoteViews.setViewVisibility(R.id.primary_section, widgetOptions.showsFiveHour() ? GRAPHIC_STANDARD : 8);
        remoteViews.setViewVisibility(R.id.secondary_section, widgetOptions.showsWeekly() ? GRAPHIC_STANDARD : 8);
    }

    private static void applyResetCreditRow(Context context, RemoteViews remoteViews, int i, WidgetOptions widgetOptions, boolean z, String str) {
        String str2;
        boolean zEquals = STYLE_MICRO.equals(str) || STYLE_BATTERY_LIST.equals(str);
        boolean z2 = widgetOptions.showResetCredits || widgetOptions.showResetAction;
        if (zEquals || !z2) {
            remoteViews.setViewVisibility(R.id.reset_credit_row, 8);
            return;
        }
        ResetCreditsSnapshot resetCreditsSnapshotLoadResetCredits = AppPreferences.loadResetCredits(context);
        int i2 = resetCreditsSnapshotLoadResetCredits == null ? GRAPHIC_STANDARD : resetCreditsSnapshotLoadResetCredits.availableCount;
        long jCurrentTimeMillis = System.currentTimeMillis();
        long jNextExpiryMillis = resetCreditsSnapshotLoadResetCredits == null ? 0L : resetCreditsSnapshotLoadResetCredits.nextExpiryMillis(jCurrentTimeMillis);
        boolean z3 = widgetOptions.showResetCredits;
        boolean z4 = widgetOptions.showResetAction && i2 > 0 && SecureTokenStore.isSignedIn(context);
        if (!z3 && !z4) {
            remoteViews.setViewVisibility(R.id.reset_credit_row, 8);
            return;
        }
        remoteViews.setViewVisibility(R.id.reset_credit_row, GRAPHIC_STANDARD);
        remoteViews.setViewVisibility(R.id.reset_credit_info, z3 ? GRAPHIC_STANDARD : 8);
        remoteViews.setViewVisibility(R.id.reset_credit_button, z4 ? GRAPHIC_STANDARD : 8);
        if (i2 <= 0) {
            str2 = "No reset credits";
        } else if (jNextExpiryMillis > 0) {
            str2 = i2 + " reset" + (i2 == GRAPHIC_LARGE ? "" : "s") + " · expires " + UsageFormat.relative(jNextExpiryMillis, jCurrentTimeMillis);
        } else {
            str2 = i2 + " reset" + (i2 == GRAPHIC_LARGE ? "" : "s") + " available";
        }
        remoteViews.setTextViewText(R.id.reset_credit_info, str2);
        remoteViews.setTextColor(R.id.reset_credit_info, mutedColor(z));
        remoteViews.setTextViewText(R.id.reset_credit_button, i2 > GRAPHIC_LARGE ? "Use reset (" + i2 + ")" : "Use reset");
        remoteViews.setTextColor(R.id.reset_credit_button, WidgetGraphics.mainTextColor(z));
        remoteViews.setInt(R.id.reset_credit_button, "setBackgroundResource", z ? R.drawable.widget_action_dark : R.drawable.widget_action_light);
        remoteViews.setContentDescription(R.id.reset_credit_button, "Use one Codex reset credit. " + str2);
    }

    private static int progressResource(String str, boolean z) {
        if (WidgetOptions.ACCENT_APP.equals(str)) {
            return R.drawable.progress_mono;
        }
        if (WidgetOptions.ACCENT_BLUE.equals(str)) {
            return R.drawable.progress_blue;
        }
        if (WidgetOptions.ACCENT_AMBER.equals(str)) {
            return R.drawable.progress_amber;
        }
        if (WidgetOptions.ACCENT_VIOLET.equals(str)) {
            return R.drawable.progress_violet;
        }
        if (WidgetOptions.ACCENT_ROSE.equals(str)) {
            return R.drawable.progress_rose;
        }
        if (WidgetOptions.ACCENT_CYAN.equals(str)) {
            return R.drawable.progress_cyan;
        }
        if (WidgetOptions.ACCENT_LIME.equals(str)) {
            return R.drawable.progress_lime;
        }
        if (WidgetOptions.ACCENT_MONO.equals(str)) {
            return z ? R.drawable.progress_mono : R.drawable.progress_mono_dark;
        }
        return R.drawable.progress_mint;
    }

    private static void applyAppAccentFilter(Context context, RemoteViews views, String accent,
            int... viewIds) {
        if (!WidgetOptions.ACCENT_APP.equals(accent)) {
            return;
        }
        int color = Ui.accent(context, Ui.isDark(context));
        for (int viewId : viewIds) {
            views.setInt(viewId, "setColorFilter", color);
        }
    }

    private static void applyAppAccentFilter(Context context, RemoteViews views,
            WidgetOptions options, int... viewIds) {
        if (!WidgetOptions.ACCENT_APP.equals(options.accent)) {
            return;
        }
        int color = isMaterial(options)
                ? Ui.materialAccent(context, chooseDark(context, options))
                : Ui.accent(context, Ui.isDark(context));
        for (int viewId : viewIds) {
            views.setInt(viewId, "setColorFilter", color);
        }
    }

    private static int backgroundResource(Context context, boolean z, int i, String str) {
        if (i == 0) {
            return R.drawable.widget_bg_transparent;
        }
        boolean zEquals = WidgetOptions.SURFACE_ONE_UI.equals(str);
        if (zEquals && isSamsung(context)) {
            if (z) {
                if (i == 56) {
                    return R.drawable.widget_bg_samsung_dark_56;
                }
                if (i == 72) {
                    return R.drawable.widget_bg_samsung_dark_72;
                }
                return i == 100 ? R.drawable.widget_bg_samsung_dark_100 : R.drawable.widget_bg_samsung_dark_88;
            }
            if (i == 56) {
                return R.drawable.widget_bg_samsung_light_56;
            }
            if (i == 72) {
                return R.drawable.widget_bg_samsung_light_72;
            }
            return i == 100 ? R.drawable.widget_bg_samsung_light_100 : R.drawable.widget_bg_samsung_light_88;
        }
        if (zEquals && z) {
            if (i == 56) {
                return R.drawable.widget_bg_oneui_dark_56;
            }
            if (i == 72) {
                return R.drawable.widget_bg_oneui_dark_72;
            }
            return i == 100 ? R.drawable.widget_bg_oneui_dark_100 : R.drawable.widget_bg_oneui_dark_88;
        }
        if (zEquals) {
            if (i == 56) {
                return R.drawable.widget_bg_oneui_light_56;
            }
            if (i == 72) {
                return R.drawable.widget_bg_oneui_light_72;
            }
            return i == 100 ? R.drawable.widget_bg_oneui_light_100 : R.drawable.widget_bg_oneui_light_88;
        }
        if (z) {
            if (i == 56) {
                return R.drawable.widget_bg_dark_56;
            }
            if (i == 72) {
                return R.drawable.widget_bg_dark_72;
            }
            return i == 100 ? R.drawable.widget_bg_dark_100 : R.drawable.widget_bg_dark_88;
        }
        if (i == 56) {
            return R.drawable.widget_bg_light_56;
        }
        if (i == 72) {
            return R.drawable.widget_bg_light_72;
        }
        return i == 100 ? R.drawable.widget_bg_light_100 : R.drawable.widget_bg_light_88;
    }

    private static boolean isSamsung(Context context) {
        String str = Build.MANUFACTURER;
        String str2 = Build.BRAND;
        return (str != null && "samsung".equalsIgnoreCase(str)) || (str2 != null && "samsung".equalsIgnoreCase(str2));
    }

    private static boolean isMaterial(WidgetOptions options) {
        return options != null
                && WidgetOptions.SURFACE_MATERIAL.equals(options.surfaceStyle);
    }

    private static int mainTextColor(Context context, WidgetOptions options, boolean dark) {
        return isMaterial(options)
                ? Ui.materialOnSurface(context, dark) : WidgetGraphics.mainTextColor(dark);
    }

    private static int accentColor(Context context, WidgetOptions options, boolean dark) {
        if (isMaterial(options) && WidgetOptions.ACCENT_APP.equals(options.accent)) {
            return Ui.materialAccent(context, dark);
        }
        return WidgetGraphics.accentColor(context, options.accent, dark);
    }

    private static int trackColor(Context context, WidgetOptions options, boolean dark) {
        if (!isMaterial(options)) {
            return WidgetGraphics.trackColor(dark);
        }
        int foreground = Ui.materialOnSurface(context, dark);
        return Color.argb(dark ? 74 : 48, Color.red(foreground), Color.green(foreground),
                Color.blue(foreground));
    }

    private static int secondaryColor(Context context, WidgetOptions options, boolean dark) {
        return isMaterial(options) ? withAlpha(mainTextColor(context, options, dark), 0.82f)
                : secondaryColor(dark);
    }

    private static int mutedColor(Context context, WidgetOptions options, boolean dark) {
        return isMaterial(options) ? withAlpha(mainTextColor(context, options, dark), 0.66f)
                : mutedColor(dark);
    }

    private static int faintColor(Context context, WidgetOptions options, boolean dark) {
        return isMaterial(options) ? withAlpha(mainTextColor(context, options, dark), 0.48f)
                : faintColor(dark);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255.0f * alpha), Color.red(color), Color.green(color),
                Color.blue(color));
    }

    private static int secondaryColor(boolean z) {
        return z ? Color.argb(232, 255, 255, 255) : Color.argb(232, 17, 19, 21);
    }

    private static int mutedColor(boolean z) {
        return z ? Color.argb(165, 255, 255, 255) : Color.argb(165, 17, 19, 21);
    }

    private static int faintColor(boolean z) {
        return z ? Color.argb(112, 255, 255, 255) : Color.argb(112, 17, 19, 21);
    }

    public static String shortReset(Context context, UsageWindow usageWindow, String str, long j) {
        if (usageWindow == null || WidgetOptions.RESET_HIDDEN.equals(str)
                || !usageWindow.showsResetCountdown()) {
            return "";
        }
        long jResetAtMillis = usageWindow.resetAtMillis();
        if (jResetAtMillis <= 0 && usageWindow.resetAfterSeconds > 0) {
            jResetAtMillis = (usageWindow.resetAfterSeconds * 1000) + j;
        }
        if (jResetAtMillis <= 0) {
            return "reset unavailable";
        }
        if (WidgetOptions.RESET_RELATIVE.equals(str)) {
            return UsageFormat.relative(jResetAtMillis, j);
        }
        if ("both".equals(str)) {
            return UsageFormat.absolute(context, jResetAtMillis, j) + " (" + UsageFormat.relative(jResetAtMillis, j) + ")";
        }
        return UsageFormat.absolute(context, jResetAtMillis, j).replace("today at ", "").replace("tomorrow at ", "tomorrow ");
    }

    private static String safeMessage(RuntimeException runtimeException) {
        String message = runtimeException.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return runtimeException.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(GRAPHIC_STANDARD, 180) : message;
    }

    private static final class WidgetState {
        final String combinedReset;
        final String plan;
        final String primaryReset;
        final String primaryShort;
        final String primaryShortReset;
        final String primaryText;
        final int primaryValue;
        final String secondaryReset;
        final String secondaryShort;
        final String secondaryShortReset;
        final String secondaryText;
        final int secondaryValue;
        final String updated;
        final String nextResetText;
        final int nextResetProgress;

        WidgetState(String str, int i, int i2, String str2, String str3, String str4,
                String str5, String str6, String str7, String str8, String str9,
                String str10, String str11, String nextResetText, int nextResetProgress) {
            this.plan = str;
            this.primaryValue = i;
            this.secondaryValue = i2;
            this.primaryText = str2;
            this.secondaryText = str3;
            this.primaryShort = str4;
            this.secondaryShort = str5;
            this.primaryReset = str6;
            this.secondaryReset = str7;
            this.primaryShortReset = str8;
            this.secondaryShortReset = str9;
            this.combinedReset = str10;
            this.updated = str11;
            this.nextResetText = nextResetText;
            this.nextResetProgress = Math.max(0, Math.min(100, nextResetProgress));
        }

        static WidgetState from(Context context, WidgetOptions widgetOptions) {
            String str;
            boolean zIsSignedIn = SecureTokenStore.isSignedIn(context);
            UsageSnapshot usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(context);
            long jCurrentTimeMillis = System.currentTimeMillis();
            if (!zIsSignedIn) {
                return new WidgetState("", -1, -1, "Sign in", "—", "SIGN IN", "—", "Open the app to connect ChatGPT", "", "Tap to connect", "", "Open the app to connect ChatGPT", "Tap anywhere to sign in", "—", 0);
            }
            if (usageSnapshotLoadSnapshot == null) {
                String lastError = AppPreferences.getLastError(context);
                if (lastError.isEmpty()) {
                    lastError = "Tap refresh to load usage";
                }
                return new WidgetState("", -1, -1, "Loading…", "—", "…", "—", lastError, "", lastError, "", lastError, "Waiting for the first update", "—", 0);
            }
            boolean zEquals = WidgetOptions.DISPLAY_USED.equals(widgetOptions.displayMode);
            int iValue = value(usageSnapshotLoadSnapshot.fiveHour, zEquals);
            int iValue2 = value(usageSnapshotLoadSnapshot.weekly, zEquals);
            String strPercent = UsageFormat.percent(usageSnapshotLoadSnapshot.fiveHour, widgetOptions.displayMode, false);
            String strPercent2 = UsageFormat.percent(usageSnapshotLoadSnapshot.weekly, widgetOptions.displayMode, false);
            String strPercent3 = UsageFormat.percent(usageSnapshotLoadSnapshot.fiveHour, widgetOptions.displayMode, true);
            String strPercent4 = UsageFormat.percent(usageSnapshotLoadSnapshot.weekly, widgetOptions.displayMode, true);
            String strReset = UsageFormat.reset(context, usageSnapshotLoadSnapshot.fiveHour, widgetOptions.resetMode, jCurrentTimeMillis);
            String strReset2 = UsageFormat.reset(context, usageSnapshotLoadSnapshot.weekly, widgetOptions.resetMode, jCurrentTimeMillis);
            String strShortReset = WidgetRenderer.shortReset(context, usageSnapshotLoadSnapshot.fiveHour, widgetOptions.resetMode, jCurrentTimeMillis);
            String strShortReset2 = WidgetRenderer.shortReset(context, usageSnapshotLoadSnapshot.weekly, widgetOptions.resetMode, jCurrentTimeMillis);
            if (strShortReset.isEmpty()) {
                str = strShortReset2.isEmpty() ? "" : "Week " + strShortReset2;
            } else {
                str = strShortReset2.isEmpty() ? "5h " + strShortReset : "5h " + strShortReset + " · Week " + strShortReset2;
            }
            String strUpdated = UsageFormat.updated(usageSnapshotLoadSnapshot.fetchedAtMillis, jCurrentTimeMillis);
            String str2 = jCurrentTimeMillis - usageSnapshotLoadSnapshot.fetchedAtMillis > TimeUnit.HOURS.toMillis(6L) ? strUpdated + " · cached" : strUpdated;
            String strPlanLabel = UsageFormat.planLabel(usageSnapshotLoadSnapshot.planType);
            if (!AppPreferences.getLastError(context).isEmpty() && jCurrentTimeMillis - usageSnapshotLoadSnapshot.fetchedAtMillis > TimeUnit.MINUTES.toMillis(20L)) {
                str2 = str2 + " · refresh issue";
            }
            ResetCountdown countdown = nextReset(usageSnapshotLoadSnapshot, jCurrentTimeMillis);
            return new WidgetState(strPlanLabel, iValue, iValue2, strPercent, strPercent2,
                    strPercent3, strPercent4, strReset, strReset2, strShortReset,
                    strShortReset2, str, str2, countdown.text, countdown.progress);
        }

        static WidgetState error(String str) {
            return new WidgetState("", -1, -1, "—", "—", "—", "—", str, "", str,
                    "", str, "Open the app", "—", 0);
        }

        private static ResetCountdown nextReset(UsageSnapshot snapshot, long now) {
            UsageWindow fiveHour = snapshot == null ? null : snapshot.fiveHour;
            UsageWindow weekly = snapshot == null ? null : snapshot.weekly;
            long fiveHourReset = (fiveHour != null && fiveHour.showsResetCountdown())
                    ? resetAt(fiveHour, now) : 0L;
            long weeklyReset = (weekly != null && weekly.showsResetCountdown())
                    ? resetAt(weekly, now) : 0L;
            long resetAt;
            long windowDuration;
            if (fiveHourReset > now && (weeklyReset <= now || fiveHourReset <= weeklyReset)) {
                resetAt = fiveHourReset;
                windowDuration = TimeUnit.HOURS.toMillis(5L);
            } else if (weeklyReset > now) {
                resetAt = weeklyReset;
                windowDuration = TimeUnit.DAYS.toMillis(7L);
            } else {
                return new ResetCountdown("—", 0);
            }
            long remaining = Math.max(0L, resetAt - now);
            int progress = (int) Math.max(0L, Math.min(100L,
                    Math.round((remaining * 100.0d) / windowDuration)));
            String text = UsageFormat.relative(resetAt, now);
            if (text.startsWith("in ")) {
                text = text.substring(3);
            }
            return new ResetCountdown(text, progress);
        }

        private static long resetAt(UsageWindow window, long now) {
            if (window == null) {
                return 0L;
            }
            long value = window.resetAtMillis();
            if (value <= 0L && window.resetAfterSeconds > 0L) {
                value = now + TimeUnit.SECONDS.toMillis(window.resetAfterSeconds);
            }
            return value;
        }

        private static final class ResetCountdown {
            final String text;
            final int progress;

            ResetCountdown(String text, int progress) {
                this.text = text;
                this.progress = progress;
            }
        }

        private static int value(UsageWindow usageWindow, boolean z) {
            if (usageWindow == null) {
                return -1;
            }
            return z ? usageWindow.usedPercent : usageWindow.remainingPercent();
        }
    }
}
