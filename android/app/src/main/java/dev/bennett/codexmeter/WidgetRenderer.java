package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetRenderer {
    private static final int GRAPHIC_LARGE = 1;
    private static final int GRAPHIC_MAX = 2;
    private static final int GRAPHIC_STANDARD = 0;
    private static final String STYLE_BATTERY_LIST = WidgetMeters.VISUAL_BATTERY_LIST;
    private static final String STYLE_FOUR_DIALS = WidgetMeters.VISUAL_FOUR_DIALS;
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
                DiagnosticLog.info(context, "widget", "update_all_started",
                        "home_widget_count", appWidgetIds.length,
                        "lock_widget_count", SamsungLockWidgetSupport.countAll(context));
                for (int widgetId : appWidgetIds) {
                    update(context, appWidgetManager, widgetId);
                }
                SamsungLockWidgetSupport.updateAll(context);
                DiagnosticLog.info(context, "widget", "update_all_finished");
            } catch (RuntimeException e) {
                DiagnosticLog.error(context, "widget", "update_all_failed", e);
                Log.w("CodexMeterWidget", "Widget update failed: " + safeMessage(e));
            }
        }
    }

    public static void update(Context context, AppWidgetManager appWidgetManager, int widgetId) {
        RemoteViews views;
        if (context != null && appWidgetManager != null && widgetId != 0) {
            try {
                WidgetOptions options = AppPreferences.loadWidgetOptions(context, widgetId);
                Bundle appWidgetOptions = appWidgetManager.getAppWidgetOptions(widgetId);
                // Liquid Glass: the widget sits on Glassé's glass once it is baked (null until then).
                Bitmap glass = WidgetGlass.forWidget(context, appWidgetManager, widgetId,
                        options, chooseDark(context, options));
                if (Build.VERSION.SDK_INT >= 31) {
                    views = buildResponsiveWidget(context, widgetId, options, glass);
                } else {
                    views = onGlass(context, buildViews(context, widgetId, options,
                            styleForSize(context, appWidgetOptions, options,
                                    ProviderPreferences.getWidgetProviders(context, widgetId)),
                            appWidgetOptions), glass);
                }
                appWidgetManager.updateAppWidget(widgetId, views);
            } catch (RuntimeException e) {
                DiagnosticLog.error(context, "widget", "render_failed", e,
                        "widget_id", widgetId);
                Log.w("CodexMeterWidget", "Widget render failed: " + safeMessage(e));
                try {
                    appWidgetManager.updateAppWidget(widgetId, buildFallback(context, widgetId));
                } catch (RuntimeException e2) {
                    DiagnosticLog.error(context, "widget", "fallback_render_failed", e2,
                            "widget_id", widgetId);
                }
            }
        }
    }

    private static RemoteViews onGlass(Context context, RemoteViews views, Bitmap glass) {
        return glass == null ? views : WidgetGlass.wrap(context, views, glass);
    }

    @SuppressLint({"NewApi", "UseRequiresApi"})
    private static RemoteViews buildResponsiveWidget(Context context, int widgetId, WidgetOptions widgetOptions,
            Bitmap glass) {
        LinkedHashMap<SizeF, RemoteViews> linkedHashMap = new LinkedHashMap<>();
        List<UsageProvider> providers = ProviderPreferences.getWidgetProviders(context, widgetId);
        boolean single = providers.size() == 1 && widgetOptions.singleMetric();
        String compactStyle = preferVisualStyle(widgetOptions, WidgetMeters.VISUAL_RINGS, single);
        String wideShortStyle = preferVisualStyle(widgetOptions,
                single ? WidgetMeters.VISUAL_RINGS : WidgetMeters.VISUAL_FOUR_DIALS, single);
        String tallStyle = preferVisualStyle(widgetOptions,
                single ? WidgetMeters.VISUAL_DIALS : WidgetMeters.VISUAL_BATTERY_LIST, single);
        linkedHashMap.put(new SizeF(110.0f, 60.0f), onGlass(context,
                buildViews(context, widgetId, widgetOptions, compactStyle,
                        sizeBundle(110, 70), GRAPHIC_STANDARD, providers), glass));
        linkedHashMap.put(new SizeF(250.0f, 60.0f), onGlass(context,
                buildViews(context, widgetId, widgetOptions, wideShortStyle,
                        sizeBundle(250, 70), GRAPHIC_STANDARD, providers), glass));
        linkedHashMap.put(new SizeF(110.0f, 130.0f), onGlass(context,
                buildViews(context, widgetId, widgetOptions, tallStyle,
                        sizeBundle(110, 156), GRAPHIC_STANDARD, providers), glass));
        linkedHashMap.put(new SizeF(250.0f, 130.0f), onGlass(context,
                buildViews(context, widgetId, widgetOptions, tallStyle,
                        sizeBundle(250, 156),
                        single ? GRAPHIC_LARGE : GRAPHIC_STANDARD, providers), glass));
        return new RemoteViews(linkedHashMap);
    }

    static RemoteViews buildPreview(Context context, int appWidgetId, WidgetOptions widgetOptions,
            Bundle appWidgetOptions) {
        return buildPreview(context, appWidgetId, widgetOptions, appWidgetOptions,
                ProviderPreferences.getWidgetProviders(context, appWidgetId));
    }

    /** Preview for the configuration screen, showing the providers picked but not yet saved. */
    static RemoteViews buildPreview(Context context, int appWidgetId, WidgetOptions widgetOptions,
            Bundle appWidgetOptions, List<UsageProvider> providers) {
        String style = styleForSize(context, appWidgetOptions, widgetOptions, providers);
        RemoteViews preview = buildViews(context, appWidgetId, widgetOptions, style,
                appWidgetOptions, resolveGraphicTier(context, widgetOptions, appWidgetOptions),
                providers);
        preview.setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT);
        return preview;
    }

    private static String styleForSize(Context context, Bundle bundle, WidgetOptions options,
            List<UsageProvider> providers) {
        int rows = option(bundle, "semAppWidgetRowSpan");
        int columns = option(bundle, "semAppWidgetColumnSpan");
        boolean single = options != null && options.singleMetric()
                && (providers == null || providers.size() <= 1);
        return WidgetMeters.resolveHomeVisualStyle(
                options == null ? WidgetMeters.PREF_AUTO : options.layoutPreference(),
                single, rows, columns, currentHeight(context, bundle),
                currentWidth(context, bundle));
    }

    /** Applies Auto / Dials / Bars preference on top of a size-derived auto bucket style. */
    private static String preferVisualStyle(WidgetOptions options, String autoBucketStyle,
            boolean single) {
        String preference = options == null
                ? WidgetMeters.PREF_AUTO : options.layoutPreference();
        if (WidgetMeters.PREF_BARS.equals(preference)) {
            return WidgetMeters.VISUAL_BATTERY_LIST;
        }
        if (WidgetMeters.PREF_DIALS.equals(preference)
                && WidgetMeters.VISUAL_BATTERY_LIST.equals(autoBucketStyle)) {
            return single ? WidgetMeters.VISUAL_DIALS : WidgetMeters.VISUAL_FOUR_DIALS;
        }
        return autoBucketStyle;
    }

    private static Bundle sizeBundle(int width, int height) {
        Bundle bundle = new Bundle();
        bundle.putInt("appWidgetMinWidth", width);
        bundle.putInt("appWidgetMinHeight", height);
        return bundle;
    }

    private static RemoteViews buildViews(Context context, int widgetId, WidgetOptions widgetOptions, String style, Bundle bundle) {
        return buildViews(context, widgetId, widgetOptions, style, bundle, resolveGraphicTier(context, widgetOptions, bundle));
    }

    private static RemoteViews buildViews(Context context, int widgetId, WidgetOptions widgetOptions, String style, Bundle bundle, int tier) {
        return buildViews(context, widgetId, widgetOptions, style, bundle, tier,
                ProviderPreferences.getWidgetProviders(context, widgetId));
    }

    /**
     * One widget layout for one or more providers. With several providers each slot shows one
     * provider, marked by its icon, so the battery list reads like Samsung's battery widget.
     */
    private static RemoteViews buildViews(Context context, int widgetId, WidgetOptions widgetOptions,
            String style, Bundle bundle, int tier, List<UsageProvider> providers) {
        UsageProvider provider = providers == null || providers.isEmpty()
                ? UsageProvider.DEFAULT : providers.get(0);
        boolean multi = providers != null && providers.size() > 1;
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), layoutForStyle(style, tier));
        boolean dark = chooseDark(context, widgetOptions);
        WidgetState state = WidgetState.from(context, widgetOptions, provider);
        List<MeterSlot> slots = multi
                ? resolveMultiSlots(context, widgetOptions, style, bundle, providers)
                : resolveSlots(context, widgetOptions, state, style, bundle, provider);
        applyRootAndHeader(context, remoteViews, widgetId, widgetOptions, dark, state);
        remoteViews.setTextViewText(R.id.widget_title, multi ? "USAGE"
                : provider == UsageProvider.CODEX ? context.getString(R.string.widget_title)
                : provider.accountName.toUpperCase(java.util.Locale.ROOT));
        if (STYLE_MICRO.equals(style)) {
            renderMicro(remoteViews, widgetOptions, dark, state, slots);
        } else if (WidgetOptions.STYLE_RINGS.equals(style)) {
            renderGraphic(context, remoteViews, widgetOptions, dark, state, true, tier, slots);
        } else if (STYLE_FOUR_DIALS.equals(style)) {
            renderFourDials(context, remoteViews, widgetOptions, dark, slots);
        } else if (STYLE_BATTERY_LIST.equals(style)) {
            renderBatteryList(context, remoteViews, widgetOptions, dark, slots);
        } else if (WidgetOptions.STYLE_DIALS.equals(style)) {
            renderGraphic(context, remoteViews, widgetOptions, dark, state, false, tier, slots);
        } else if (WidgetOptions.STYLE_MINIMAL.equals(style)) {
            renderMinimal(context, remoteViews, widgetOptions, dark, state, slots);
        } else {
            renderBars(context, remoteViews, widgetOptions, dark, state, bundle, slots);
        }
        applySlotVisibility(remoteViews, style, slots);
        if (ResetAlertPreferences.hasResets(provider) && !multi) {
            applyResetCreditRow(context, remoteViews, widgetId, widgetOptions, dark, style,
                    provider);
        } else {
            // Only Codex and Claude Code bank resets; a widget of several providers has no room.
            remoteViews.setViewVisibility(R.id.reset_credit_row, View.GONE);
        }
        return remoteViews;
    }

    private static RemoteViews buildFallback(Context context, int widgetId) {
        WidgetOptions defaults = WidgetOptions.defaults();
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget_compact);
        boolean dark = chooseDark(context, defaults);
        WidgetState state = WidgetState.error("Open Codex Meter to recover");
        applyRootAndHeader(context, remoteViews, widgetId, defaults, dark, state);
        renderMinimal(context, remoteViews, defaults, dark, state,
                java.util.Collections.emptyList());
        return remoteViews;
    }

    private static String resolveStyle(Context context, WidgetOptions widgetOptions, Bundle bundle) {
        int width = currentWidth(context, bundle);
        int height = currentHeight(context, bundle);
        String style = WidgetOptions.normalizeStyle(widgetOptions.layout);
        if ((height <= 0 || height >= 90) && (width <= 0 || width >= 145)) {
            return "auto".equals(style) ? ((height <= 0 || height >= 140) && (width <= 0 || width >= 215)) ? WidgetOptions.STYLE_BARS : WidgetOptions.STYLE_MINIMAL : (height <= 0 || height >= 130) ? style : WidgetOptions.STYLE_MINIMAL;
        }
        return STYLE_MICRO;
    }

    private static int option(Bundle bundle, String key) {
        return bundle == null ? 0 : bundle.getInt(key, 0);
    }

    private static int currentWidth(Context context, Bundle bundle) {
        int minSize = option(bundle, "appWidgetMinWidth");
        int maxSize = option(bundle, "appWidgetMaxWidth");
        return (context == null || context.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) ? minSize > 0 ? minSize : maxSize : maxSize > 0 ? maxSize : minSize;
    }

    private static int currentHeight(Context context, Bundle bundle) {
        int minSize = option(bundle, "appWidgetMinHeight");
        int maxSize = option(bundle, "appWidgetMaxHeight");
        return (context == null || context.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) ? maxSize > 0 ? maxSize : minSize : minSize > 0 ? minSize : maxSize;
    }

    private static int layoutForStyle(String style, int tier) {
        if (STYLE_BATTERY_LIST.equals(style)) {
            return R.layout.widget_battery_list;
        }
        if (STYLE_FOUR_DIALS.equals(style)) {
            return R.layout.widget_rings_four;
        }
        if (STYLE_MICRO.equals(style)) {
            return R.layout.widget_micro;
        }
        if (WidgetOptions.STYLE_RINGS.equals(style)) {
            // Samsung's battery widget keeps the 46dp small arc at every 2x1 host size.
            return R.layout.widget_rings;
        }
        if (!WidgetOptions.STYLE_DIALS.equals(style)) {
            return WidgetOptions.STYLE_MINIMAL.equals(style) ? R.layout.widget_compact : R.layout.widget_detailed;
        }
        if (tier == GRAPHIC_MAX) {
            return R.layout.widget_dials_max;
        }
        return tier == GRAPHIC_LARGE ? R.layout.widget_dials_large : R.layout.widget_dials;
    }

    private static int resolveGraphicTier(Context context, WidgetOptions widgetOptions, Bundle bundle) {
        int width = currentWidth(context, bundle);
        int height = currentHeight(context, bundle);
        if (widgetOptions.singleMetric() && WidgetOptions.GRAPHIC_AUTO.equals(
                widgetOptions.graphicScale) && width > 0 && height > 0) {
            if (width >= 340 && height >= 178) return GRAPHIC_MAX;
            if (width >= 230 && height >= 130) return GRAPHIC_LARGE;
            return GRAPHIC_STANDARD;
        }
        return (width <= 0 || height <= 0) ? (WidgetOptions.GRAPHIC_MAX.equals(widgetOptions.graphicScale) || WidgetOptions.GRAPHIC_LARGE.equals(widgetOptions.graphicScale)) ? GRAPHIC_LARGE : GRAPHIC_STANDARD : WidgetOptions.GRAPHIC_MAX.equals(widgetOptions.graphicScale) ? (width < 340 || height < 198) ? (width < 270 || height < 158) ? GRAPHIC_STANDARD : GRAPHIC_LARGE : GRAPHIC_MAX : WidgetOptions.GRAPHIC_LARGE.equals(widgetOptions.graphicScale) ? (width < 400 || height < 208) ? (width < 290 || height < 164) ? GRAPHIC_STANDARD : GRAPHIC_LARGE : GRAPHIC_MAX : (width < 460 || height < 220) ? (width < 340 || height < 178) ? GRAPHIC_STANDARD : GRAPHIC_LARGE : GRAPHIC_MAX;
    }

    private static boolean chooseDark(Context context, WidgetOptions widgetOptions) {
        if (WidgetOptions.THEME_DARK.equals(widgetOptions.theme)) {
            return true;
        }
        return !WidgetOptions.THEME_LIGHT.equals(widgetOptions.theme) && (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    private static void applyRootAndHeader(Context context, RemoteViews remoteViews, int widgetId, WidgetOptions widgetOptions, boolean dark, WidgetState widgetState) {
        if (WidgetOptions.SURFACE_ONE_UI.equals(widgetOptions.surfaceStyle) && isSamsung(context)) {
            int alpha = Math.round(Math.max(0, Math.min(100, widgetOptions.opacity)) * 2.55f);
            remoteViews.setInt(android.R.id.background, "setBackgroundColor",
                    dark ? Color.argb(alpha, 0, 0, 0) : Color.argb(alpha, 255, 255, 255));
        } else {
            remoteViews.setInt(android.R.id.background, "setBackgroundResource",
                    backgroundResource(context, dark, widgetOptions.opacity, widgetOptions.surfaceStyle));
        }
        remoteViews.setTextColor(R.id.widget_title, WidgetGraphics.mainTextColor(dark));
        remoteViews.setViewVisibility(R.id.widget_title, widgetOptions.showTitle ? View.VISIBLE : View.GONE);
        remoteViews.setTextColor(R.id.plan_label, mutedColor(dark));
        remoteViews.setTextViewText(R.id.plan_label, widgetState.plan);
        remoteViews.setViewVisibility(R.id.plan_label, (!widgetOptions.showPlan || widgetState.plan.isEmpty()) ? View.GONE : View.VISIBLE);
        remoteViews.setImageViewResource(R.id.refresh_button, dark ? R.drawable.ic_oui_refresh_widget_light : R.drawable.ic_oui_refresh_widget_dark);
        remoteViews.setViewVisibility(R.id.refresh_button, widgetOptions.showRefresh ? View.VISIBLE : View.GONE);
        applyIntents(context, remoteViews, widgetId);
    }

    private static void applyIntents(Context context, RemoteViews remoteViews, int widgetId) {
        String tapAction = AppPreferences.getWidgetTapAction(context, widgetId);
        PendingIntent rootAction;
        if (WidgetOptions.TAP_REFRESH.equals(tapAction)) {
            rootAction = PendingIntent.getBroadcast(context, 74000 + widgetId,
                    new Intent(context, (Class<?>) WidgetRefreshReceiver.class)
                            .setAction(AppConstants.ACTION_REFRESH_WIDGET)
                            .setData(widgetUri(widgetId, "root-refresh"))
                            .putExtra("appWidgetId", widgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (WidgetOptions.TAP_USE_RESET.equals(tapAction)) {
            rootAction = PendingIntent.getActivity(context, 74000 + widgetId,
                    new Intent(context, (Class<?>) ResetCreditActivity.class)
                            .setAction("dev.bennett.codexmeter.action.WIDGET_RESET")
                            .setData(widgetUri(widgetId, "root-reset"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            // Opens the app as the user left it, never a particular provider's tab.
            rootAction = PendingIntent.getActivity(context, 74000 + widgetId,
                    AppOpenActivity.widgetIntent(context, widgetUri(widgetId, "root-open")),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        remoteViews.setOnClickPendingIntent(android.R.id.background, rootAction);
        remoteViews.setOnClickPendingIntent(R.id.refresh_button,
                PendingIntent.getBroadcast(context, 75000 + widgetId,
                        new Intent(context, (Class<?>) WidgetRefreshReceiver.class)
                                .setAction(AppConstants.ACTION_REFRESH_WIDGET)
                                .setData(widgetUri(widgetId, "refresh"))
                                .putExtra("appWidgetId", widgetId),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        remoteViews.setOnClickPendingIntent(R.id.reset_credit_button,
                PendingIntent.getActivity(context, 76000 + widgetId,
                        new Intent(context, (Class<?>) ResetCreditActivity.class)
                                .setAction("dev.bennett.codexmeter.action.WIDGET_RESET")
                                .setData(widgetUri(widgetId, "reset"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static Uri widgetUri(int appWidgetId, String action) {
        return Uri.parse("codexmeter://widget/home/v" + AppConstants.VERSION_CODE + "/"
                + appWidgetId + "/" + action);
    }

    private static void renderBars(Context context, RemoteViews remoteViews, WidgetOptions widgetOptions, boolean dark, WidgetState widgetState, Bundle bundle, List<MeterSlot> slots) {
        int textColor = WidgetGraphics.mainTextColor(dark);
        int subtextColor = secondaryColor(dark);
        int muted = mutedColor(dark);
        int faint = faintColor(dark);
        remoteViews.setTextColor(R.id.primary_label, subtextColor);
        remoteViews.setTextColor(R.id.secondary_label, subtextColor);
        remoteViews.setTextColor(R.id.primary_percent, textColor);
        remoteViews.setTextColor(R.id.secondary_percent, textColor);
        remoteViews.setTextColor(R.id.primary_reset, muted);
        remoteViews.setTextColor(R.id.secondary_reset, muted);
        remoteViews.setTextColor(R.id.updated_label, faint);
        remoteViews.setImageViewResource(R.id.primary_track, dark ? R.drawable.progress_track : R.drawable.progress_track_light);
        remoteViews.setImageViewResource(R.id.secondary_track, dark ? R.drawable.progress_track : R.drawable.progress_track_light);
        int progressDrawable = progressResource(widgetOptions.accent, dark);
        remoteViews.setImageViewResource(R.id.primary_progress, progressDrawable);
        remoteViews.setImageViewResource(R.id.secondary_progress, progressDrawable);
        applyAppAccentFilter(context, remoteViews, widgetOptions.accent,
                R.id.primary_progress, R.id.secondary_progress);
        MeterSlot primary = slotAt(slots, 0);
        MeterSlot secondary = slotAt(slots, 1);
        remoteViews.setInt(R.id.primary_progress, "setImageLevel",
                Math.max(0, primary == null ? 0 : primary.progress) * 100);
        remoteViews.setInt(R.id.secondary_progress, "setImageLevel",
                Math.max(0, secondary == null ? 0 : secondary.progress) * 100);
        remoteViews.setTextViewText(R.id.primary_label, primary == null ? "" : primary.label);
        remoteViews.setTextViewText(R.id.secondary_label, secondary == null ? "" : secondary.label);
        remoteViews.setTextViewText(R.id.primary_percent, primary == null ? "—" : primary.valueText);
        remoteViews.setTextViewText(R.id.secondary_percent, secondary == null ? "—" : secondary.valueText);
        remoteViews.setTextViewText(R.id.primary_reset, widgetState.primaryReset);
        remoteViews.setTextViewText(R.id.secondary_reset, widgetState.secondaryReset);
        remoteViews.setViewVisibility(R.id.primary_reset, widgetState.primaryReset.isEmpty() ? View.GONE : View.VISIBLE);
        remoteViews.setViewVisibility(R.id.secondary_reset, widgetState.secondaryReset.isEmpty() ? View.GONE : View.VISIBLE);
        applyUpdated(remoteViews, widgetOptions, widgetState, faint);
        int height = currentHeight(context, bundle);
        boolean compact = "compact".equals(widgetOptions.density)
                || ("auto".equals(widgetOptions.density) && height > 0 && height < 145);
        if (compact) {
            remoteViews.setTextViewTextSize(R.id.primary_percent, TypedValue.COMPLEX_UNIT_SP, 16.0f);
            remoteViews.setTextViewTextSize(R.id.secondary_percent, TypedValue.COMPLEX_UNIT_SP, 16.0f);
            remoteViews.setTextViewTextSize(R.id.primary_reset, TypedValue.COMPLEX_UNIT_SP, 9.0f);
            remoteViews.setTextViewTextSize(R.id.secondary_reset, TypedValue.COMPLEX_UNIT_SP, 9.0f);
            return;
        }
        if (WidgetOptions.DENSITY_COMFORTABLE.equals(widgetOptions.density)) {
            remoteViews.setTextViewTextSize(R.id.primary_percent, TypedValue.COMPLEX_UNIT_SP, 21.0f);
            remoteViews.setTextViewTextSize(R.id.secondary_percent, TypedValue.COMPLEX_UNIT_SP, 21.0f);
        }
    }

    private static void renderMicro(RemoteViews remoteViews, WidgetOptions widgetOptions, boolean dark,
            WidgetState widgetState, List<MeterSlot> slots) {
        int textColor = WidgetGraphics.mainTextColor(dark);
        int subtextColor = secondaryColor(dark);
        MeterSlot primary = slotAt(slots, 0);
        MeterSlot secondary = slotAt(slots, 1);
        remoteViews.setTextColor(R.id.primary_label, subtextColor);
        remoteViews.setTextColor(R.id.secondary_label, subtextColor);
        remoteViews.setTextColor(R.id.primary_percent, textColor);
        remoteViews.setTextColor(R.id.secondary_percent, textColor);
        remoteViews.setTextViewText(R.id.primary_label, primary == null ? "" : primary.label);
        remoteViews.setTextViewText(R.id.secondary_label, secondary == null ? "" : secondary.label);
        remoteViews.setTextViewText(R.id.primary_percent, primary == null ? "—" : primary.shortText);
        remoteViews.setTextViewText(R.id.secondary_percent, secondary == null ? "—" : secondary.shortText);
        remoteViews.setViewVisibility(R.id.primary_reset, View.GONE);
        remoteViews.setViewVisibility(R.id.secondary_reset, View.GONE);
        remoteViews.setViewVisibility(R.id.updated_label, View.GONE);
    }

    private static void renderMinimal(Context context, RemoteViews remoteViews, WidgetOptions widgetOptions, boolean dark, WidgetState widgetState, List<MeterSlot> slots) {
        String resetText;
        int trackDrawable = R.drawable.progress_track_light;
        int textColor = WidgetGraphics.mainTextColor(dark);
        int subtextColor = secondaryColor(dark);
        int muted = mutedColor(dark);
        int faint = faintColor(dark);
        MeterSlot primary = slotAt(slots, 0);
        MeterSlot secondary = slotAt(slots, 1);
        remoteViews.setTextColor(R.id.primary_label, subtextColor);
        remoteViews.setTextColor(R.id.secondary_label, subtextColor);
        remoteViews.setTextColor(R.id.primary_percent, textColor);
        remoteViews.setTextColor(R.id.secondary_percent, textColor);
        remoteViews.setTextColor(R.id.primary_reset, muted);
        remoteViews.setTextColor(R.id.updated_label, faint);
        remoteViews.setImageViewResource(R.id.primary_track, dark ? R.drawable.progress_track : R.drawable.progress_track_light);
        if (dark) {
            trackDrawable = R.drawable.progress_track;
        }
        remoteViews.setImageViewResource(R.id.secondary_track, trackDrawable);
        int progressDrawable = progressResource(widgetOptions.accent, dark);
        remoteViews.setImageViewResource(R.id.primary_progress, progressDrawable);
        remoteViews.setImageViewResource(R.id.secondary_progress, progressDrawable);
        applyAppAccentFilter(context, remoteViews, widgetOptions.accent,
                R.id.primary_progress, R.id.secondary_progress);
        remoteViews.setInt(R.id.primary_progress, "setImageLevel",
                Math.max(0, primary == null ? 0 : primary.progress) * 100);
        remoteViews.setInt(R.id.secondary_progress, "setImageLevel",
                Math.max(0, secondary == null ? 0 : secondary.progress) * 100);
        remoteViews.setTextViewText(R.id.primary_label, primary == null ? "" : primary.label);
        remoteViews.setTextViewText(R.id.secondary_label, secondary == null ? "" : secondary.label);
        remoteViews.setTextViewText(R.id.primary_percent, primary == null ? "—" : primary.shortText);
        remoteViews.setTextViewText(R.id.secondary_percent, secondary == null ? "—" : secondary.shortText);
        if ("five_hour".equals(widgetOptions.metricMode)) {
            resetText = widgetState.primaryShortReset.isEmpty() ? "" : "5h " + widgetState.primaryShortReset;
        } else if ("weekly".equals(widgetOptions.metricMode)) {
            resetText = widgetState.secondaryShortReset.isEmpty() ? ""
                    : widgetState.secondaryName + " " + widgetState.secondaryShortReset;
        } else {
            resetText = widgetState.combinedReset;
        }
        remoteViews.setTextViewText(R.id.primary_reset, resetText);
        remoteViews.setViewVisibility(R.id.primary_reset, resetText.isEmpty() ? View.GONE : View.VISIBLE);
        remoteViews.setViewVisibility(R.id.secondary_reset, View.GONE);
        applyUpdated(remoteViews, widgetOptions, widgetState, faint);
    }

    private static void renderGraphic(Context context, RemoteViews remoteViews, WidgetOptions widgetOptions, boolean dark, WidgetState widgetState, boolean rings, int tier, List<MeterSlot> slots) {
        float scale;
        int textColor = WidgetGraphics.mainTextColor(dark);
        int subtextColor = secondaryColor(dark);
        int muted = mutedColor(dark);
        int faint = faintColor(dark);
        int accentColor = WidgetGraphics.accentColor(context, widgetOptions.accent, dark);
        int trackColor = WidgetGraphics.trackColor(dark);
        String usageWord = WidgetOptions.DISPLAY_USED.equals(widgetOptions.displayMode) ? WidgetOptions.DISPLAY_USED : "left";
        if (tier == GRAPHIC_MAX) {
            scale = 1.36f;
        } else {
            scale = tier == GRAPHIC_LARGE ? 1.24f : 1.0f;
        }
        float textScale = widgetOptions.singleMetric() ? Math.min(1.36f, scale * 1.16f) : scale;
        MeterSlot primary = slotAt(slots, 0);
        MeterSlot secondary = slotAt(slots, 1);
        int primaryProgress = primary == null ? -1 : primary.progress;
        int secondaryProgress = secondary == null ? -1 : secondary.progress;
        if (rings) {
            applyProgressColors(remoteViews, R.id.primary_samsung_progress,
                    accentColor, trackColor);
            applyProgressColors(remoteViews, R.id.secondary_samsung_progress,
                    accentColor, trackColor);
            remoteViews.setProgressBar(R.id.primary_samsung_progress, 100,
                    Math.max(0, primaryProgress), false);
            remoteViews.setProgressBar(R.id.secondary_samsung_progress, 100,
                    Math.max(0, secondaryProgress), false);
            remoteViews.setTextViewText(R.id.primary_samsung_value,
                    primary == null ? "—" : primary.valueText);
            remoteViews.setTextViewText(R.id.secondary_samsung_value,
                    secondary == null ? "—" : secondary.valueText);
            remoteViews.setTextColor(R.id.primary_samsung_value, textColor);
            remoteViews.setTextColor(R.id.secondary_samsung_value, textColor);
            if (primary != null) {
                remoteViews.setImageViewResource(R.id.primary_samsung_icon, primary.iconRes);
            }
            if (secondary != null) {
                remoteViews.setImageViewResource(R.id.secondary_samsung_icon, secondary.iconRes);
            }
            remoteViews.setInt(R.id.primary_samsung_icon, "setColorFilter", textColor);
            remoteViews.setInt(R.id.secondary_samsung_icon, "setColorFilter", textColor);
        } else {
            remoteViews.setImageViewBitmap(R.id.primary_graphic,
                    WidgetGraphics.dial(primaryProgress, accentColor, trackColor, textColor,
                            primary == null ? "—" : primary.valueText,
                            primary == null || primary.usage ? usageWord : primary.label, textScale));
            remoteViews.setImageViewBitmap(R.id.secondary_graphic,
                    WidgetGraphics.dial(secondaryProgress, accentColor, trackColor, textColor,
                            secondary == null ? "—" : secondary.valueText,
                            secondary == null || secondary.usage ? usageWord : secondary.label, textScale));
        }
        remoteViews.setTextColor(R.id.primary_label, subtextColor);
        remoteViews.setTextColor(R.id.secondary_label, subtextColor);
        remoteViews.setTextColor(R.id.primary_reset, muted);
        remoteViews.setTextColor(R.id.secondary_reset, muted);
        remoteViews.setTextColor(R.id.updated_label, faint);
        remoteViews.setTextViewText(R.id.primary_label, primary == null ? "" : primary.label);
        remoteViews.setTextViewText(R.id.secondary_label, secondary == null ? "" : secondary.label);
        remoteViews.setViewVisibility(R.id.primary_label, View.GONE);
        remoteViews.setViewVisibility(R.id.secondary_label, View.GONE);
        remoteViews.setTextViewText(R.id.primary_reset, widgetState.primaryShortReset);
        remoteViews.setTextViewText(R.id.secondary_reset, widgetState.secondaryShortReset);
        remoteViews.setViewVisibility(R.id.primary_reset, View.GONE);
        remoteViews.setViewVisibility(R.id.secondary_reset, View.GONE);
        applyUpdated(remoteViews, widgetOptions, widgetState, faint);
    }

    private static void renderFourDials(Context context, RemoteViews remoteViews,
            WidgetOptions options, boolean dark, List<MeterSlot> slots) {
        int accent = WidgetGraphics.accentColor(context, options.accent, dark);
        int track = WidgetGraphics.trackColor(dark);
        int text = WidgetGraphics.mainTextColor(dark);
        int[] graphicIds = {
                R.id.primary_four_graphic, R.id.secondary_four_graphic,
                R.id.reset_time_four_graphic, R.id.reset_count_four_graphic
        };
        int[] sectionIds = {
                R.id.primary_section, R.id.secondary_section, 0, 0
        };
        for (int index = 0; index < graphicIds.length; index++) {
            MeterSlot slot = slotAt(slots, index);
            if (slot == null) {
                remoteViews.setViewVisibility(graphicIds[index], View.GONE);
                if (sectionIds[index] != 0) {
                    remoteViews.setViewVisibility(sectionIds[index], View.GONE);
                }
                continue;
            }
            remoteViews.setViewVisibility(graphicIds[index], View.VISIBLE);
            if (sectionIds[index] != 0) {
                remoteViews.setViewVisibility(sectionIds[index], View.VISIBLE);
            }
            remoteViews.setImageViewBitmap(graphicIds[index],
                    WidgetGraphics.compactDial(context, slot.progress, slot.iconRes,
                            accent, track, text, slot.valueText, 1.0f));
        }
    }

    private static void renderBatteryList(Context context, RemoteViews remoteViews,
            WidgetOptions options, boolean dark, List<MeterSlot> slots) {
        int textColor = WidgetGraphics.mainTextColor(dark);
        int accent = WidgetGraphics.accentColor(context, options.accent, dark);
        int track = WidgetGraphics.trackColor(dark);
        int[] sectionIds = {
                R.id.primary_section, R.id.secondary_section,
                R.id.reset_time_section, R.id.reset_count_section
        };
        int[] spacerIds = {
                R.id.list_spacer_1, R.id.list_spacer_2, R.id.list_spacer_3
        };
        int[] progressIds = {
                R.id.primary_list_progress, R.id.secondary_list_progress,
                R.id.reset_time_list_progress, R.id.reset_count_list_progress
        };
        int[] valueIds = {
                R.id.primary_list_value, R.id.secondary_list_value,
                R.id.reset_time_list_value, R.id.reset_count_list_value
        };
        int[] iconIds = {
                R.id.primary_list_icon, R.id.secondary_list_icon,
                R.id.reset_time_list_icon, R.id.reset_count_list_icon
        };
        for (int index = 0; index < sectionIds.length; index++) {
            MeterSlot slot = slotAt(slots, index);
            if (slot == null) {
                remoteViews.setViewVisibility(sectionIds[index], View.GONE);
                if (index > 0) {
                    remoteViews.setViewVisibility(spacerIds[index - 1], View.GONE);
                }
                continue;
            }
            remoteViews.setViewVisibility(sectionIds[index], View.VISIBLE);
            if (index > 0) {
                remoteViews.setViewVisibility(spacerIds[index - 1], View.VISIBLE);
            }
            applyProgressColors(remoteViews, progressIds[index], accent, track);
            int max = slot.progressMax > 0 ? slot.progressMax : 100;
            remoteViews.setProgressBar(progressIds[index], max,
                    Math.max(0, slot.progress), false);
            remoteViews.setTextViewText(valueIds[index], slot.valueText);
            remoteViews.setTextColor(valueIds[index], textColor);
            remoteViews.setImageViewResource(iconIds[index], slot.iconRes);
            remoteViews.setInt(iconIds[index], "setColorFilter", Color.WHITE);
        }
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

    private static void applyUpdated(RemoteViews remoteViews, WidgetOptions widgetOptions, WidgetState widgetState, int color) {
        remoteViews.setTextColor(R.id.updated_label, color);
        remoteViews.setTextViewText(R.id.updated_label, widgetState.updated);
        remoteViews.setViewVisibility(R.id.updated_label, (!widgetOptions.showUpdated || widgetState.updated.isEmpty()) ? View.GONE : View.VISIBLE);
    }

    private static void applySlotVisibility(RemoteViews remoteViews, String style, List<MeterSlot> slots) {
        if (STYLE_FOUR_DIALS.equals(style) || STYLE_BATTERY_LIST.equals(style)) {
            return;
        }
        remoteViews.setViewVisibility(R.id.primary_section, slots.size() > 0 ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.secondary_section, slots.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private static MeterSlot slotAt(List<MeterSlot> slots, int index) {
        if (slots == null || index < 0 || index >= slots.size()) {
            return null;
        }
        return slots.get(index);
    }

    private static List<MeterSlot> resolveSlots(Context context, WidgetOptions options,
            WidgetState state, String visualStyle, Bundle bundle, UsageProvider provider) {
        UsageSnapshot snapshot = SecureTokenStore.isSignedIn(context, provider)
                ? ProviderPreferences.loadSnapshot(context, provider) : null;
        int height = currentHeight(context, bundle);
        int capacity = WidgetMeters.slotCapacity(visualStyle, height);
        List<String> available = new ArrayList<>(WidgetMeters.availableKeys(snapshot));
        boolean noShortWindow = false;
        if (!ResetAlertPreferences.hasResets(provider)) {
            available.remove(WidgetMeters.RESET_CREDITS);
        }
        // Plans without a 5-hour limit (Claude Ultra, Cursor, Codex Free) show the long window
        // instead of an empty 5-hour meter.
        noShortWindow = !WidgetMeters.mayHaveFiveHour(snapshot);
        if (noShortWindow) available.remove(WidgetMeters.FIVE_HOUR);
        List<String> resolvedVisible = new ArrayList<>(
                WidgetMeters.resolveVisibleForWidget(options.effectiveVisibleMeters(),
                        available, options.metricMode));
        if (noShortWindow && !resolvedVisible.contains(WidgetMeters.WEEKLY)
                && WidgetMeters.usageMeterCount(resolvedVisible) == 0) {
            resolvedVisible.add(0, WidgetMeters.WEEKLY);
        }
        List<String> visible = WidgetMeters.cap(resolvedVisible, capacity);
        ResetCreditsSnapshot credits = ProviderPreferences.loadResetCredits(context, provider,
                System.currentTimeMillis());
        int resetCount = credits == null ? 0 : credits.availableCount;
        ArrayList<MeterSlot> slots = new ArrayList<>();
        for (String key : visible) {
            MeterSlot slot = buildSlot(key, snapshot, state, options, resetCount);
            if (slot == null) {
                // Keep the reserved slot so a toggled-on meter never collapses away.
                slot = blankSlot(key, snapshot);
            }
            slots.add(slot);
        }
        return slots;
    }

    /** One slot per provider (two when every provider fits twice), each with the provider's mark. */
    private static List<MeterSlot> resolveMultiSlots(Context context, WidgetOptions options,
            String visualStyle, Bundle bundle, List<UsageProvider> providers) {
        int capacity = WidgetMeters.slotCapacity(visualStyle, currentHeight(context, bundle));
        List<UsageProvider> shown = providers.subList(0,
                Math.min(providers.size(), Math.min(capacity, WidgetProviders.MAX_HOME)));
        // Only battery-list rows have room for a meter label next to the value.
        int perProvider = STYLE_BATTERY_LIST.equals(visualStyle)
                ? WidgetProviders.metersPerProvider(shown.size(), capacity) : 1;
        List<String> visible = WidgetMeters.parse(options.effectiveVisibleMeters());
        ArrayList<MeterSlot> slots = new ArrayList<>();
        for (UsageProvider provider : shown) {
            UsageSnapshot snapshot = SecureTokenStore.isSignedIn(context, provider)
                    ? ProviderPreferences.loadSnapshot(context, provider) : null;
            WidgetState state = WidgetState.from(context, options, provider);
            List<String> keys = WidgetProviders.homeMetersFor(visible,
                    snapshot != null && snapshot.fiveHour != null,
                    snapshot != null && snapshot.longWindow() != null,
                    ResetAlertPreferences.hasResets(provider), perProvider);
            ResetCreditsSnapshot credits = ResetAlertPreferences.hasResets(provider)
                    ? ProviderPreferences.loadResetCredits(context, provider, System.currentTimeMillis())
                    : null;
            int resetCount = credits == null ? 0 : credits.availableCount;
            for (String key : keys) {
                MeterSlot slot = buildSlot(key, snapshot, state, options, resetCount);
                if (slot == null) slot = blankSlot(key, snapshot);
                // Battery-list rows name the meter when a provider has two, or when it is not a usage window.
                boolean named = STYLE_BATTERY_LIST.equals(visualStyle) && (perProvider > 1 || !slot.usage);
                String value = named
                        ? WidgetMeters.shortLabel(key, snapshot) + "  " + slot.valueText
                        : slot.valueText;
                slots.add(new MeterSlot(key, provider.displayName,
                        ProvidersActivity.iconRes(provider), slot.progress, value,
                        slot.shortText, slot.progressMax, slot.usage));
            }
        }
        return slots;
    }

    private static MeterSlot blankSlot(String key, UsageSnapshot snapshot) {
        String label = WidgetMeters.shortLabel(key, snapshot);
        int icon = WidgetMeters.WEEKLY.equals(key)
                || (WidgetMeters.isLimitKey(key) && !WidgetMeters.isLimitPrimary(key))
                ? R.drawable.ic_oui_calendar_week
                : R.drawable.ic_oui_time;
        if (WidgetMeters.NEXT_RESET.equals(key)) {
            icon = R.drawable.ic_oui_alarm;
        } else if (WidgetMeters.RESET_CREDITS.equals(key)) {
            icon = R.drawable.ic_oui_refresh;
        }
        return new MeterSlot(key, label, icon, -1, "—", "—", 100, true);
    }

    private static MeterSlot buildSlot(String key, UsageSnapshot snapshot, WidgetState state,
            WidgetOptions options, int resetCount) {
        boolean used = WidgetOptions.DISPLAY_USED.equals(options.displayMode);
        if (WidgetMeters.FIVE_HOUR.equals(key)) {
            return usageSlot(key, "5h", R.drawable.ic_oui_time, state.primaryValue,
                    dialValue(state.primaryValue, options.showPercentSymbol),
                    state.primaryShort);
        }
        if (WidgetMeters.WEEKLY.equals(key)) {
            return usageSlot(key, WidgetMeters.shortLabel(key, snapshot),
                    R.drawable.ic_oui_calendar_week, state.secondaryValue,
                    dialValue(state.secondaryValue, options.showPercentSymbol),
                    state.secondaryShort);
        }
        if (WidgetMeters.NEXT_RESET.equals(key)) {
            return new MeterSlot(key, "Reset", R.drawable.ic_oui_alarm, state.nextResetProgress,
                    state.nextResetText, state.nextResetText, 100, false);
        }
        if (WidgetMeters.RESET_CREDITS.equals(key)) {
            int progress = Math.min(100, Math.round((Math.min(4, resetCount) / 4.0f) * 100));
            String text = String.valueOf(resetCount);
            return new MeterSlot(key, "Credits", R.drawable.ic_oui_refresh, progress, text, text,
                    100, false);
        }
        UsageLimit limit = WidgetMeters.findLimit(key, snapshot);
        if (limit != null) {
            UsageWindow window = WidgetMeters.isLimitPrimary(key) ? limit.primary : limit.secondary;
            int value = window == null ? -1 : (used ? window.usedPercent : window.remainingPercent());
            String label = WidgetMeters.shortLabel(key, snapshot);
            int icon = WidgetMeters.isLimitPrimary(key)
                    ? R.drawable.ic_oui_time : R.drawable.ic_oui_calendar_week;
            String text = dialValue(value, options.showPercentSymbol);
            return usageSlot(key, label, icon, value, text, text);
        }
        return null;
    }

    private static MeterSlot usageSlot(String key, String label, int icon, int progress,
            String valueText, String shortText) {
        return new MeterSlot(key, label, icon, progress, valueText, shortText, 100, true);
    }

    private static final class MeterSlot {
        final String key;
        final String label;
        final int iconRes;
        final int progress;
        final String valueText;
        final String shortText;
        final int progressMax;
        /** True for percent-based usage windows; false for countdown/credit helpers. */
        final boolean usage;

        MeterSlot(String key, String label, int iconRes, int progress, String valueText,
                String shortText, int progressMax, boolean usage) {
            this.key = key;
            this.label = label;
            this.iconRes = iconRes;
            this.progress = progress;
            this.valueText = valueText;
            this.shortText = shortText;
            this.progressMax = progressMax;
            this.usage = usage;
        }
    }

    private static void applyResetCreditRow(Context context, RemoteViews remoteViews,
            int widgetId, WidgetOptions widgetOptions, boolean dark, String style,
            UsageProvider provider) {
        String summary;
        boolean hidden = STYLE_MICRO.equals(style) || STYLE_BATTERY_LIST.equals(style);
        boolean wanted = widgetOptions.showResetCredits || widgetOptions.showResetAction;
        if (hidden || !wanted) {
            remoteViews.setViewVisibility(R.id.reset_credit_row, View.GONE);
            return;
        }
        long now = System.currentTimeMillis();
        ResetCreditsSnapshot resetCredits = ProviderPreferences.loadResetCredits(context,
                provider, now);
        int credits = resetCredits == null ? 0 : resetCredits.availableCount;
        long nextExpiry = resetCredits == null ? 0L : resetCredits.nextExpiryMillis(now);
        boolean showInfo = widgetOptions.showResetCredits;
        boolean showButton = widgetOptions.showResetAction && credits > 0
                && SecureTokenStore.isSignedIn(context, provider);
        if (!showInfo && !showButton) {
            remoteViews.setViewVisibility(R.id.reset_credit_row, View.GONE);
            return;
        }
        remoteViews.setViewVisibility(R.id.reset_credit_row, View.VISIBLE);
        remoteViews.setViewVisibility(R.id.reset_credit_info, showInfo ? View.VISIBLE : View.GONE);
        remoteViews.setViewVisibility(R.id.reset_credit_button, showButton ? View.VISIBLE : View.GONE);
        if (credits <= 0) {
            summary = "No reset credits";
        } else if (nextExpiry > 0) {
            summary = credits + " reset" + (credits == 1 ? "" : "s") + " · expires " + UsageFormat.relative(nextExpiry, now);
        } else {
            summary = credits + " reset" + (credits == 1 ? "" : "s") + " available";
        }
        remoteViews.setTextViewText(R.id.reset_credit_info, summary);
        remoteViews.setTextColor(R.id.reset_credit_info, mutedColor(dark));
        remoteViews.setTextViewText(R.id.reset_credit_button, credits > 1 ? "Use reset (" + credits + ")" : "Use reset");
        remoteViews.setTextColor(R.id.reset_credit_button, WidgetGraphics.mainTextColor(dark));
        remoteViews.setInt(R.id.reset_credit_button, "setBackgroundResource", dark ? R.drawable.widget_action_dark : R.drawable.widget_action_light);
        remoteViews.setContentDescription(R.id.reset_credit_button,
                "Use one " + provider.displayName + " reset. " + summary);
        // The reset screen for this widget's provider (applyIntents set Codex's).
        remoteViews.setOnClickPendingIntent(R.id.reset_credit_button,
                PendingIntent.getActivity(context, 76000 + widgetId,
                        ResetCreditActivity.intent(context, provider)
                                .setAction("dev.bennett.codexmeter.action.WIDGET_RESET")
                                .setData(widgetUri(widgetId, "reset-" + provider.id))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static int progressResource(String accent, boolean dark) {
        if (WidgetOptions.ACCENT_APP.equals(accent)) {
            return R.drawable.progress_mono;
        }
        if (WidgetOptions.ACCENT_BLUE.equals(accent)) {
            return R.drawable.progress_blue;
        }
        if (WidgetOptions.ACCENT_AMBER.equals(accent)) {
            return R.drawable.progress_amber;
        }
        if (WidgetOptions.ACCENT_VIOLET.equals(accent)) {
            return R.drawable.progress_violet;
        }
        if (WidgetOptions.ACCENT_ROSE.equals(accent)) {
            return R.drawable.progress_rose;
        }
        if (WidgetOptions.ACCENT_CYAN.equals(accent)) {
            return R.drawable.progress_cyan;
        }
        if (WidgetOptions.ACCENT_LIME.equals(accent)) {
            return R.drawable.progress_lime;
        }
        if (WidgetOptions.ACCENT_MONO.equals(accent)) {
            return dark ? R.drawable.progress_mono : R.drawable.progress_mono_dark;
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

    private static int backgroundResource(Context context, boolean dark, int opacity, String surface) {
        if (opacity == 0) {
            return R.drawable.widget_bg_transparent;
        }
        boolean oneUi = WidgetOptions.SURFACE_ONE_UI.equals(surface);
        if (oneUi && isSamsung(context)) {
            if (dark) {
                if (opacity == 56) {
                    return R.drawable.widget_bg_samsung_dark_56;
                }
                if (opacity == 72) {
                    return R.drawable.widget_bg_samsung_dark_72;
                }
                return opacity == 100 ? R.drawable.widget_bg_samsung_dark_100 : R.drawable.widget_bg_samsung_dark_88;
            }
            if (opacity == 56) {
                return R.drawable.widget_bg_samsung_light_56;
            }
            if (opacity == 72) {
                return R.drawable.widget_bg_samsung_light_72;
            }
            return opacity == 100 ? R.drawable.widget_bg_samsung_light_100 : R.drawable.widget_bg_samsung_light_88;
        }
        if (oneUi && dark) {
            if (opacity == 56) {
                return R.drawable.widget_bg_oneui_dark_56;
            }
            if (opacity == 72) {
                return R.drawable.widget_bg_oneui_dark_72;
            }
            return opacity == 100 ? R.drawable.widget_bg_oneui_dark_100 : R.drawable.widget_bg_oneui_dark_88;
        }
        if (oneUi) {
            if (opacity == 56) {
                return R.drawable.widget_bg_oneui_light_56;
            }
            if (opacity == 72) {
                return R.drawable.widget_bg_oneui_light_72;
            }
            return opacity == 100 ? R.drawable.widget_bg_oneui_light_100 : R.drawable.widget_bg_oneui_light_88;
        }
        if (dark) {
            if (opacity == 56) {
                return R.drawable.widget_bg_dark_56;
            }
            if (opacity == 72) {
                return R.drawable.widget_bg_dark_72;
            }
            return opacity == 100 ? R.drawable.widget_bg_dark_100 : R.drawable.widget_bg_dark_88;
        }
        if (opacity == 56) {
            return R.drawable.widget_bg_light_56;
        }
        if (opacity == 72) {
            return R.drawable.widget_bg_light_72;
        }
        return opacity == 100 ? R.drawable.widget_bg_light_100 : R.drawable.widget_bg_light_88;
    }

    private static boolean isSamsung(Context context) {
        String manufacturer = Build.MANUFACTURER;
        String brand = Build.BRAND;
        return (manufacturer != null && "samsung".equalsIgnoreCase(manufacturer)) || (brand != null && "samsung".equalsIgnoreCase(brand));
    }

    private static int secondaryColor(boolean dark) {
        return dark ? Color.argb(232, 255, 255, 255) : Color.argb(232, 17, 19, 21);
    }

    private static int mutedColor(boolean dark) {
        return dark ? Color.argb(165, 255, 255, 255) : Color.argb(165, 17, 19, 21);
    }

    private static int faintColor(boolean dark) {
        return dark ? Color.argb(112, 255, 255, 255) : Color.argb(112, 17, 19, 21);
    }

    public static String shortReset(Context context, UsageWindow usageWindow, String mode, long nowMillis) {
        return shortReset(context, usageWindow, mode, nowMillis, nowMillis);
    }

    public static String shortReset(Context context, UsageWindow usageWindow, String mode,
            long observedAtMillis, long nowMillis) {
        if (usageWindow == null || WidgetOptions.RESET_HIDDEN.equals(mode)
                || !usageWindow.showsResetCountdown()) {
            return "";
        }
        long resetAt = usageWindow.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= 0) {
            return "reset unavailable";
        }
        if (WidgetOptions.RESET_RELATIVE.equals(mode)) {
            return UsageFormat.relative(resetAt, nowMillis);
        }
        if ("both".equals(mode)) {
            return UsageFormat.absolute(context, resetAt, nowMillis) + " ("
                    + UsageFormat.relative(resetAt, nowMillis) + ")";
        }
        return UsageFormat.absolute(context, resetAt, nowMillis)
                .replace("today at ", "").replace("tomorrow at ", "tomorrow ");
    }

    private static String safeMessage(RuntimeException runtimeException) {
        String message = runtimeException.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return runtimeException.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
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
        /** "Week" or "Month" — the long-cadence window filling the secondary slot. */
        final String secondaryName;
        final String updated;
        final String nextResetText;
        final int nextResetProgress;

        WidgetState(String plan, int primaryValue, int secondaryValue, String primaryText, String secondaryText, String primaryShort,
                String secondaryShort, String primaryReset, String secondaryReset, String primaryShortReset, String secondaryShortReset,
                String combinedReset, String updated, String nextResetText, int nextResetProgress) {
            this(plan, primaryValue, secondaryValue, primaryText, secondaryText, primaryShort, secondaryShort, primaryReset, secondaryReset, primaryShortReset, secondaryShortReset, combinedReset, updated,
                    nextResetText, nextResetProgress, "Week");
        }

        WidgetState(String plan, int primaryValue, int secondaryValue, String primaryText, String secondaryText, String primaryShort,
                String secondaryShort, String primaryReset, String secondaryReset, String primaryShortReset, String secondaryShortReset,
                String combinedReset, String updated, String nextResetText, int nextResetProgress,
                String secondaryName) {
            this.plan = plan;
            this.primaryValue = primaryValue;
            this.secondaryValue = secondaryValue;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.primaryShort = primaryShort;
            this.secondaryShort = secondaryShort;
            this.primaryReset = primaryReset;
            this.secondaryReset = secondaryReset;
            this.primaryShortReset = primaryShortReset;
            this.secondaryShortReset = secondaryShortReset;
            this.combinedReset = combinedReset;
            this.updated = updated;
            this.nextResetText = nextResetText;
            this.nextResetProgress = Math.max(0, Math.min(100, nextResetProgress));
            this.secondaryName = secondaryName == null ? "Week" : secondaryName;
        }

        static WidgetState from(Context context, WidgetOptions widgetOptions) {
            return from(context, widgetOptions, UsageProvider.CODEX);
        }

        static WidgetState from(Context context, WidgetOptions widgetOptions,
                UsageProvider provider) {
            String combinedReset;
            boolean signedIn = SecureTokenStore.isSignedIn(context, provider);
            UsageSnapshot snapshot =
                    ProviderPreferences.loadSnapshot(context, provider);
            long now = System.currentTimeMillis();
            if (!signedIn) {
                String connect = "Open the app to connect " + provider.accountName;
                return new WidgetState("", -1, -1, "Sign in", "—", "SIGN IN", "—", connect, "", "Tap to connect", "", connect, "Tap anywhere to sign in", "—", 0);
            }
            if (snapshot == null) {
                String lastError = ProviderPreferences.getLastError(context, provider);
                if (lastError.isEmpty()) {
                    lastError = "Tap refresh to load usage";
                }
                return new WidgetState("", -1, -1, "Loading…", "—", "…", "—", lastError, "", lastError, "", lastError, "Waiting for the first update", "—", 0);
            }
            boolean showUsed = WidgetOptions.DISPLAY_USED.equals(widgetOptions.displayMode);
            // The secondary slot follows the account's long-cadence window: weekly on paid
            // plans, monthly on the free tier.
            UsageWindow longWindow = snapshot.longWindow();
            String secondaryName = snapshot.longWindowIsMonthly()
                    ? "Month" : "Week";
            int primaryValue = value(snapshot.fiveHour, showUsed);
            int secondaryValue = value(longWindow, showUsed);
            String primaryText = UsageFormat.percent(snapshot.fiveHour, widgetOptions.displayMode, false);
            String secondaryText = UsageFormat.percent(longWindow, widgetOptions.displayMode, false);
            String primaryShort = UsageFormat.percent(snapshot.fiveHour, widgetOptions.displayMode, true);
            String secondaryShort = UsageFormat.percent(longWindow, widgetOptions.displayMode, true);
            String primaryReset = UsageFormat.reset(context, snapshot.fiveHour,
                    widgetOptions.resetMode, snapshot.fetchedAtMillis,
                    now);
            String secondaryReset = UsageFormat.reset(context, longWindow,
                    widgetOptions.resetMode, snapshot.fetchedAtMillis,
                    now);
            if (provider != UsageProvider.CODEX && snapshot.fiveHour == null
                    && longWindow != null) {
                // A plan without a 5-hour limit (Claude Ultra) says so instead of "Unavailable".
                primaryText = "No limit";
                primaryReset = "No 5-hour limit";
            }
            String primaryShortReset = WidgetRenderer.shortReset(context,
                    snapshot.fiveHour, widgetOptions.resetMode,
                    snapshot.fetchedAtMillis, now);
            String secondaryShortReset = WidgetRenderer.shortReset(context,
                    longWindow, widgetOptions.resetMode,
                    snapshot.fetchedAtMillis, now);
            if (primaryShortReset.isEmpty()) {
                combinedReset = secondaryShortReset.isEmpty() ? "" : secondaryName + " " + secondaryShortReset;
            } else {
                combinedReset = secondaryShortReset.isEmpty() ? "5h " + primaryShortReset
                        : "5h " + primaryShortReset + " · " + secondaryName + " " + secondaryShortReset;
            }
            String updated = UsageFormat.updated(snapshot.fetchedAtMillis, now);
            String updatedText = now - snapshot.fetchedAtMillis > TimeUnit.HOURS.toMillis(6L) ? updated + " · cached" : updated;
            // Codex stores raw plan ids; other providers store the display label directly.
            String plan = provider == UsageProvider.CODEX
                    ? UsageFormat.planLabel(snapshot.planType)
                    : snapshot.planType;
            if (!ProviderPreferences.getLastError(context, provider).isEmpty() && now - snapshot.fetchedAtMillis > TimeUnit.MINUTES.toMillis(20L)) {
                updatedText = updatedText + " · refresh issue";
            }
            ResetCountdown countdown = nextReset(snapshot, now);
            return new WidgetState(plan, primaryValue, secondaryValue, primaryText, secondaryText,
                    primaryShort, secondaryShort, primaryReset, secondaryReset, primaryShortReset,
                    secondaryShortReset, combinedReset, updatedText, countdown.text, countdown.progress,
                    secondaryName);
        }

        static WidgetState error(String message) {
            return new WidgetState("", -1, -1, "—", "—", "—", "—", message, "", message,
                    "", message, "Open the app", "—", 0);
        }

        private static ResetCountdown nextReset(UsageSnapshot snapshot, long now) {
            UsageWindow fiveHour = snapshot == null ? null : snapshot.fiveHour;
            UsageWindow longWindow = snapshot == null ? null : snapshot.longWindow();
            long fiveHourReset = (fiveHour != null && fiveHour.showsResetCountdown())
                    ? resetAt(fiveHour, snapshot.fetchedAtMillis) : 0L;
            long longReset = (longWindow != null && longWindow.showsResetCountdown())
                    ? resetAt(longWindow, snapshot.fetchedAtMillis) : 0L;
            long resetAt;
            long windowDuration;
            if (fiveHourReset > now && (longReset <= now || fiveHourReset <= longReset)) {
                resetAt = fiveHourReset;
                windowDuration = TimeUnit.HOURS.toMillis(5L);
            } else if (longReset > now) {
                resetAt = longReset;
                windowDuration = Math.max(TimeUnit.DAYS.toMillis(1L),
                        longWindow.windowSeconds * 1000L);
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

        private static long resetAt(UsageWindow window, long observedAtMillis) {
            return window == null ? 0L : window.effectiveResetAtMillis(observedAtMillis);
        }

        private static final class ResetCountdown {
            final String text;
            final int progress;

            ResetCountdown(String text, int progress) {
                this.text = text;
                this.progress = progress;
            }
        }

        private static int value(UsageWindow usageWindow, boolean used) {
            if (usageWindow == null) {
                return -1;
            }
            return used ? usageWindow.usedPercent : usageWindow.remainingPercent();
        }
    }
}
