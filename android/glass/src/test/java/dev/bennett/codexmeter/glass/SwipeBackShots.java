package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Frames of Settings closing with a back swipe in the Duo layout (Always, on a phone): the app's
 * own swipe from the page, then the system's predictive back gesture. Only with
 * {@code -Dcap.swipe=<dir>}.
 */
public class SwipeBackShots {
    @Test
    public void swipeBack() {
        String out = System.getProperty("cap.swipe");
        if (out == null) {
            return;
        }
        double w = Double.parseDouble(System.getProperty("cap.w", "412"));
        double h = Double.parseDouble(System.getProperty("cap.h", "891"));
        CodexGlass.setDuoMode(System.getProperty("cap.duo", CodexGlass.DUO_ALWAYS));
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        Capture c = new Capture(w, h, 40, 24, 1, System.getProperty("cap.scheme", "dark"),
                env -> CodexGlass.dashboard(env, src), makers);
        CodexGlass.attach(c.host);
        c.run(2500);
        c.host.open(CodexGlass.SETTINGS, false);
        int[] f = {0};
        Runnable shot = () -> c.shot(String.format("%s/f%04d.png", out, f[0]++));
        for (int i = 0; i < 40; i++) { c.run(1000.0 / 60); shot.run(); }
        // predictive back: the system reports the finger's travel, then commits
        c.host.backStart();
        for (int i = 1; i <= 30; i++) { c.host.backProgress(i * 4.0); c.run(1000.0 / 60); shot.run(); }
        c.host.backCommit(true, -0.5);
        for (int i = 0; i < 50; i++) { c.run(1000.0 / 60); shot.run(); }
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
    }
}
