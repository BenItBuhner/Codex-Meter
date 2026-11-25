package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * More, with six providers on: the bar shows the first three and More. Picking a provider from
 * More's list and tapping More again opens the list again (the bar used to ignore a tap on the tab
 * it already showed), and the list's close button closes it.
 */
public class MoreTabTest {
    private static final double W = 393;
    private static final double H = 852;
    /** the tab bar's tabs: the bar's centre line, and More (the fourth of four) */
    static final double BAR_Y = H - 24 - 14 - 31;
    static final double MORE_X = 316;
    /** More's list: its rows (Copilot, Antigravity, Grok) and its close button */
    static final double FIRST_ROW_Y = 656;
    static final double ROW_H = 52;
    static final double CLOSE_X = 351;
    static final double CLOSE_Y = 600;

    private FakeSource src;
    private Capture c;

    private void open() {
        src = new FakeSource();
        src.moreTabs = true;
        c = new Capture(W, H, 32, 24, 1, "dark", env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
    }

    private void shot(String name) {
        String out = System.getProperty("cap.out");
        if (out != null) {
            c.shot(out + "/more-" + name + ".png");
        }
    }

    @Test
    public void moreOpensAgainAfterPickingFromIt() {
        open();
        c.tap(MORE_X, BAR_Y, 80);
        c.run(900);
        shot("1-open");
        c.tap(W / 2, FIRST_ROW_Y + ROW_H, 80); // Antigravity
        c.run(900);
        shot("2-picked");
        Assert.assertEquals("the list picks its row", "antigravity", src.selectedTab());

        c.tap(MORE_X, BAR_Y, 80);
        c.run(900);
        shot("3-again");
        c.tap(W / 2, FIRST_ROW_Y + 2 * ROW_H, 80); // Grok
        c.run(900);
        Assert.assertEquals("More opened again and its list picked Grok", "grok", src.selectedTab());
    }

    @Test
    public void theCloseButtonClosesTheList() {
        open();
        c.tap(MORE_X, BAR_Y, 80);
        c.run(900);
        c.tap(CLOSE_X, CLOSE_Y, 80);
        c.run(900);
        shot("4-closed");
        // where Antigravity's row was is the page again
        c.tap(W / 2, FIRST_ROW_Y + ROW_H, 80);
        c.run(900);
        Assert.assertEquals("the close button closed the list", "codex", src.selectedTab());
    }

    @Test
    public void aPressedCloseButtonDrawsOverTheList() throws java.io.IOException {
        open();
        c.tap(MORE_X, BAR_Y, 80);
        c.run(900);
        c.host.down(CLOSE_X, CLOSE_Y);
        c.run(200);
        for (int i = 1; i <= 8; i++) {
            c.host.move(CLOSE_X - 4 * i, CLOSE_Y + 5 * i);
            c.run(1000.0 / 60);
        }
        c.run(100);
        shot("5-pressed");
        // the swollen button reaches down over the first row: its glass shows there, not the row's grey
        java.io.File f = java.io.File.createTempFile("more-press", ".png");
        c.shot(f.getPath());
        int rgb = javax.imageio.ImageIO.read(f).getRGB(340, 634);
        f.delete();
        int g = (rgb >> 8) & 0xff;
        Assert.assertTrue("the pressed close button draws over the list (green " + g + ")", g > 60);
        c.host.up(CLOSE_X - 32, CLOSE_Y + 40);
        c.run(900);
    }
}
