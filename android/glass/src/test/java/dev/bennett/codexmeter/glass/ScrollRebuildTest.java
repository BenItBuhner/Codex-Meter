package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * Scrolling the dashboard moves the title into the bar and shrinks the tab bar; neither may build
 * the dashboard's cards again (that read every snapshot from storage on each flip, which made
 * Liquid Glass stutter while scrolling). A change from the app still does.
 */
public class ScrollRebuildTest {
    @Test
    public void scrollingDoesNotRebuildTheDashboard() {
        FakeSource src = new FakeSource();
        Capture c = new Capture(393, 852, 32, 24, 1, "light", env -> CodexGlass.dashboard(env, src),
                new LinkedHashMap<String, ScreenMaker>());
        c.run(2000);
        int built = src.dashboardBuilds;
        for (int i = 0; i < 4; i++) {
            c.drag(196, 600, 196, 250, 300);
            c.run(900);
            c.drag(196, 250, 196, 600, 300);
            c.run(900);
        }
        Assert.assertEquals("scrolling built the dashboard again", built, src.dashboardBuilds);
        src.selectTab("claude_code");
        c.run(600);
        Assert.assertTrue("a change from the app builds it again", src.dashboardBuilds > built);
    }
}
