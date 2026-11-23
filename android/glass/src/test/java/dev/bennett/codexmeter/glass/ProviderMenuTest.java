package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * A provider tab's bar has Edit, ⋯ and Settings; Priority's has Edit and Settings, so switching
 * between them morphs the bar. The ⋯ menu opens with Glassé's menu motion and its rows work.
 */
public class ProviderMenuTest {
    private static final double W = 393;
    private static final double H = 852;
    /** the bar's buttons: Settings at the trailing edge, ⋯ before it */
    static final double BAR_Y = 32 + 30;
    static final double MENU_X = 300;

    private FakeSource src;
    private Capture c;

    private void shot(String name) {
        String out = System.getProperty("cap.out");
        if (out != null) {
            c.shot(out + "/provider-menu-" + name + ".png");
        }
    }

    @Test
    public void theMenuOpensAndItsRowsWork() {
        src = new FakeSource();
        c = new Capture(W, H, 32, 24, 1, "dark", env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
        shot("1-provider");
        c.tap(MENU_X, BAR_Y, 80);
        c.run(900);
        shot("2-open");
        c.tap(MENU_X - 60, ROW1_Y, 80);
        c.run(900);
        Assert.assertEquals("the first row ran", java.util.Collections.singletonList("history"), src.menuPicks);
        src.priority = true;
        src.changed();
        c.run(900);
        shot("3-priority");
    }

    static final double ROW1_Y = 71;
}
