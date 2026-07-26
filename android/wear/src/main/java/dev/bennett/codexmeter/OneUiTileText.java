package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ProtoLayoutScope;
import androidx.wear.protolayout.ResourceBuilders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Renders quota-safe tile copy in Samsung's local One UI Sans system typeface. */
final class OneUiTileText {
    private static final float MAX_RENDER_SCALE = 1.25f;
    private final float fontScale;
    private final ProtoLayoutScope scope;

    OneUiTileText(Context context, ProtoLayoutScope scope) {
        float density = context.getResources().getDisplayMetrics().density;
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        fontScale = density <= 0f ? 1f : scaledDensity / density;
        this.scope = scope;
    }

    LayoutElement element(String value, float sizeSp, int color, int weight) {
        String text = value == null ? "" : value;
        int numericWeight = weight == LayoutElementBuilders.FONT_WEIGHT_BOLD ? 700 : 400;
        float logicalSize = sizeSp * fontScale;
        float renderScale = MAX_RENDER_SCALE;
        Paint paint;
        int widthPx;
        int heightPx;
        do {
            paint = textPaint(logicalSize * renderScale, color, numericWeight);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            widthPx = Math.max(1, (int) Math.ceil(paint.measureText(text)) + 2);
            heightPx = Math.max(1, (int) Math.ceil(metrics.descent - metrics.ascent) + 2);
            long byteCount = (long) widthPx * heightPx * 4L;
            if (byteCount <= TileImageResources.MAX_INLINE_IMAGE_BYTES) break;
            renderScale *= Math.sqrt(
                    (TileImageResources.MAX_INLINE_IMAGE_BYTES * 0.96d) / byteCount);
        } while (renderScale > 0.25f);

        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawText(text, 1f, 1f - paint.getFontMetrics().ascent, paint);
        ResourceBuilders.InlineImageResource inline = TileImageResources.argb8888(bitmap);
        bitmap.recycle();
        ResourceBuilders.ImageResource image =
                new ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(inline)
                        .build();
        String resourceId = resourceId(text, logicalSize, color, numericWeight);
        return new LayoutElementBuilders.Image.Builder(scope)
                .setImageResource(image, resourceId)
                .setWidth(DimensionBuilders.dp(widthPx / renderScale))
                .setHeight(DimensionBuilders.dp(heightPx / renderScale))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setSemantics(new ModifiersBuilders.Semantics.Builder()
                                .setContentDescription(text)
                                .build())
                        .build())
                .build();
    }

    private static Paint textPaint(float sizePx, int color, int weight) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextSize(sizePx);
        paint.setTypeface(Typeface.create(Typeface.create("sec", Typeface.NORMAL), weight, false));
        return paint;
    }

    private static String resourceId(String text, float sizeSp, int color, int weight) {
        String source = text + '\u0000' + sizeSp + '\u0000' + color + '\u0000' + weight;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("oneui_text_");
            for (int i = 0; i < 8; i++) {
                id.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "oneui_text_" + Integer.toHexString(source.hashCode());
        }
    }
}
