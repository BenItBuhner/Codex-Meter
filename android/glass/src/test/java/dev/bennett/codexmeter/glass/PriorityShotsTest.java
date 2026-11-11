package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Priority tab's Recent activity list: rows sit in one grouped card with hairlines between
 * them, a swipe left dismisses a row, and touch and hold opens a menu with Dismiss. Screenshots
 * with {@code -Dcap.out=<dir>}.
 */
public class PriorityShotsTest {
    private static final double W = 393;
    private static final double H = 852;
    /** The first Recent activity row's centre on this phone size, under the large title. */
    private static final double FIRST_ROW_Y = 231;

    @Test
    public void swipeAndHoldDismiss() {
        String out = System.getProperty("cap.out");
        String scheme = System.getProperty("cap.scheme", "dark");
        FakeSource src = new FakeSource();
        src.priority = true;
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        Capture c = new Capture(W, H, 32, 24, out != null ? 2 : 1, scheme,
                env -> CodexGlass.dashboard(env, src), makers);
        c.run(2500);
        if (out != null) {
            c.shot(out + "/priority-" + scheme + ".png");
        }
        // swipe the first row left, all the way
        double y = FIRST_ROW_Y;
        c.host.down(W - 40, y);
        for (int i = 1; i <= 20; i++) {
            c.host.move(W - 40 - (W - 70) * i / 20.0, y);
            c.run(1000.0 / 60);
            if (i == 8 && out != null) {
                c.shot(out + "/priority-swiping-" + scheme + ".png");
            }
        }
        c.host.up(30, y);
        c.run(1500);
        if (out != null) {
            c.shot(out + "/priority-swiped-" + scheme + ".png");
        }
        Assert.assertTrue("a full swipe dismisses the row: " + src.dismissed, src.dismissed.contains(0));
        // touch and hold the row now in its place: the menu opens, and Dismiss is in it
        c.host.down(W / 2, y);
        c.run(900);
        c.host.up(W / 2, y);
        c.run(800);
        if (out != null) {
            c.shot(out + "/priority-hold-" + scheme + ".png");
        }
    }
}
