package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** Pull to refresh on the dashboard, including one shorter than the screen (a tall phone, few cards). */
public class PullRefreshTest {
    private static final double W = 393;

    private static int pull(boolean shortDashboard, double height) {
        FakeSource src = new FakeSource();
        src.shortDashboard = shortDashboard;
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        Capture c = new Capture(W, height, 32, 24, 1, "light", env -> CodexGlass.dashboard(env, src), makers);
        c.run(1500);
        c.drag(W / 2, 300, W / 2, 560, 500);
        c.run(500);
        return src.refreshes;
    }

    @Test
    public void pullRefreshesATallDashboard() {
        assertEquals(1, pull(false, 852));
    }

    @Test
    public void pullRefreshesAShortDashboard() {
        assertEquals(1, pull(true, 1400));
    }
}
