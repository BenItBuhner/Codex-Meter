package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.util.LinkedHashMap;
import org.junit.Test;

/**
 * Debug: every 60 Hz frame of More with six providers (open its list, push a provider, Back, push
 * another, swipe back from the edge, push again, tap More to pop to the list), for recordings. Only with
 * {@code -Dcap.frames=<dir>}.
 */
public class MoreTabFrames {
    private static final double W = 393;
    private static final double H = 852;

    private Capture c;
    private String dir;
    private int frame;

    private void step(int n) {
        for (int i = 0; i < n; i++) {
            c.run(1000.0 / 60);
            c.shot(String.format("%s/f%04d.png", dir, frame++));
        }
    }

    private void tap(double x, double y) {
        c.host.down(x, y);
        step(5);
        c.host.up(x, y);
    }

    @Test
    public void record() {
        String out = System.getProperty("cap.frames");
        if (out == null) {
            return;
        }
        dir = out + "/more";
        new File(dir).mkdirs();
        FakeSource src = new FakeSource();
        src.moreTabs = true;
        c = new Capture(W, H, 32, 24, 1, System.getProperty("cap.scheme", "dark"),
                env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
        step(10);
        tap(MoreTabTest.MORE_X, MoreTabTest.BAR_Y); // More's list
        step(45);
        tap(W / 2, MoreTabTest.FIRST_ROW_Y + MoreTabTest.ROW_H); // Antigravity pushes
        step(50);
        tap(MoreTabTest.BACK_X, MoreTabTest.BACK_Y); // Back to the list
        step(50);
        tap(W / 2, MoreTabTest.FIRST_ROW_Y + 2 * MoreTabTest.ROW_H); // Grok pushes
        step(50);
        // an edge swipe back, a thumb's pace
        c.host.backStart();
        for (int i = 1; i <= 18; i++) {
            c.host.backProgress(i * 14);
            step(1);
        }
        c.host.backCommit(true, 0.6);
        step(50);
        tap(W / 2, MoreTabTest.FIRST_ROW_Y + MoreTabTest.ROW_H); // Antigravity
        step(50);
        tap(MoreTabTest.MORE_X, MoreTabTest.BAR_Y); // More again pops to the list
        step(50);
    }
}
