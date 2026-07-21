package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.ProtoLayoutScope;
import androidx.wear.protolayout.ResourceBuilders;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Renders tile copy in the local Samsung system typeface before the remote host can replace it. */
final class OneUiTileText {
    private final float density;
    private final float scaledDensity;
    private final ProtoLayoutScope scope;

    OneUiTileText(Context context, ProtoLayoutScope scope) {
        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        this.scope = scope;
    }

    LayoutElement element(String value, float sizeSp, int color, int weight) {
        String text = value == null ? "" : value;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextSize(sizeSp * scaledDensity);
        Typeface oneUi = Typeface.create("sec", Typeface.NORMAL);
        int numericWeight = weight == LayoutElementBuilders.FONT_WEIGHT_BOLD ? 700 : 400;
        paint.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(oneUi, numericWeight, false)
                : Typeface.create("sec", numericWeight >= 600 ? Typeface.BOLD : Typeface.NORMAL));

        Paint.FontMetrics metrics = paint.getFontMetrics();
        int widthPx = Math.max(1, (int) Math.ceil(paint.measureText(text)) + 2);
        int heightPx = Math.max(1, (int) Math.ceil(metrics.descent - metrics.ascent) + 2);
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawText(text, 1f, 1f - metrics.ascent, paint);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        byte[] png = output.toByteArray();
        String resourceId = resourceId(text, sizeSp, color, numericWeight);
        ResourceBuilders.InlineImageResource inline =
                new ResourceBuilders.InlineImageResource.Builder()
                        .setData(png)
                        .setWidthPx(widthPx)
                        .setHeightPx(heightPx)
                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                        .build();
        ResourceBuilders.ImageResource imageResource =
                new ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(inline)
                        .build();
        return new LayoutElementBuilders.Image.Builder(scope)
                .setImageResource(imageResource, resourceId)
                .setWidth(DimensionBuilders.dp(widthPx / density))
                .setHeight(DimensionBuilders.dp(heightPx / density))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                .build();
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
