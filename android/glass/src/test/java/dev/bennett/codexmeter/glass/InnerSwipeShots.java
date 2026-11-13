package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** Debug: a back swipe from About to Settings' first page, every frame. Only with -Dcap.inner=dir. */
public class InnerSwipeShots {
    private Capture c;
    private String out;
    private int frame;

    private void step(int n) {
        for (int i = 0; i < n; i++) {
            c.run(1000.0 / 60);
            c.shot(String.format("%s/f%04d.png", out, frame++));
        }
    }

    @Test
    public void innerSwipe() {
        out = System.getProperty("cap.inner");
        if (out == null) {
            return;
        }
        double w = 412, h = 891;
        CodexGlass.setDuoMode(CodexGlass.DUO_ALWAYS);
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        c = new Capture(w, h, 40, 24, 1, "light", env -> CodexGlass.dashboard(env, src), makers);
        CodexGlass.attach(c.host);
        c.run(2500);
        c.host.open(CodexGlass.SETTINGS, false);
        c.run(1500);
        src.navigate(new GlassSource.Nav(false, "x:about"));
        c.run(1500);
        step(4);
        // a quick flick
        c.host.down(10, h / 2);
        for (int i = 1; i <= 12; i++) { c.host.move(10 + 140.0 * i / 12, h / 2); step(1); }
        c.host.up(150, h / 2);
        step(50);
        src.navigate(new GlassSource.Nav(false, "x:about"));
        c.run(1500);
        step(4);
        // a slow drag past the middle
        c.host.down(10, h / 2);
        for (int i = 1; i <= 40; i++) { c.host.move(10 + 250.0 * i / 40, h / 2); step(1); }
        c.host.up(260, h / 2);
        step(50);
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
        CodexGlass.shared = false;
    }
}
