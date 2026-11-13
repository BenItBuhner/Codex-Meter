package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Foldables and tablets: on a wide window the dashboard and Settings keep a readable column and the
 * tab bar keeps its phone size, centred, instead of stretching edge to edge. With
 * {@code -Dcap.out=<dir>} it also saves each size's dashboard and Settings.
 */
public class WideWindowTest {
    /** name, width, height (dp): a phone, a foldable's inner screen, a tablet in portrait and landscape */
    private static final Object[][] SIZES = {
            {"phone", 393.0, 852.0},
            {"foldable", 673.0, 841.0},
            {"foldable-420dpi", 707.0, 823.0},
            {"tablet-portrait", 800.0, 1280.0},
            {"tablet-landscape", 1280.0, 800.0},
    };

    @Test
    public void readablePadOnlyGrowsPastTheColumn() {
        assertEquals(16, CodexGlass.readablePad(393), 0);
        assertEquals(16, CodexGlass.readablePad(673), 0);
        assertEquals(280, CodexGlass.readablePad(1280), 0);
    }

    @Test
    public void wideWindows() {
        String out = System.getProperty("cap.out");
        String scheme = System.getProperty("cap.scheme", "light");
        for (Object[] size : SIZES) {
            String name = (String) size[0];
            double w = (Double) size[1];
            double h = (Double) size[2];
            FakeSource src = new FakeSource();
            Map<String, ScreenMaker> makers = new LinkedHashMap<>();
            makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
            makers.put(CodexGlass.DASHBOARD, env -> CodexGlass.dashboard(env, src));
            Capture c = new Capture(w, h, 32, 24, out != null ? 2 : 1, scheme,
                    env -> CodexGlass.dashboard(env, src), makers);
            c.host.open(CodexGlass.DASHBOARD, true);
            c.run(2500);
            // content keeps to the readable column, centred beside the Duo layout's right-edge column
            double room = w - CodexGlass.railWidth(w, h);
            double[] cards = textSpan(c, CodexGlass.DASHBOARD, room);
            assertTrue(name + " content left " + cards[0], cards[0] >= CodexGlass.readablePad(room) - 1);
            assertTrue(name + " content right " + cards[1], cards[1] <= room - CodexGlass.readablePad(room) + 1);
            if (out != null) {
                c.shot(out + "/wide-" + name + "-dashboard-" + scheme + ".png");
            }
            c.host.close();
            c.run(1500);
            c.host.open(CodexGlass.SETTINGS, true);
            c.run(2500);
            if (out != null) {
                c.shot(out + "/wide-" + name + "-settings-" + scheme + ".png");
            }
        }
    }

    /** A drag up scrolls the dashboard on a phone and both foldable sizes (the sample dashboard fits a tablet). */
    @Test
    public void wideDashboardsScroll() {
        for (Object[] size : SIZES) {
            String name = (String) size[0];
            double w = (Double) size[1];
            double h = (Double) size[2];
            if (h > 900) {
                continue;
            }
            FakeSource src = new FakeSource();
            Map<String, ScreenMaker> makers = new LinkedHashMap<>();
            makers.put(CodexGlass.DASHBOARD, env -> CodexGlass.dashboard(env, src));
            Capture c = new Capture(w, h, 32, 24, 1, "light", env -> CodexGlass.dashboard(env, src), makers);
            c.host.open(CodexGlass.DASHBOARD, true);
            c.run(2500);
            byte[] before = c.image().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100, 6).getBytes();
            c.drag(w / 2, h * 3 / 4, w / 2, h / 3, 600);
            c.run(1500);
            byte[] after = c.image().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100, 6).getBytes();
            assertTrue(name + " dashboard did not scroll", !java.util.Arrays.equals(before, after));
        }
    }

    /** The leftmost and rightmost edge of any text on the dashboard page below the nav bar. {@code room}: text from here on is the Duo layout's right-edge column (its tab labels), not the page. */
    private static double[] textSpan(Capture c, String app, double room) {
        com.benitbuhner.glasse.ui.Screen s = c.host.apps.get(app);
        com.benitbuhner.glasse.kit.KitScreen kit = (com.benitbuhner.glasse.kit.KitScreen) s;
        kit.scene.layout();
        Pattern re = Pattern.compile("text ([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+) \"(.*)\"");
        double left = Double.MAX_VALUE;
        double right = 0;
        for (String line : c.dump(kit.scene.root).split("\n")) {
            Matcher m = re.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            double x = Double.parseDouble(m.group(1));
            double y = Double.parseDouble(m.group(2));
            double tw = Double.parseDouble(m.group(3));
            // below the nav bar and above the tab bar
            if (y > 140 && y < 600 && x < room) {
                left = Math.min(left, x);
                right = Math.max(right, x + tw);
            }
        }
        return new double[] {left, right};
    }
}
