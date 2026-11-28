package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * More, with six providers on: the bar shows the first three and More. More is iOS's More tab, a
 * pushed list: tapping it shows a page with the other providers, a row pushes that provider's page
 * with Back, and More stays the selected tab throughout.
 *
 * <p>Regression cover for the More bugs Bennett hit on alpha.32 to .34 (PRs #265, #266, #269):
 * tapping More again after picking from it did nothing, and the sheet's X did not close it. The
 * sheet is gone, so these pin the pushed list's equivalents: More again pops back to the list,
 * Back (the bar's button, Android's back and the edge swipe) returns to the list every time, and
 * a row works after any number of rounds.
 */
public class MoreTabTest {
    static final double W = 393;
    static final double H = 852;
    /** the tab bar's tabs: the bar's centre line, Codex (the first of four) and More (the fourth) */
    static final double BAR_Y = H - 24 - 14 - 31;
    static final double CODEX_X = 78;
    static final double MORE_X = 316;
    /** More's list page: its rows (Copilot, Antigravity, Grok) under the large title */
    static final double FIRST_ROW_Y = 183;
    static final double ROW_H = 52;
    /** the bar's Back button on a page More pushed */
    static final double BACK_X = 38;
    static final double BACK_Y = 32 + 6 + 4 + 22;

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

    private void tapMore() {
        c.tap(MORE_X, BAR_Y, 80);
        c.run(900);
    }

    private void pick(int row) {
        c.tap(W / 2, FIRST_ROW_Y + row * ROW_H, 80);
        c.run(900);
    }

    /** the list is in front: its Grok row picks Grok (on a provider's page that spot is a card) */
    private void assertListShows(String why) {
        String before = src.selectedTab();
        pick(2);
        Assert.assertEquals(why, "grok", src.selectedTab());
        // and back to where it was, so the caller's next step starts from the list again
        c.host.back();
        c.run(900);
        src.selectTab(before);
        c.run(300);
    }

    /** green in a 3x3 patch around (x, y) of the current frame */
    private int green(double x, double y) throws java.io.IOException {
        File f = File.createTempFile("more", ".png");
        c.shot(f.getPath());
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
        f.delete();
        return (img.getRGB((int) x, (int) y) >> 8) & 0xff;
    }

    @Test
    public void moreShowsItsListAsAPageAndARowPushesTheProvider() {
        open();
        tapMore();
        shot("1-list");
        Assert.assertEquals("opening More's list picks nothing yet", "codex", src.selectedTab());
        Assert.assertFalse("More's list is the tab's root: Android's back leaves it alone", c.host.back());
        c.run(300);
        pick(1); // Antigravity
        shot("2-pushed");
        Assert.assertEquals("the list's row pushes its provider", "antigravity", src.selectedTab());
    }

    @Test
    public void theBarsBackButtonReturnsToTheListEveryTime() {
        open();
        tapMore();
        for (int round = 0; round < 3; round++) {
            pick(1); // Antigravity
            Assert.assertEquals("round " + round + ": pushed", "antigravity", src.selectedTab());
            c.tap(BACK_X, BACK_Y, 80);
            c.run(900);
            if (round == 0) {
                shot("3-back");
            }
        }
        pick(2);
        Assert.assertEquals("after three Backs the list still picks", "grok", src.selectedTab());
    }

    @Test
    public void androidBackReturnsToTheList() {
        open();
        tapMore();
        pick(0); // Copilot
        Assert.assertEquals("copilot", src.selectedTab());
        Assert.assertTrue("a provider More pushed takes Android's back", c.host.back());
        c.run(900);
        pick(2);
        Assert.assertEquals("Android's back went to More's list", "grok", src.selectedTab());
    }

    @Test
    public void anEdgeSwipeReturnsToTheList() {
        open();
        tapMore();
        pick(1);
        Assert.assertTrue("the pushed page can be swiped back", c.host.backStart());
        for (int i = 1; i <= 12; i++) {
            c.host.backProgress(i * 22);
            c.run(1000.0 / 60);
        }
        shot("4-swipe-mid");
        c.host.backCommit(true, 0.8);
        c.run(900);
        pick(2);
        Assert.assertEquals("the swipe went back to More's list", "grok", src.selectedTab());
    }

    @Test
    public void tappingMoreAgainPopsBackToTheList() {
        // #265: picking from More and tapping More again used to do nothing
        open();
        tapMore();
        pick(1); // Antigravity
        tapMore();
        shot("5-again");
        pick(2); // Grok
        Assert.assertEquals("More again showed its list, and the list picked Grok", "grok", src.selectedTab());
        tapMore();
        pick(0);
        Assert.assertEquals("and again", "copilot", src.selectedTab());
    }

    @Test
    public void moreKeepsItsPlaceAcrossTabs() {
        open();
        tapMore();
        pick(1); // Antigravity
        c.tap(CODEX_X, BAR_Y, 80);
        c.run(900);
        Assert.assertEquals("another tab", "codex", src.selectedTab());
        tapMore();
        Assert.assertEquals("More shows the provider it had pushed, as iOS does", "antigravity", src.selectedTab());
        Assert.assertTrue("with Back to its list", c.host.back());
        c.run(900);
        c.tap(CODEX_X, BAR_Y, 80);
        c.run(900);
        tapMore();
        Assert.assertEquals("popped to the list, More opens on the list", "codex", src.selectedTab());
        assertListShows("More's list is in front");
    }

    @Test
    public void codexTabLeavesMore() {
        open();
        tapMore();
        c.tap(CODEX_X, BAR_Y, 80);
        c.run(900);
        shot("6-codex");
        // Codex's page is in front again: the list's Grok row spot is Codex's page, not a row
        pick(2);
        Assert.assertEquals("picking Codex left More's list", "codex", src.selectedTab());
    }

    @Test
    public void theDuoColumnPushesAndGoesBackToo() {
        // the Duo layout on a short window: the right-edge column has room for four tabs and More
        CodexGlass.setDuoMode(CodexGlass.DUO_ALWAYS);
        try {
            src = new FakeSource();
            src.moreTabs = true;
            c = new Capture(W, 440, 32, 24, 1, "dark", env -> CodexGlass.dashboard(env, src),
                    new LinkedHashMap<String, ScreenMaker>());
            c.run(2500);
            c.tap(350, 381, 80); // More, the column's last tab
            c.run(900);
            shot("7-duo-list");
            c.tap(W / 2 - 40, 178, 80); // Antigravity
            c.run(900);
            shot("8-duo-pushed");
            Assert.assertEquals("the column's More list pushes", "antigravity", src.selectedTab());
            c.tap(350, 67, 80); // Back, leading the column
            c.run(900);
            shot("9-duo-back");
            c.tap(W / 2 - 40, 230, 80); // Grok
            c.run(900);
            Assert.assertEquals("the column's Back went to More's list", "grok", src.selectedTab());
        } finally {
            CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
        }
    }
}
