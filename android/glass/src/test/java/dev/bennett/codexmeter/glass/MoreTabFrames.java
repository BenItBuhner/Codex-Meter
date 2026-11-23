package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.util.LinkedHashMap;
import org.junit.Test;

/**
 * Debug: every 60 Hz frame of More with six providers (open it, pick a provider, tap More again,
 * press and drag on the list's close button, tap it), for before / after recordings. Only with
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
        tap(MoreTabTest.MORE_X, MoreTabTest.BAR_Y);
        step(45);
        tap(W / 2, MoreTabTest.FIRST_ROW_Y + MoreTabTest.ROW_H); // Antigravity
        step(50);
        tap(MoreTabTest.MORE_X, MoreTabTest.BAR_Y); // again
        step(50);
        // press the close button and drift a little, as a thumb does
        c.host.down(MoreTabTest.CLOSE_X, MoreTabTest.CLOSE_Y);
        step(12);
        for (int i = 1; i <= 10; i++) {
            c.host.move(MoreTabTest.CLOSE_X - 2.5 * i, MoreTabTest.CLOSE_Y + 3 * i);
            step(1);
        }
        step(12);
        c.host.up(MoreTabTest.CLOSE_X - 25, MoreTabTest.CLOSE_Y + 30);
        step(50);
    }
}
