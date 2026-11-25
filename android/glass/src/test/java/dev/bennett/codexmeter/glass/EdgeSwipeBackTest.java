package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * A back swipe from the screen's left edge on a Settings page. The kit drags the page itself until
 * Android recognises its back gesture, then Android cancels the touch and drives the swipe through
 * predictive back. While the finger dragged, back used to stop counting as the app's, so its
 * predictive-back callback was dropped and Android's back-to-home took over (Settings › Providers
 * peeked at the page under it, then shrank away over black, or the app closed).
 */
public class EdgeSwipeBackTest {
    private Capture open() {
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        Capture c = new Capture(412, 891, 40, 24, 1, "light", env -> CodexGlass.dashboard(env, src), makers);
        c.run(1500);
        c.host.open(CodexGlass.SETTINGS, true);
        c.run(800);
        return c;
    }

    @Test
    public void backStaysTheAppsWhileTheFingerDrags() {
        Capture c = open();
        assertTrue(c.host.backActive());
        c.host.down(2, 420);
        for (int x = 10; x <= 90; x += 10) {
            c.host.move(x, 420);
            c.run(1000.0 / 60);
            assertTrue("back left the app at x=" + x, c.host.backActive());
        }
        // Android takes the finger for its back gesture: the touch is cancelled...
        c.host.up(90, 420, true);
        // ...and predictive back drives the same swipe, which the user then takes back
        assertTrue(c.host.backActive());
        c.host.backStart();
        c.host.backProgress(60);
        c.run(1000.0 / 60);
        assertTrue(c.host.backActive());
        c.host.backCancel(0);
        c.run(1200);
        assertTrue("Settings still open after a cancelled back", c.host.backActive());
        assertEquals(CodexGlass.SETTINGS, c.host.app);
    }

    @Test
    public void cancelledTouchDoesNotCloseThePage() {
        Capture c = open();
        c.host.down(2, 420);
        // a fast flick that would close Settings if the finger lifted
        for (int x = 20; x <= 220; x += 40) {
            c.host.move(x, 420);
            c.run(4);
        }
        c.host.up(220, 420, true);
        c.run(1200);
        assertEquals(CodexGlass.SETTINGS, c.host.app);
        assertTrue(c.host.backActive());
    }
}
