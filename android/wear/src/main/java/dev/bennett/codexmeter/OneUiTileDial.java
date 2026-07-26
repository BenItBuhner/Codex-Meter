package dev.bennett.codexmeter;

import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;

/** Builds Twidget's inset usage ring from quota-free native ProtoLayout arcs. */
final class OneUiTileDial {
    private static final int TRACK = 0x4DFFFFFF;
    private static final int PROGRESS = 0xFF6B6EE0;
    private static final float START_DEGREES = 225f;
    private static final float SWEEP_DEGREES = 270f;
    private static final float ARC_DIAMETER_DP = 52f;
    private static final float STROKE_DP = 4f;

    private OneUiTileDial() {
    }

    static LayoutElement element(UsageWindow window) {
        LayoutElementBuilders.Arc.Builder track = new LayoutElementBuilders.Arc.Builder()
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .setAnchorAngle(DimensionBuilders.degrees(START_DEGREES))
                .setArcDirection(LayoutElementBuilders.ARC_DIRECTION_CLOCKWISE)
                .addContent(arcLine(SWEEP_DEGREES, TRACK));
        LayoutElementBuilders.Arc.Builder fill = new LayoutElementBuilders.Arc.Builder()
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .setAnchorAngle(DimensionBuilders.degrees(START_DEGREES))
                .setArcDirection(LayoutElementBuilders.ARC_DIRECTION_CLOCKWISE);
        float progress = WearGlanceFormat.remainingProgress(window);
        if (window != null && progress > 0f) {
            fill.addContent(arcLine(SWEEP_DEGREES * progress, PROGRESS));
        }
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(ARC_DIAMETER_DP))
                .setHeight(DimensionBuilders.dp(ARC_DIAMETER_DP))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(track.build())
                .addContent(fill.build())
                .build();
    }

    private static LayoutElementBuilders.ArcLine arcLine(float degrees, int color) {
        return new LayoutElementBuilders.ArcLine.Builder()
                .setLength(DimensionBuilders.degrees(degrees))
                .setThickness(DimensionBuilders.dp(STROKE_DP))
                .setColor(ColorBuilders.argb(color))
                .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                .build();
    }
}
