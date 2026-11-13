package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.kit.KitScreen;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * The Duo layout on an unfolded Galaxy Z Fold 8: its 2448 x 1848 inner screen at 420 dpi is about
 * 933 x 704 dp. Back, the toolbar and the tabs stand in Glassé's right-edge column and the page lays
 * out beside it; on a phone, or with the layout off, the bars stay where they were. With
 * {@code -Dcap.out=<dir>} it also saves the dashboard and Settings with the layout on and off.
 */
public class DuoLayoutTest {
    static final double FOLD8_W = 933;
    static final double FOLD8_H = 704;
    /** a Galaxy phone (and about a folded Fold 8's cover screen's width) */
    static final double PHONE_W = 412;
    static final double PHONE_H = 915;
    private static final double RAIL = 84;

    @After
    public void restore() {
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
    }

    @Test
    public void railOnlyOnWideScreens() {
        assertTrue(CodexGlass.wide(FOLD8_W, FOLD8_H));
        assertTrue(CodexGlass.wide(704, 933));
        assertFalse(CodexGlass.wide(393, 852));
        assertFalse(CodexGlass.wide(852, 393)); // a phone held sideways stays a phone
    }

    @Test
    public void alwaysPutsTheRailOnPhones() {
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
        assertEquals(0, CodexGlass.railWidth(PHONE_W, PHONE_H), 0);
        CodexGlass.setDuoMode(CodexGlass.DUO_ALWAYS);
        assertEquals(RAIL, CodexGlass.railWidth(PHONE_W, PHONE_H), 0);
        CodexGlass.setDuoMode(CodexGlass.DUO_OFF);
        assertEquals(0, CodexGlass.railWidth(FOLD8_W, FOLD8_H), 0);
    }

    /** Back starts about a quarter of the way down (thumb reach), not by the status bar. */
    @Test
    public void railButtonsSitWithinThumbReach() {
        double top = CodexGlass.railTop(FOLD8_H, 32, 24, 2, 4);
        assertTrue("rail top " + top, top >= FOLD8_H * 0.25 && top <= FOLD8_H * 0.3);
        double phone = CodexGlass.railTop(PHONE_H, 48, 24, 2, 4);
        assertTrue("phone rail top " + phone, phone >= PHONE_H * 0.25);
        // a short window gives the tabs their room first, never going under the status bar
        double shortTop = CodexGlass.railTop(400, 32, 24, 2, 4);
        assertTrue(shortTop >= 44);
        assertTrue(CodexGlass.railTabsFit(400, 32, 24, 2) >= 2);
    }

    /** A tap on another rail tab flies the selection there (frames in between differ), as the bottom bar does. */
    @Test
    public void railSelectionSlides() {
        FakeSource src = new FakeSource();
        Capture c = capture(true, "light", CodexGlass.DASHBOARD, src);
        String before = src.selectedTab();
        // the rail's tabs: the column at the right edge, the second tab from the top of the tab capsule
        double x = FOLD8_W - RAIL / 2;
        int n = Math.min(src.tabs().size(), CodexGlass.railTabsFit(FOLD8_H, 32, 24, 2));
        double tabsTop = FOLD8_H - 24 - 8 - (n * CodexGlass.RAIL_TAB + 8);
        c.tap(x, tabsTop + 4 + CodexGlass.RAIL_TAB * 1.5, 80.0);
        c.run(60);
        byte[] mid = c.image().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100, 6).getBytes();
        c.run(1500);
        byte[] end = c.image().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100, 6).getBytes();
        assertFalse("tab did not change", before.equals(src.selectedTab()));
        assertFalse("no frame between the tabs", java.util.Arrays.equals(mid, end));
    }

    @Test
    public void fold8DashboardKeepsThePageBesideTheRail() {
        Capture c = capture(true, "light", CodexGlass.DASHBOARD);
        List<double[]> texts = texts(c, CodexGlass.DASHBOARD);
        // the rail's tabs are drawn by Glassé's RailTabView (no text nodes); every page text stays left of it
        for (double[] t : texts) {
            assertTrue("page text runs under the rail at x " + (t[0] + t[2]), t[0] + t[2] <= FOLD8_W - RAIL + 1);
        }
    }

    @Test
    public void layoutOffKeepsTheBottomTabBar() {
        Capture c = capture(false, "light", CodexGlass.DASHBOARD);
        for (double[] t : texts(c, CodexGlass.DASHBOARD)) {
            // the tab bar's labels sit along the bottom, none in a right-edge column
            if (t[1] > FOLD8_H - 80) {
                return;
            }
        }
        throw new AssertionError("no bottom tab bar with the Duo layout off");
    }

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        if (out == null) {
            return;
        }
        for (String scheme : new String[] {"light", "dark"}) {
            CodexGlass.setDuoMode(CodexGlass.DUO_ALWAYS);
            FakeSource ps = new FakeSource();
            Map<String, ScreenMaker> pm = new LinkedHashMap<>();
            pm.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, ps));
            pm.put(CodexGlass.DASHBOARD, env -> CodexGlass.dashboard(env, ps));
            Capture pc = new Capture(PHONE_W, PHONE_H, 48, 24, 2, scheme, env -> CodexGlass.dashboard(env, ps), pm);
            pc.host.open(CodexGlass.DASHBOARD, true);
            pc.run(2500);
            pc.shot(out + "/phone-always-" + scheme + "-01-dashboard.png");
            pc.host.close();
            pc.run(1500);
            pc.host.open(CodexGlass.SETTINGS, true);
            pc.run(2500);
            pc.shot(out + "/phone-always-" + scheme + "-02-settings.png");
            for (boolean duo : new boolean[] {true, false}) {
                String tag = (duo ? "duo" : "standard") + "-" + scheme;
                Capture c = capture(duo, scheme, CodexGlass.DASHBOARD);
                c.shot(out + "/fold8-" + tag + "-01-dashboard.png");
                c.drag(FOLD8_W / 3, FOLD8_H * 3 / 4, FOLD8_W / 3, FOLD8_H / 4, 600);
                c.run(1500);
                c.shot(out + "/fold8-" + tag + "-02-scrolled.png");
                c = capture(duo, scheme, CodexGlass.SETTINGS);
                c.shot(out + "/fold8-" + tag + "-03-settings.png");
            }
        }
    }

    private static Capture capture(boolean duo, String scheme, String app) {
        return capture(duo, scheme, app, new FakeSource());
    }

    private static Capture capture(boolean duo, String scheme, String app, FakeSource src) {
        CodexGlass.setDuoMode(duo ? CodexGlass.DUO_WIDE : CodexGlass.DUO_OFF);
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        makers.put(CodexGlass.DASHBOARD, env -> CodexGlass.dashboard(env, src));
        boolean shots = System.getProperty("cap.out") != null;
        Capture c = new Capture(FOLD8_W, FOLD8_H, 32, 24, shots ? 2 : 1, scheme,
                env -> CodexGlass.dashboard(env, src), makers);
        c.host.open(app, true);
        c.run(2500);
        return c;
    }

    /** Every text on {@code app}'s screen: x, y, width. */
    private static List<double[]> texts(Capture c, String app) {
        KitScreen kit = (KitScreen) c.host.apps.get(app);
        kit.scene.layout();
        Pattern re = Pattern.compile("text ([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+) \"(.*)\"");
        List<double[]> out = new ArrayList<>();
        for (String line : c.dump(kit.scene.root).split("\n")) {
            Matcher m = re.matcher(line.trim());
            if (m.matches() && !m.group(5).isEmpty()) {
                out.add(new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3))});
            }
        }
        assertFalse(out.isEmpty());
        assertEquals(0, out.stream().filter(t -> Double.isNaN(t[0])).count());
        return out;
    }
}
