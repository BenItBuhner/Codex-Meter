package dev.bennett.codexmeter;

import android.graphics.Bitmap;
import androidx.wear.protolayout.ResourceBuilders;
import java.nio.ByteBuffer;

/** Shared conversion for renderer-portable inline ProtoLayout bitmap resources. */
final class TileImageResources {
    private TileImageResources() {
    }

    static ResourceBuilders.InlineImageResource argb8888(Bitmap bitmap) {
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
