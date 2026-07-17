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
import androidx.wear.protolayout.material.Button;
import androidx.wear.protolayout.material.ButtonColors;
import androidx.wear.protolayout.material.ChipColors;
import androidx.wear.protolayout.material.CircularProgressIndicator;
import androidx.wear.protolayout.material.Colors;
import androidx.wear.protolayout.material.ProgressIndicatorColors;
import androidx.wear.protolayout.material.Text;
import androidx.wear.protolayout.material.TitleChip;
import androidx.wear.protolayout.material.Typography;
import androidx.wear.protolayout.material.layouts.EdgeContentLayout;
import androidx.wear.protolayout.material.layouts.PrimaryLayout;

final class CodexTileLayouts {
    static final Colors ONE_UI_COLORS = new Colors(WearGlanceFormat.ONE_UI_PRIMARY,
            WearGlanceFormat.ONE_UI_ON_PRIMARY, WearGlanceFormat.ONE_UI_SURFACE,
            WearGlanceFormat.ONE_UI_ON_SURFACE);

    private CodexTileLayouts() {
    }

    static LayoutElement overview(Context context, DeviceParameters deviceParameters) {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        UsageWindow fiveHour = WearGlanceFormat.currentFiveHour(snapshot);
        UsageWindow weekly = WearGlanceFormat.currentWeekly(snapshot);
        LayoutElement content = column(openModifiers(context, "overview"))
                .addContent(text(context, WearGlanceFormat.remainingPercentText(fiveHour),
                        Typography.TYPOGRAPHY_DISPLAY2, WearGlanceFormat.ONE_UI_ON_SURFACE,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text(context, "5-hour remaining", Typography.TYPOGRAPHY_CAPTION1,
                        WearGlanceFormat.ONE_UI_SECONDARY_TEXT,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .addContent(spacer(6f))
                .addContent(text(context, "Week " + WearGlanceFormat.remainingPercentText(weekly),
                        Typography.TYPOGRAPHY_TITLE3, WearGlanceFormat.ONE_UI_ON_SURFACE,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .build();
        return primary(deviceParameters)
                .setPrimaryLabelTextContent(text(context, "Codex", Typography.TYPOGRAPHY_TITLE3,
                        WearGlanceFormat.ONE_UI_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .setSecondaryLabelTextContent(text(context, emptyCopy(context, snapshot),
                        Typography.TYPOGRAPHY_CAPTION2, WearGlanceFormat.ONE_UI_SECONDARY_TEXT,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .setContent(content)
                .build();
    }

    static LayoutElement progress(Context context, DeviceParameters deviceParameters,
            String label, UsageWindow window) {
        String remaining = WearGlanceFormat.remainingPercentText(window);
        LayoutElement center = column(openModifiers(context, label))
                .addContent(text(context, remaining, Typography.TYPOGRAPHY_DISPLAY2,
                        WearGlanceFormat.ONE_UI_ON_SURFACE,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text(context, label, Typography.TYPOGRAPHY_CAPTION1,
                        WearGlanceFormat.ONE_UI_SECONDARY_TEXT,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .build();
        /*
         * The arc uses remaining progress, matching the big remaining-percent number.
         * A fuller arc therefore means more Codex usage budget is still available.
         */
        return new EdgeContentLayout.Builder(deviceParameters)
                .setEdgeContent(new CircularProgressIndicator.Builder()
                        .setProgress(WearGlanceFormat.remainingProgress(window))
                        .setCircularProgressIndicatorColors(new ProgressIndicatorColors(
                                WearGlanceFormat.ONE_UI_PRIMARY,
                                WearGlanceFormat.ONE_UI_TRACK))
                        .setContentDescription(label + " " + remaining + " remaining")
                        .build())
                .setPrimaryLabelTextContent(text(context, "Codex",
                        Typography.TYPOGRAPHY_CAPTION1, WearGlanceFormat.ONE_UI_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .setContent(center)
                .build();
    }

    static LayoutElement reset(Context context, DeviceParameters deviceParameters) {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        long now = System.currentTimeMillis();
        String window = WearGlanceFormat.nextResetWindowLabel(snapshot, now);
        String relative = WearGlanceFormat.nextResetRelativeText(snapshot, now);
        LayoutElement content = column(openModifiers(context, "reset"))
                .addContent(text(context, relative, Typography.TYPOGRAPHY_DISPLAY3,
                        WearGlanceFormat.ONE_UI_ON_SURFACE,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text(context, window, Typography.TYPOGRAPHY_CAPTION1,
                        WearGlanceFormat.ONE_UI_SECONDARY_TEXT,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .build();
        return primary(deviceParameters)
                .setPrimaryLabelTextContent(text(context, "Next reset",
                        Typography.TYPOGRAPHY_TITLE3, WearGlanceFormat.ONE_UI_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .setContent(content)
                .setPrimaryChipContent(openChip(context, deviceParameters, "Open"))
                .build();
    }

    static LayoutElement monitor(Context context, DeviceParameters deviceParameters) {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        boolean active = WearOngoingMonitor.isActive(context);
        LayoutElement content = column(null)
                .addContent(text(context, active ? "Active" : "Off", Typography.TYPOGRAPHY_DISPLAY3,
                        active ? WearGlanceFormat.ONE_UI_PRIMARY
                                : WearGlanceFormat.ONE_UI_ON_SURFACE,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .addContent(text(context, WearGlanceFormat.focusSummary(snapshot),
                        Typography.TYPOGRAPHY_CAPTION1, WearGlanceFormat.ONE_UI_SECONDARY_TEXT,
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL))
                .build();
        return primary(deviceParameters)
                .setPrimaryLabelTextContent(text(context, "Live monitor",
                        Typography.TYPOGRAPHY_TITLE3, WearGlanceFormat.ONE_UI_PRIMARY,
                        LayoutElementBuilders.FONT_WEIGHT_BOLD))
                .setContent(content)
                .setPrimaryChipContent(openChip(context, deviceParameters, "Open"))
                .build();
    }

    static UsageWindow fiveHour(Context context) {
        return WearGlanceFormat.currentFiveHour(WearPreferences.loadSnapshot(context));
    }

    static UsageWindow weekly(Context context) {
        return WearGlanceFormat.currentWeekly(WearPreferences.loadSnapshot(context));
    }

    static LayoutElement openButton(Context context) {
        return new Button.Builder(context, openClickable(context, "button"))
                .setTextContent("Open")
                .setButtonColors(new ButtonColors(WearGlanceFormat.ONE_UI_PRIMARY,
                        WearGlanceFormat.ONE_UI_ON_PRIMARY))
                .setContentDescription("Open Codex Meter")
                .build();
    }

    private static PrimaryLayout.Builder primary(DeviceParameters deviceParameters) {
        return new PrimaryLayout.Builder(nonNullDevice(deviceParameters));
    }

    private static TitleChip openChip(Context context, DeviceParameters deviceParameters,
            String label) {
        return new TitleChip.Builder(context, label, openClickable(context, "chip"),
                nonNullDevice(deviceParameters))
                .setChipColors(new ChipColors(WearGlanceFormat.ONE_UI_PRIMARY,
                        WearGlanceFormat.ONE_UI_ON_PRIMARY))
                .build();
    }

    private static LayoutElementBuilders.Column.Builder column(ModifiersBuilders.Modifiers modifiers) {
        LayoutElementBuilders.Column.Builder builder = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
        if (modifiers != null) {
            builder.setModifiers(modifiers);
        }
        return builder;
    }

    private static Text text(Context context, String value, int typography, int color, int weight) {
        return new Text.Builder(context, value)
                .setTypography(typography)
                .setColor(ColorBuilders.argb(color))
                .setWeight(weight)
                .setMaxLines(2)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .build();
    }

    private static LayoutElement spacer(float heightDp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(heightDp))
                .build();
    }

    private static ModifiersBuilders.Modifiers openModifiers(Context context, String idSuffix) {
        return new ModifiersBuilders.Modifiers.Builder()
                .setClickable(openClickable(context, idSuffix))
                .setSemantics(new ModifiersBuilders.Semantics.Builder()
                        .setContentDescription("Open Codex Meter")
                        .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON)
                        .build())
                .build();
    }

    private static ModifiersBuilders.Clickable openClickable(Context context, String idSuffix) {
        ComponentName activity = new ComponentName(context, WearMainActivity.class);
        return new ModifiersBuilders.Clickable.Builder()
                .setId("codex_open_" + idSuffix)
                .setOnClick(ActionBuilders.launchAction(activity))
                .build();
    }

    private static DeviceParameters nonNullDevice(DeviceParameters deviceParameters) {
        return deviceParameters == null ? new DeviceParameters.Builder().build() : deviceParameters;
    }

    private static String emptyCopy(Context context, UsageSnapshot snapshot) {
        if (snapshot == null) {
            return WearPreferences.hasSyncedUsage(context) ? "Waiting for sync" : "No usage yet";
        }
        if (WearPreferences.isConnected(context)) {
            return "Phone synced";
        }
        return "From watch cache";
    }
}
