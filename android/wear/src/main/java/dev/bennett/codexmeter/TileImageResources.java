package dev.bennett.codexmeter;

import android.graphics.Bitmap;
import androidx.wear.protolayout.ResourceBuilders;
import java.nio.ByteBuffer;

/** Shared conversion for renderer-portable inline images below ProtoLayout's 10 KB quota. */
final class TileImageResources {
    static final int MAX_INLINE_IMAGE_BYTES = 10 * 1024;

    private TileImageResources() {
    }

    static ResourceBuilders.InlineImageResource argb8888(Bitmap bitmap) {
        if (bitmap.getByteCount() > MAX_INLINE_IMAGE_BYTES) {
            throw new IllegalArgumentException("Inline tile image exceeds 10 KB");
        }
        ByteBuffer pixels = ByteBuffer.allocate(bitmap.getByteCount());
        bitmap.copyPixelsToBuffer(pixels);
        return new ResourceBuilders.InlineImageResource.Builder()
                .setData(pixels.array())
                .setWidthPx(bitmap.getWidth())
                .setHeightPx(bitmap.getHeight())
                .setFormat(ResourceBuilders.IMAGE_FORMAT_ARGB_8888)
                .build();
    }
}
