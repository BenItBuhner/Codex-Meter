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
    public void bottomEdgeMatchesTopEdge() {
        // The glass ends as close to the widget's bottom as to its top: nothing extra hangs under it.
        int w = 820;
        int h = 400;
        Image img = GlassWidgetArt.render(null, 0, 0, w, h, 2.8125, false, 88);
        Bitmap b = new Bitmap();
        b.allocN32Pixels(w, h, false);
        img.readPixels(b, 0, 0);
        int x = w / 2;
        int top = 0;
        while (top < h && Color.INSTANCE.getA(b.getColor(x, top)) == 0) top++;
        int bottom = 0;
        while (bottom < h && Color.INSTANCE.getA(b.getColor(x, h - 1 - bottom)) == 0) bottom++;
        assertTrue("clear margin above " + top + " and below " + bottom, Math.abs(top - bottom) <= 1);
    }

    @Test
    public void shadowFollowsTheContinuousCorner() {
        // The contact shadow (a few device px wide) hugs the glass's continuous ("squircle") corner as evenly as its straight
        // edges. A circular shadow stood out past the rim's shoulders and read as the fill bleeding.
        double d = 2.625;
        int w = 900;
        int h = 480;
        Image img = GlassWidgetArt.render(null, 0, 0, w, h, d, true, 100);
        Bitmap b = new Bitmap();
        b.allocN32Pixels(w, h, false);
        img.readPixels(b, 0, 0);
        double m = GlassWidgetArt.MARGIN * d;
        double r = GlassWidgetArt.RADIUS * d;
        org.jetbrains.skia.Path outline = com.benitbuhner.glasse.port.Smooth.path(m, m, w - 2 * m, h - 2 * m, r);
        int n = (int) Math.ceil(m + 1.6 * r);
        boolean[][] in = new boolean[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                in[y][x] = outline.contains(x + 0.5f, y + 0.5f);
            }
        }
        java.util.List<int[]> rim = new java.util.ArrayList<>();
        for (int y = 1; y < n - 1; y++) {
            for (int x = 1; x < n - 1; x++) {
                if (in[y][x] && (!in[y - 1][x] || !in[y + 1][x] || !in[y][x - 1] || !in[y][x + 1])) {
                    rim.add(new int[] {x, y});
                }
            }
        }
        double corner = 0;
        double edge = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (in[y][x]) {
                    continue;
                }
                double best = Double.MAX_VALUE;
                for (int[] q : rim) {
                    best = Math.min(best, Math.hypot(q[0] - x, q[1] - y));
                }
                if (best < 1.5 || best > 3.0) {
                    continue;
                }
                double a = Color.INSTANCE.getA(b.getColor(x, y));
                boolean shoulder = x > m + 0.2 * r && y > m + 0.2 * r;
                if (shoulder) {
                    corner = Math.max(corner, a);
                } else if (x > m + 1.6 * r - 2 || y > m + 1.6 * r - 2) {
                    edge = Math.max(edge, a);
                }
            }
        }
        assertTrue("shadow at the corner " + corner + " vs along the edge " + edge,
                edge > 0 && corner <= edge * 1.25 + 2);
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
