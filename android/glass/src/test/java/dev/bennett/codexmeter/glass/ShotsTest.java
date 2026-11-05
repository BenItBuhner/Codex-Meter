package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/**
 * Renders the Liquid Glass screens offscreen, with sample data, as PNGs:
 * {@code ./gradlew :glass:test -Dcap.out=/some/dir [-Dcap.scheme=dark]}. Skipped without cap.out.
 */
public class ShotsTest {
    private static final double W = 393;
    private static final double H = 852;
    private static final double TOP = 32;
    private static final double BOTTOM = 24;

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        Assume.assumeTrue(out != null);
        String scheme = System.getProperty("cap.scheme", "light");
        double dpr = Double.parseDouble(System.getProperty("cap.dpr", "2"));
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        Capture c = new Capture(W, H, TOP, BOTTOM, dpr, scheme,
                env -> CodexGlass.dashboard(env, src), makers);
        c.run(2500);
        c.shot(out + "/app-dashboard-" + scheme + ".png");
        // pulled down: the refresh spinner
        c.drag(W / 2, 300, W / 2, 420, 400);
        c.shot(out + "/app-pull-" + scheme + ".png");
        c.run(1500);
        // scrolled, so the tab bar minimizes and the title moves into the bar
        c.drag(W / 2, 600, W / 2, 250, 300);
        c.run(1500);
        c.shot(out + "/app-dashboard-scrolled-" + scheme + ".png");

        c.host.open(CodexGlass.SETTINGS, true);
        c.run(2500);
        c.shot(out + "/app-settings-" + scheme + ".png");
        // Refresh & dashboard (third section, first row)
        c.tap(W / 2, rowY(c, "Refresh & dashboard"), 80);
        c.run(1500);
        c.shot(out + "/app-settings-refresh-" + scheme + ".png");
        c.tap(W / 2, rowY(c, "Refresh usage"), 80);
        c.run(1500);
        c.shot(out + "/app-settings-choice-" + scheme + ".png");
        c.host.back();
        c.run(1200);
        c.tap(W / 2, rowY(c, "Clear local history"), 80);
        c.run(1500);
        c.shot(out + "/app-alert-" + scheme + ".png");
    }

    /** The y of the row labelled {@code label} on screen (from the kit's layout dump). */
    static double rowY(Capture c, String label) {
        com.benitbuhner.glasse.ui.Screen s = c.host.app != null ? c.host.apps.get(c.host.app) : null;
        if (!(s instanceof com.benitbuhner.glasse.kit.KitScreen)) {
            return H / 2;
        }
        com.benitbuhner.glasse.kit.KitScreen kit = (com.benitbuhner.glasse.kit.KitScreen) s;
        kit.scene.layout();
        String dump = c.dump(kit.scene.root);
        double found = -1;
        java.util.regex.Pattern re = java.util.regex.Pattern
                .compile("text ([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+) \"(.*)\"");
        for (String line : dump.split("\n")) {
            java.util.regex.Matcher m = re.matcher(line.trim());
            if (m.matches() && label.startsWith(m.group(5))) {
                double y = Double.parseDouble(m.group(2)) + Double.parseDouble(m.group(4)) / 2;
                if (y > TOP + 70 && y < H) {
                    found = y;
                }
            }
        }
        if (found > 0) {
            return found;
        }
        System.out.println(dump);
        throw new AssertionError("no row " + label);
    }
}
