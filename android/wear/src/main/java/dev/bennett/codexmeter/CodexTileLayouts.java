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
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSyncStatus;
import java.util.Locale;

/** One UI Watch tile layouts shared by the full-screen and Samsung modular hosts. */
final class CodexTileLayouts {
    private static final int GRADIENT_FALLBACK = 0xFF263765;
    private static final int GRADIENT_START = 0xFF14274F;
    private static final int GRADIENT_MIDDLE = 0xFF293A78;
    private static final int GRADIENT_END = 0xFF56366F;
    private static final int DIAL_TRACK = 0x667A8AB4;
    private static final int FIVE_HOUR_ACCENT = 0xFF64D8FF;
    private static final int WEEKLY_ACCENT = 0xFFAB9CFF;
    private static final int RESET_ACCENT = 0xFFFFC56E;
    private static final int MONITOR_ACCENT = 0xFF73E1B7;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFD5DDF2;
    private static final int TEXT_TERTIARY = 0xFFABB7D4;
    private static final float DIAL_START_DEGREES = 246f;
    private static final float DIAL_SWEEP_DEGREES = 228f;

    private CodexTileLayouts() {
    }

    static LayoutElement overview(Context context, DeviceParameters deviceParameters,
            ProtoLayoutScope scope) {
        OneUiTileText text = new OneUiTileText(context, scope);
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);

        LayoutElement dials = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.wrap())
                .setHeight(DimensionBuilders.wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(dial(fiveHour, "5H", FIVE_HOUR_ACCENT, 46f, text))
                .addContent(horizontalSpacer(6f))
                .addContent(dial(weekly, "WEEK", WEEKLY_ACCENT, 46f, text))
                .build();

        LayoutElementBuilders.Column.Builder content = centeredColumn()
                .addContent(text.element("CODEX METER", 10f, TEXT_SECONDARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(verticalSpacer(3f))
                .addContent(dials)
                .addContent(verticalSpacer(3f))
                .addContent(text.element(emptyCopy(context, snapshot), 8f, TEXT_TERTIARY,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL));
        String credits = WearGlanceFormat.resetCreditsText(snapshot);
        if (!credits.isEmpty()) {
            content.addContent(text.element(credits, 7f, FIVE_HOUR_ACCENT,
                    LayoutElementBuilders.FONT_WEIGHT_BOLD));
        }
        // Samsung's 2x2 Media Controller tile uses the entire host allocation with a
        // 62dp corner radius. Its content supplies its own spacing; the root has none.
        return card(context, "overview", content.build(), 0f, 62f);
    }

    static LayoutElement progress(Context context, DeviceParameters deviceParameters,
            String label, UsageWindow window, ProtoLayoutScope scope) {
        OneUiTileText text = new OneUiTileText(context, scope);
        boolean weekly = label.toLowerCase(Locale.ROOT).contains("week");
        int accent = weekly ? WEEKLY_ACCENT : FIVE_HOUR_ACCENT;
        String shortLabel = weekly ? "WEEK" : "5H";
        String remaining = WearGlanceFormat.remainingPercentText(window);
        String state = isStale(context) ? "Stale phone data" : "Remaining";

        LayoutElement content = compactRow(
                dial(window, shortLabel, accent, 56f, text),
                new LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.wrap())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                        .addContent(text.element(label, 13f, TEXT_PRIMARY,
                                LayoutElementBuilders.FONT_WEIGHT_BOLD))
                        .addContent(verticalSpacer(2f))
                        .addContent(text.element(remaining + " remaining", 11f, TEXT_SECONDARY,
                                LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                        .addContent(text.element(state, 9f, TEXT_TERTIARY,
                                LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                        .build());
        return card(context, label, content, 10f, 34f);
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
        return card(context, "reset", compactRow(
                dial(dialWindow, weekly ? "WEEK" : "5H", RESET_ACCENT, 56f, text),
                copy.build()), 10f, 34f);
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
        int accent = active ? MONITOR_ACCENT : WEEKLY_ACCENT;

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
        return card(context, "monitor", compactRow(
                dial(focus, focusWeekly ? "WEEK" : "5H", accent, 56f, text), copy), 10f, 34f);
    }

    static UsageWindow fiveHour(Context context) {
        return WearGlanceFormat.currentFiveHour(WearPreferences.loadSnapshot(context));
    }

    static UsageWindow weekly(Context context) {
        return WearGlanceFormat.currentWeekly(WearPreferences.loadSnapshot(context));
    }

    private static LayoutElement card(Context context, String idSuffix, LayoutElement content,
            float paddingDp, float cornerRadiusDp) {
        return card(context, idSuffix, content, paddingDp, cornerRadiusDp,
                DimensionBuilders.expand(), DimensionBuilders.expand());
    }

    private static LayoutElement card(Context context, String idSuffix, LayoutElement content,
            float paddingDp, float cornerRadiusDp,
            DimensionBuilders.ContainerDimension width,
            DimensionBuilders.ContainerDimension height) {
        ModifiersBuilders.Background background = new ModifiersBuilders.Background.Builder()
                .setColor(ColorBuilders.argb(GRADIENT_FALLBACK))
                .setBrush(new ColorBuilders.LinearGradient.Builder(
                        ColorBuilders.argb(GRADIENT_START),
                        ColorBuilders.argb(GRADIENT_MIDDLE),
                        ColorBuilders.argb(GRADIENT_END))
                        .setStartY(DimensionBuilders.dp(0f))
                        .setEndY(DimensionBuilders.dp(110f))
                        .build())
                .setCorner(new ModifiersBuilders.Corner.Builder()
                        .setRadius(DimensionBuilders.dp(cornerRadiusDp))
                        .build())
                .build();
        ModifiersBuilders.Modifiers modifiers = new ModifiersBuilders.Modifiers.Builder()
                .setBackground(background)
                .setClickable(openClickable(context, idSuffix))
                .setSemantics(new ModifiersBuilders.Semantics.Builder()
                        .setContentDescription("Open Codex Meter")
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

    /** Matches the phone battery widget's rounded 228-degree arc and wide bottom opening. */
    private static LayoutElement dial(UsageWindow window, String label, int accent, float sizeDp,
            OneUiTileText text) {
        float progress = WearGlanceFormat.remainingProgress(window);
        LayoutElementBuilders.Arc.Builder track = new LayoutElementBuilders.Arc.Builder()
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .setAnchorAngle(DimensionBuilders.degrees(DIAL_START_DEGREES))
                .setArcDirection(LayoutElementBuilders.ARC_DIRECTION_CLOCKWISE)
                .addContent(arcLine(DIAL_SWEEP_DEGREES, DIAL_TRACK, sizeDp));
        LayoutElementBuilders.Arc.Builder fill = new LayoutElementBuilders.Arc.Builder()
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .setAnchorAngle(DimensionBuilders.degrees(DIAL_START_DEGREES))
                .setArcDirection(LayoutElementBuilders.ARC_DIRECTION_CLOCKWISE);
        if (window != null && progress > 0f) {
            fill.addContent(arcLine(DIAL_SWEEP_DEGREES * progress, accent, sizeDp));
        }

        LayoutElement value = centeredColumn()
                .addContent(text.element(WearGlanceFormat.remainingNumberText(window),
                        sizeDp >= 70f ? 22f : sizeDp >= 54f ? 18f : 14f, TEXT_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text.element(label, sizeDp >= 70f ? 9f : sizeDp >= 54f ? 8f : 7f,
                        accent,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .build();
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(sizeDp))
                .setHeight(DimensionBuilders.dp(sizeDp))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(track.build())
                .addContent(fill.build())
                .addContent(value)
                .build();
    }

    private static LayoutElementBuilders.ArcLine arcLine(float degrees, int color, float sizeDp) {
        return new LayoutElementBuilders.ArcLine.Builder()
                .setLength(DimensionBuilders.degrees(degrees))
                .setThickness(DimensionBuilders.dp(
                        sizeDp >= 70f ? 6f : sizeDp >= 54f ? 5f : 4f))
                .setColor(ColorBuilders.argb(color))
                .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
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

    private static LayoutElementBuilders.Column.Builder centeredColumn() {
        return new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.wrap())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
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

    private static String emptyCopy(Context context, UsageSnapshot snapshot) {
        WearSyncStatus status = WearPreferences.syncStatus(context);
        if (status.updatedAtMillis > 0L && !status.signedIn) return "Sign in on phone";
        if (status.refreshInProgress) return "Refreshing";
        if (!status.lastError.isEmpty()) return "Refresh failed";
        if (snapshot == null) {
            return WearPreferences.hasSyncedUsage(context) ? "Waiting for sync" : "No usage yet";
        }
        if (!snapshot.allowed) return "Usage unavailable";
        if (snapshot.limitReached) return "Limit reached";
        if (isStale(context)) return "Stale phone data";
        WearSettingsState settings = WearPreferences.settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR);
        String pace = WearGlanceFormat.paceWarning(snapshot, settings.usagePaceEnabled,
                settings.usagePaceSensitivity, System.currentTimeMillis());
        if (!pace.isEmpty()) return pace;
        return WearPreferences.isConnected(context) ? "Phone synced" : "From watch cache";
    }

    private static boolean isStale(Context context) {
        WearSettingsState settings = WearPreferences.settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR);
        return WearGlanceFormat.isStale(WearPreferences.lastUsageAt(context),
                settings.refreshMinutes, System.currentTimeMillis());
    }
}
