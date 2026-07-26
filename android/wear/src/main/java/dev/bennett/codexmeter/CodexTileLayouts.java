package dev.bennett.codexmeter;

import android.content.ComponentName;
import android.content.Context;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ProtoLayoutScope;
import androidx.wear.protolayout.ResourceBuilders;
import dev.bennett.codexmeter.wear.WearSettingsState;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** One UI Watch tile layouts shared by the full-screen and Samsung modular hosts. */
final class CodexTileLayouts {
    private static final int GRADIENT_FALLBACK = 0xFF1C197E;
    private static final int GRADIENT_START = 0xFF534FA7;
    private static final int GRADIENT_END = 0xFF1C197E;
    private static final int DIAL_BACKGROUND = 0xFF0B0B10;
    private static final int DIAL_PROGRESS = 0xFF6B6EE0;
    private static final int RESET_ACCENT = 0xFFFFC56E;
    private static final int MONITOR_ACCENT = 0xFF73E1B7;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xCCFFFFFF;
    private static final int TEXT_TERTIARY = 0xCCFFFFFF;
    private static final int TEXT_DIVIDER = 0x66FFFFFF;

    private CodexTileLayouts() {
    }

    static LayoutElement overview(Context context, DeviceParameters deviceParameters,
            ProtoLayoutScope scope) {
        OneUiTileText text = new OneUiTileText(context, scope);
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);
        long observedAt = snapshot == null ? 0L : snapshot.fetchedAtMillis;
        long now = System.currentTimeMillis();
        boolean stale = snapshot != null && isStale(context);
        String fiveReset = stale ? "Stale phone data" : resetCopy(fiveHour, observedAt, now);
        String weekReset = stale ? "Stale phone data" : resetCopy(weekly, observedAt, now);
        LayoutElement content = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(metricRow(context, fiveHour, "5hr", false, fiveReset, 9f, text, scope))
                .addContent(verticalSpacer(6.5f))
                .addContent(metricRow(context, weekly, "Weekly", true, weekReset, 9f, text, scope))
                .build();
        String description = "Five hour usage, "
                + WearGlanceFormat.remainingPercentText(fiveHour) + " remaining, " + fiveReset
                + ". Weekly usage, " + WearGlanceFormat.remainingPercentText(weekly)
                + " remaining, " + weekReset + ". Open Codex Meter.";
        return card(context, "overview", leadingInset(content, 12f), 0f, 72f, 176f,
                description);
    }

    static LayoutElement progress(Context context, DeviceParameters deviceParameters,
            String label, UsageWindow window, ProtoLayoutScope scope) {
        OneUiTileText text = new OneUiTileText(context, scope);
        boolean weekly = label.toLowerCase(Locale.ROOT).contains("week");
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        long observedAt = snapshot == null ? 0L : snapshot.fetchedAtMillis;
        String reset = snapshot != null && isStale(context)
                ? "Stale phone data"
                : resetCopy(window, observedAt, System.currentTimeMillis());
        String designLabel = weekly ? "Weekly" : "5hr";
        boolean compact = isCompactViewport(deviceParameters);
        float gap = compact ? 9f : 14f;
        float inset = compact ? 8f : 14f;
        LayoutElement content = metricRow(context, window, designLabel, weekly, reset, gap, text,
                scope);
        String description = label + ", " + WearGlanceFormat.remainingPercentText(window)
                + " remaining, " + reset + ". Open Codex Meter.";
        return card(context, label, leadingInset(content, inset), 0f, 46f, 92f, description);
    }

    static LayoutElement reset(Context context, DeviceParameters deviceParameters,
            ProtoLayoutScope scope) {
        OneUiTileText text = new OneUiTileText(context, scope);
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        long now = System.currentTimeMillis();
        String windowLabel = WearGlanceFormat.nextResetWindowLabel(snapshot, now);
        String relative = WearGlanceFormat.nextResetRelativeText(snapshot, now);
        boolean weekly = windowLabel.toLowerCase(Locale.ROOT).contains("week");
        UsageWindow dialWindow = weekly
                ? WearGlanceFormat.currentWeekly(snapshot)
                : WearGlanceFormat.currentFiveHour(snapshot);
        String credits = WearGlanceFormat.resetCreditsText(snapshot);

        LayoutElementBuilders.Column.Builder copy = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(text.element("Next reset", 13f, TEXT_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(verticalSpacer(2f))
                .addContent(text.element(relative, 14f, RESET_ACCENT,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text.element(windowLabel, 9f, TEXT_TERTIARY,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL));
        if (!credits.isEmpty()) {
            copy.addContent(text.element(credits, 9f, TEXT_SECONDARY,
                    LayoutElementBuilders.FONT_WEIGHT_NORMAL));
        }
        String description = "Next reset, " + relative + ", " + windowLabel
                + (credits.isEmpty() ? "" : ", " + credits) + ". Open Codex Meter.";
        return card(context, "reset", compactRow(
                usageDial(context, dialWindow, weekly, scope),
                copy.build()), 10f, 46f, 92f, description);
    }

    static LayoutElement monitor(Context context, DeviceParameters deviceParameters,
            ProtoLayoutScope scope) {
        OneUiTileText text = new OneUiTileText(context, scope);
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);
        UsageWindow focus = lowerRemaining(fiveHour, weekly);
        boolean focusWeekly = focus != null && focus == weekly;
        boolean active = WearOngoingMonitor.isActive(context);
        int accent = active ? MONITOR_ACCENT : DIAL_PROGRESS;

        LayoutElement copy = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(text.element("Live monitor", 13f, TEXT_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(verticalSpacer(2f))
                .addContent(text.element(active ? "Active" : "Off", 14f, accent,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text.element(WearGlanceFormat.focusSummary(snapshot), 9f, TEXT_TERTIARY,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .build();
        String description = "Live monitor " + (active ? "active" : "off") + ". "
                + WearGlanceFormat.focusSummary(snapshot) + ". Open Codex Meter.";
        return card(context, "monitor", compactRow(
                usageDial(context, focus, focusWeekly, scope), copy),
                10f, 46f, 92f, description);
    }

    static UsageWindow fiveHour(Context context) {
        return WearGlanceFormat.currentFiveHour(WearPreferences.loadSnapshot(context));
    }

    static UsageWindow weekly(Context context) {
        return WearGlanceFormat.currentWeekly(WearPreferences.loadSnapshot(context));
    }

    private static LayoutElement card(Context context, String idSuffix, LayoutElement content,
            float paddingDp, float cornerRadiusDp) {
        return card(context, idSuffix, content, paddingDp, cornerRadiusDp, 92f,
                "Open Codex Meter",
                DimensionBuilders.expand(), DimensionBuilders.expand());
    }

    private static LayoutElement card(Context context, String idSuffix, LayoutElement content,
            float paddingDp, float cornerRadiusDp, float gradientHeightDp,
            String contentDescription) {
        return card(context, idSuffix, content, paddingDp, cornerRadiusDp, gradientHeightDp,
                contentDescription, DimensionBuilders.expand(), DimensionBuilders.expand());
    }

    private static LayoutElement card(Context context, String idSuffix, LayoutElement content,
            float paddingDp, float cornerRadiusDp, float gradientHeightDp,
            String contentDescription,
            DimensionBuilders.ContainerDimension width,
            DimensionBuilders.ContainerDimension height) {
        ModifiersBuilders.Background background = new ModifiersBuilders.Background.Builder()
                .setColor(ColorBuilders.argb(GRADIENT_FALLBACK))
                .setBrush(new ColorBuilders.LinearGradient.Builder(
                        ColorBuilders.argb(GRADIENT_START),
                        ColorBuilders.argb(GRADIENT_END))
                        .setStartY(DimensionBuilders.dp(0f))
                        .setEndY(DimensionBuilders.dp(gradientHeightDp))
                        .build())
                .setCorner(new ModifiersBuilders.Corner.Builder()
                        .setRadius(DimensionBuilders.dp(cornerRadiusDp))
                        .build())
                .build();
        ModifiersBuilders.Modifiers modifiers = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(background)
                .setClickable(openClickable(context, idSuffix))
                .setSemantics(new ModifiersBuilders.Semantics.Builder()
                        .setContentDescription(contentDescription)
                        .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON)
                        .build())
                .build();
        LayoutElement insetContent = content;
        if (paddingDp > 0f) {
            insetContent = new LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                            .setPadding(new ModifiersBuilders.Padding.Builder()
                                    .setAll(DimensionBuilders.dp(paddingDp))
                                    .build())
                            .build())
                    .addContent(content)
                    .build();
        }
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(width)
                .setHeight(height)
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(modifiers)
                .addContent(insetContent)
                .build();
    }

    private static LayoutElement metricRow(Context context, UsageWindow window, String label,
            boolean weekly, String reset, float gapDp, OneUiTileText text,
            ProtoLayoutScope scope) {
        LayoutElement headline = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.wrap())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(text.element(WearGlanceFormat.remainingPercentText(window), 20f,
                        TEXT_PRIMARY, LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(horizontalSpacer(5f))
                .addContent(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.dp(4f))
                        .setHeight(DimensionBuilders.dp(4f))
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                .setBackground(new ModifiersBuilders.Background.Builder()
                                        .setColor(ColorBuilders.argb(TEXT_DIVIDER))
                                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                                .setRadius(DimensionBuilders.dp(2f))
                                                .build())
                                        .build())
                                .build())
                        .build())
                .addContent(horizontalSpacer(5f))
                .addContent(text.element(label, 20f, TEXT_SECONDARY,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .build();
        LayoutElement copy = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(headline)
                .addContent(verticalSpacer(4f))
                .addContent(text.element(reset, 13f, TEXT_SECONDARY,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .build();
        return new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(usageDial(context, window, weekly, scope))
                .addContent(horizontalSpacer(gapDp))
                .addContent(copy)
                .build();
    }

    private static LayoutElement usageDial(Context context, UsageWindow window, boolean weekly,
            ProtoLayoutScope scope) {
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(56f))
                .setHeight(DimensionBuilders.dp(56f))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(DIAL_BACKGROUND))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(28f))
                                        .build())
                                .build())
                        .setSemantics(new ModifiersBuilders.Semantics.Builder()
                                .setContentDescription(WearGlanceFormat.remainingPercentText(window)
                                        + " remaining")
                                .build())
                        .build())
                .addContent(OneUiTileDial.element(window))
                .addContent(dialIcon(weekly, scope))
                .build();
    }

    private static LayoutElement dialIcon(boolean weekly, ProtoLayoutScope scope) {
        int resourceId = weekly ? R.drawable.tile_icon_weekly : R.drawable.tile_icon_time;
        String key = weekly ? "tile_icon_weekly" : "tile_icon_time";
        ResourceBuilders.AndroidImageResourceByResId androidResource =
                new ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(resourceId)
                        .build();
        ResourceBuilders.ImageResource image =
                new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(androidResource)
                        .build();
        return new LayoutElementBuilders.Image.Builder(scope)
                .setImageResource(image, key)
                .setWidth(DimensionBuilders.dp(24f))
                .setHeight(DimensionBuilders.dp(24f))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                .build();
    }

    private static LayoutElement compactRow(LayoutElement dial, LayoutElement copy) {
        return new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(dial)
                .addContent(horizontalSpacer(9f))
                .addContent(copy)
                .build();
    }

    private static LayoutElement leadingInset(LayoutElement content, float insetDp) {
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(insetDp))
                                .build())
                        .build())
                .addContent(content)
                .build();
    }

    private static LayoutElement horizontalSpacer(float widthDp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(widthDp))
                .build();
    }

    private static LayoutElement verticalSpacer(float heightDp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(heightDp))
                .build();
    }

    private static ModifiersBuilders.Clickable openClickable(Context context, String idSuffix) {
        ComponentName activity = new ComponentName(context, WearMainActivity.class);
        return new ModifiersBuilders.Clickable.Builder()
                .setId("codex_open_" + idSuffix)
                .setOnClick(ActionBuilders.launchAction(activity))
                .build();
    }

    private static UsageWindow lowerRemaining(UsageWindow first, UsageWindow second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.remainingPercent() <= second.remainingPercent() ? first : second;
    }

    private static String resetCopy(UsageWindow window, long observedAtMillis, long nowMillis) {
        if (window == null) return "Reset unavailable";
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= nowMillis) return "Resets soon";
        long minutes = Math.max(1L,
                (resetAt - nowMillis + TimeUnit.MINUTES.toMillis(1) - 1L)
                        / TimeUnit.MINUTES.toMillis(1));
        long days = minutes / TimeUnit.DAYS.toMinutes(1);
        long hours = (minutes % TimeUnit.DAYS.toMinutes(1)) / TimeUnit.HOURS.toMinutes(1);
        long remainderMinutes = minutes % TimeUnit.HOURS.toMinutes(1);
        StringBuilder copy = new StringBuilder("Resets in ");
        if (days > 0L) copy.append(days).append('d');
        if (hours > 0L) {
            if (days > 0L) copy.append(' ');
            copy.append(hours).append("hr");
        }
        if (days == 0L && remainderMinutes > 0L) {
            if (hours > 0L) copy.append(' ');
            copy.append(remainderMinutes).append(remainderMinutes == 1L ? "min" : "mins");
        }
        return copy.toString();
    }

    private static boolean isCompactViewport(DeviceParameters deviceParameters) {
        return deviceParameters != null
                && deviceParameters.getScreenWidthDp() > 0
                && deviceParameters.getScreenWidthDp() < 217;
    }

    private static boolean isStale(Context context) {
        WearSettingsState settings = WearPreferences.settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR);
        return WearGlanceFormat.isStale(WearPreferences.lastUsageAt(context),
                settings.refreshMinutes, System.currentTimeMillis());
    }

}
