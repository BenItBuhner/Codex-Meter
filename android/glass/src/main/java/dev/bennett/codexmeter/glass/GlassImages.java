package dev.bennett.codexmeter.glass;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.jetbrains.skia.Image;

/** Pictures the app hands over as PNG bytes, decoded once for Glassé's image element. */
final class GlassImages {
    private GlassImages() {}

    private static final Map<byte[], Image> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    static Image of(byte[] png) {
        if (png == null) {
            return null;
        }
        return CACHE.computeIfAbsent(png, bytes -> {
            try {
                return Image.Companion.makeFromEncoded(bytes);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }
}
