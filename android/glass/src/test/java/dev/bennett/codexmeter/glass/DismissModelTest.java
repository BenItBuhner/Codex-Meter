package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * Priority's dismiss model is one model: New models rows dismiss exactly like Recent activity rows
 * (a swipe left), instead of the old "Dismiss" row that cleared the whole card. Screenshots with
 * {@code -Dcap.out=<dir>}.
 */
public class DismissModelTest {
    private static final double W = 393;
    private static final double H = 852;

    @Test
    public void newModelsSwipeAwayOneRowAtATime() {
        String out = System.getProperty("cap.out");
        FakeSource src = new FakeSource();
        src.priority = true;
        src.dismissed.add(0);
        src.dismissed.add(1);
        Capture c = new Capture(W, H, 32, 24, out != null ? 2 : 1, "light",
                env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
        if (out != null) {
            c.shot(out + "/priority-new-models.png");
        }
        // find the first New models row by swiping down the page until one goes
        for (double y = 140; y < H - 120 && src.dismissedModels.isEmpty(); y += 22) {
            c.host.down(W - 40, y);
            for (int i = 1; i <= 20; i++) {
                c.host.move(W - 40 - (W - 70) * i / 20.0, y);
                c.run(1000.0 / 60);
            }
            c.host.up(30, y);
            c.run(900);
        }
        Assert.assertEquals("a swipe dismisses one New models row, not the card", 1, src.dismissedModels.size());
        Assert.assertTrue("Used up rows are never dismissible", src.dismissed.size() == 2);
        if (out != null) {
            c.shot(out + "/priority-new-models-swiped.png");
        }
    }

    @Test
    public void dashboardHistoryIsTheRealScrubbableChart() {
        String out = System.getProperty("cap.out");
        FakeSource src = new FakeSource();
        src.historyFirst = true;
        Capture c = new Capture(W, H, 32, 24, out != null ? 2 : 1, "light",
                env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
        if (out != null) {
            c.shot(out + "/dashboard-history-chart.png");
        }
        // drag across the chart: the dashboard card scrubs like the history page's chart
        for (double y = 160; y < 560 && src.scrubbed.isEmpty(); y += 20) {
            c.host.down(60, y);
            for (int i = 1; i <= 10; i++) {
                c.host.move(60 + 25 * i, y);
                c.run(1000.0 / 60);
            }
            if (!src.scrubbed.isEmpty() && out != null) {
                c.shot(out + "/dashboard-history-scrub.png");
            }
            c.host.up(310, y);
            c.run(400);
        }
        Assert.assertFalse("the dashboard's Usage history chart reads out the moment under the finger",
                src.scrubbed.isEmpty());
    }
}
