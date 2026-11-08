package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.ui.Assets;
import java.io.File;
import java.nio.file.Files;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Color;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Rect;
import org.jetbrains.skia.SamplingMode;
import org.jetbrains.skia.Surface;
import org.junit.Test;

/**
 * The home-screen widget's Liquid Glass panel (Glassé's glass for widgets). It is the widget's
 * size, transparent at its corners, and its tint follows the widget's background strength. With
 * {@code -Dcap.out=<dir>} it saves the panel over a wallpaper, light and dark, with and without a
 * backdrop to refract.
 */
public class WidgetGlassTest {
    @Test
    public void tintFollowsBackgroundStrength() {
        assertEquals(0, GlassWidgetArt.tint(0), 0);
        assertTrue(GlassWidgetArt.tint(56) < GlassWidgetArt.tint(88));
        assertTrue(GlassWidgetArt.tint(88) < GlassWidgetArt.tint(100));
        assertTrue(GlassWidgetArt.tint(100) <= 0.7);
        assertTrue(GlassWidgetArt.fill(56, false) < GlassWidgetArt.fill(100, false));
        assertEquals(0, GlassWidgetArt.fill(0, true), 0);
    }

    @Test
    public void panelIsTheWidgetsSizeWithClearCorners() {
        Image img = GlassWidgetArt.render(null, 0, 0, 700, 330, 2.75, false, 88);
        assertEquals(700, img.getWidth());
        assertEquals(330, img.getHeight());
        Bitmap b = new Bitmap();
        b.allocN32Pixels(700, 330, false);
        img.readPixels(b, 0, 0);
        assertEquals(0, Color.INSTANCE.getA(b.getColor(0, 0)));
        assertTrue("glass is drawn in the middle", Color.INSTANCE.getA(b.getColor(350, 165)) > 80);
    }

    @Test
    public void shots() throws Exception {
        String out = System.getProperty("cap.out");
        if (out == null) {
            return;
        }
        Image wall = Assets.photo(5);
        int W = 1080;
        int H = 900;
        for (boolean dark : new boolean[] {false, true}) {
            for (boolean refract : new boolean[] {false, true}) {
                Surface s = Surface.Companion.makeRasterN32Premul(W, H);
                s.getCanvas().drawImageRect(wall, Rect.makeWH(wall.getWidth(), wall.getHeight()),
                        Rect.makeWH(W, H), SamplingMode.Companion.getLINEAR(), null, true);
                Image screen = s.makeImageSnapshot();
                int[][] widgets = {{60, 80, 960, 420, 88}, {60, 540, 450, 300, 56}, {570, 540, 450, 300, 100}};
                for (int[] w : widgets) {
                    Image glass = GlassWidgetArt.render(refract ? screen : null, w[0], w[1], w[2], w[3], 2.75,
                            dark, w[4]);
                    s.getCanvas().drawImage(glass, w[0], w[1]);
                }
                String name = "widget-glass-" + (dark ? "dark" : "light") + (refract ? "-preview" : "-home") + ".png";
                File f = new File(out, name);
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), s.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100, 6).getBytes());
            }
        }
    }
}
