package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The filter button beside Settings opens a menu of switches for what the tab shows; a pick flips
 * one and the menu stays open. Screenshots with {@code -Dcap.out=<dir>}.
 */
public class FilterMenuTest {
    static final double W = 393;
    static final double H = 852;
    /** the filter button, left of Settings in the bar */
    static final double FX = Double.parseDouble(System.getProperty("cap.filterX", "302"));
    static final double FY = 32 + 26;

    private static Capture open(FakeSource src) {
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        Capture c = new Capture(W, H, 32, 24, 2, System.getProperty("cap.scheme", "light"),
                env -> CodexGlass.dashboard(env, src), makers);
        c.run(2500);
        return c;
    }

    private static void shot(Capture c, String name) {
        String out = System.getProperty("cap.out");
        if (out != null) {
            c.shot(out + "/" + System.getProperty("cap.scheme", "light") + "-" + name + ".png");
        }
    }

    @Test
    public void priorityFilterFlipsASectionAndStaysOpen() {
        FakeSource src = new FakeSource();
        src.priority = true;
        Capture c = open(src);
        shot(c, "priority-bar");
        c.tap(FX, FY, 80);
        c.run(900);
        shot(c, "priority-filter-open");
        // the fourth row, Used up
        double rowY = Double.parseDouble(System.getProperty("cap.rowY", "260"));
        c.tap(FX - 60, rowY, 80);
        c.run(700);
        shot(c, "priority-filter-flipped");
        assertEquals(1, src.hidden.size());
        c.tap(FX - 60, rowY, 80);
        c.run(700);
        assertTrue(src.hidden.isEmpty());
    }

    @Test
    public void providerFilterHasRearrange() {
        FakeSource src = new FakeSource();
        Capture c = open(src);
        c.tap(FX, FY, 80);
        c.run(900);
        shot(c, "provider-filter-open");
    }
}
