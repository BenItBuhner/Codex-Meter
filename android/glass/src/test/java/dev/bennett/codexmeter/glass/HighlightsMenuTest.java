package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * Usage history's highlights button is a pull-down menu (not a sheet): it morphs out of the bar
 * button, each highlight is a toggle with a checkmark, a tap flips it and the menu stays open, and a
 * second tap turns it back on. Shots/frames: {@code -Dcap.out=<dir>} / {@code -Dcap.frames=<dir>}.
 */
public class HighlightsMenuTest {
    private static final double W = 393;
    private static final double H = 852;
    /** the bar's trailing button */
    static final double BUTTON_X = W - 16 - 22;
    static final double BAR_Y = 32 + 30;
    /** the first toggle (How to read the charts), under the menu's section title */
    static final double ROW1_X = 250;
    static final double ROW1_Y = 100;

    private Capture c;

    private void shot(String name) {
        String out = System.getProperty("cap.out");
        if (out != null) {
            c.shot(out + "/highlights-menu-" + name + ".png");
        }
    }

    /** 60 Hz frames of open → flip one toggle off and on → close, with {@code -Dcap.frames=<dir>} */
    @Test
    public void frames() {
        String out = System.getProperty("cap.frames");
        if (out == null) {
            return;
        }
        FakeSource src = new FakeSource();
        c = new Capture(W, H, 32, 24, 2, System.getProperty("cap.scheme", "dark"),
                env -> CodexGlass.page(env, src, "x:history"), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
        int[] f = {0};
        Runnable step = () -> {
            c.run(1000.0 / 60);
            c.shot(String.format("%s/f%04d.png", out, f[0]++));
        };
        for (int i = 0; i < 20; i++) step.run();
        c.host.down(BUTTON_X, BAR_Y);
        for (int i = 0; i < 4; i++) step.run();
        c.host.up(BUTTON_X, BAR_Y);
        for (int i = 0; i < 45; i++) step.run();
        for (int k = 0; k < 2; k++) {
            c.host.down(ROW1_X, ROW1_Y + 44);
            for (int i = 0; i < 5; i++) step.run();
            c.host.up(ROW1_X, ROW1_Y + 44);
            for (int i = 0; i < 40; i++) step.run();
        }
        c.host.down(W / 2, H - 80);
        for (int i = 0; i < 4; i++) step.run();
        c.host.up(W / 2, H - 80);
        for (int i = 0; i < 50; i++) step.run();
    }

    @Test
    public void togglesFlipAndTheMenuStaysOpen() {
        for (String scheme : new String[] {"dark", "light"}) {
            FakeSource src = new FakeSource();
            c = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.page(env, src, "x:history"),
                    new LinkedHashMap<String, ScreenMaker>());
            c.run(2500);
            shot(scheme + "-1-page");
            c.tap(BUTTON_X, BAR_Y, 80);
            c.run(900);
            shot(scheme + "-2-open");
            c.tap(ROW1_X, ROW1_Y, 80);
            c.run(700);
            shot(scheme + "-3-off");
            Assert.assertEquals("the first toggle turned its highlight off",
                    Collections.singleton("How to read the charts"), src.hiddenHighlights);
            c.tap(ROW1_X, ROW1_Y, 80);
            c.run(700);
            shot(scheme + "-4-on-again");
            Assert.assertTrue("a second tap in the still-open menu turned it back on", src.hiddenHighlights.isEmpty());
            // a tap outside closes it
            c.tap(W / 2, H - 80, 80);
            c.run(900);
            shot(scheme + "-5-closed");
        }
    }
}
