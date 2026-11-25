package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.Assume;
import org.junit.Test;

/**
 * The dashboard's Usage history card (at rest and while a finger reads a moment) and the history
 * page: {@code ./gradlew :glass:test --tests '*HistoryCardShotsTest*' -Dcap.out=<dir>}.
 */
public class HistoryCardShotsTest {
    private static final double W = 393;
    private static final double H = 852;

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        Assume.assumeTrue(out != null);
        for (String scheme : new String[] {"light", "dark"}) {
            FakeSource src = new FakeSource();
            Capture c = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.dashboard(env, src),
                    new LinkedHashMap<String, ScreenMaker>());
            c.run(2000);
            c.drag(W / 2, 650, W / 2, 330, 400);
            c.run(1500);
            c.shot(out + "/history-card-" + scheme + ".png");
            // a finger on the chart: the moment under it shows in the chart's top line
            c.host.down(150, 460);
            c.run(150);
            c.host.move(200, 460);
            c.run(200);
            c.shot(out + "/history-card-scrub-" + scheme + ".png");
            c.host.up(200, 460);
            c.run(300);
            Capture p = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.page(env, src, "x:history"),
                    new LinkedHashMap<String, ScreenMaker>());
            p.run(2500);
            p.shot(out + "/history-page-" + scheme + ".png");
        }
    }
}
