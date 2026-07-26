package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ProtoLayoutScope;
import androidx.wear.protolayout.ResourceBuilders;
import java.io.ByteArrayOutputStream;

/** Renders Twidget's inset usage ring without ProtoLayout host-specific arc clipping. */
final class OneUiTileDial {
    private static final int BACKGROUND = 0xFF0B0B10;
    private static final int TRACK = 0x4DFFFFFF;
    private static final int PROGRESS = 0xFF6B6EE0;
    private static final float START_DEGREES = 135f;
    private static final float SWEEP_DEGREES = 270f;
    private static final float SIZE_DP = 56f;
    private static final float STROKE_DP = 4f;
    private static final float ARC_INSET_DP = 3.5f;

    private OneUiTileDial() {
    }

    static LayoutElement element(Context context, UsageWindow window, ProtoLayoutScope scope) {
        float density = context.getResources().getDisplayMetrics().density;
        int sizePx = Math.max(1, Math.round(SIZE_DP * density));
        float center = sizePx / 2f;
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACKGROUND);
        canvas.drawCircle(center, center, center, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(STROKE_DP * density);
        float inset = ARC_INSET_DP * density;
        RectF arc = new RectF(inset, inset, sizePx - inset, sizePx - inset);
        paint.setColor(TRACK);
        canvas.drawArc(arc, START_DEGREES, SWEEP_DEGREES, false, paint);
        float progress = WearGlanceFormat.remainingProgress(window);
        if (window != null && progress > 0f) {
            paint.setColor(PROGRESS);
            canvas.drawArc(arc, START_DEGREES, SWEEP_DEGREES * progress, false, paint);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        ResourceBuilders.InlineImageResource inline =
                new ResourceBuilders.InlineImageResource.Builder()
                        .setData(output.toByteArray())
                        .setWidthPx(sizePx)
                        .setHeightPx(sizePx)
                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                        .build();
        ResourceBuilders.ImageResource image =
                new ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(inline)
                        .build();
        String resourceId = "twidget_dial_"
                + (window == null ? "empty" : window.remainingPercent());
        return new LayoutElementBuilders.Image.Builder(scope)
                .setImageResource(image, resourceId)
                .setWidth(DimensionBuilders.dp(SIZE_DP))
                .setHeight(DimensionBuilders.dp(SIZE_DP))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                .build();
    }
}
