package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * New models rows on Priority: a swipe and the touch and hold menu both animate the row away
 * before the app drops it, and Dismiss all asks first.
 */
public class DismissRowTest {
    private static final double W = 393;
    private static final double H = 852;
    /** the second New models row's centre (see PriorityGestureFrames) */
    private static final double ROW_Y = 551;
    /** the menu under that row: Dismiss, then Dismiss all */
    private static final double DISMISS_Y = 621;
    private static final double DISMISS_ALL_Y = 665;

    private FakeSource src;
    private Capture c;

    private void open() {
        src = new FakeSource();
        src.priority = true;
        c = new Capture(W, H, 32, 24, 1, "dark", env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
    }

    private void holdAndSlideTo(double y) {
        c.host.down(W / 2, ROW_Y);
        c.run(750);
        for (int i = 1; i <= 10; i++) {
            c.host.move(W / 2 - 40, ROW_Y + (y - ROW_Y) * i / 10.0);
            c.run(1000.0 / 60);
        }
        c.host.up(W / 2 - 40, y);
    }

    @Test
    public void fullSwipeDismissesOnlyAfterTheRowHasGone() {
        open();
        c.host.down(W - 30, ROW_Y);
        for (int i = 1; i <= 16; i++) {
            c.host.move(W - 30 - (W - 80) * i / 16.0, ROW_Y);
            c.run(1000.0 / 60);
        }
        c.host.up(50, ROW_Y);
        c.run(50);
        Assert.assertTrue("the row is still animating away: " + src.dismissedModels, src.dismissedModels.isEmpty());
        c.run(1500);
        Assert.assertEquals("the swiped row is dismissed", java.util.Collections.singletonList(1), src.dismissedModels);
    }

    @Test
    public void holdThenSlideOntoDismissDismissesThatRowAfterTheMenuCloses() {
        open();
        holdAndSlideTo(DISMISS_Y);
        c.run(50);
        Assert.assertTrue("the pick waits for the preview to land: " + src.dismissedModels, src.dismissedModels.isEmpty());
        c.run(2000);
        Assert.assertEquals("Dismiss in the menu dismisses the held row", java.util.Collections.singletonList(1), src.dismissedModels);
    }

    @Test
    public void dismissAllAsksFirst() {
        open();
        holdAndSlideTo(DISMISS_ALL_Y);
        c.run(2000);
        Assert.assertTrue("Dismiss all opens a confirmation instead of clearing: " + src.dismissedModels, src.dismissedModels.isEmpty());
        // a tap outside the confirmation cancels it
        c.tap(W / 2, 120, 80);
        c.run(1500);
        Assert.assertTrue("cancelling keeps every row: " + src.dismissedModels, src.dismissedModels.isEmpty());
    }
}
