package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Debug: every 60 Hz frame of Providers' Edit → drag a row → let go → Done, for before / after recordings of the
 * reorder motion. Only with {@code -Dcap.frames=<dir>} (PNGs in {@code <dir>/reorder-edit}).
 */
public class ReorderEditFrames {
    private Capture c;
    private String dir;
    private int frame;

    private void step(int n) {
        for (int i = 0; i < n; i++) {
            c.run(1000.0 / 60);
            c.shot(String.format("%s/f%04d.png", dir, frame++));
        }
    }

    @Test
    public void record() {
        String out = System.getProperty("cap.frames");
        if (out == null) {
            return;
        }
        dir = out + "/reorder-edit";
        new File(dir).mkdirs();
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        c = new Capture(ReorderTest.W, ReorderTest.H, 32, 24, 2, System.getProperty("cap.scheme", "light"),
                env -> CodexGlass.page(env, src, "x:tabs"), makers);
        c.run(2500);
        double x = ReorderTest.W - 50;
        double y = 32 + 26;
        step(12);
        // Edit: the handles come in
        c.host.down(x, y);
        step(3);
        c.host.up(x, y);
        step(40);
        // drag the first row's handle down one row and let go
        double hx = ReorderTest.W - 43;
        double y0 = 239; // the first row (ReorderTestRows.firstRowY)
        c.host.down(hx, y0);
        step(10);
        for (int i = 1; i <= 24; i++) {
            c.host.move(hx, y0 + 70.0 * i / 24);
            step(1);
        }
        step(8);
        c.host.up(hx, y0 + 70);
        step(50);
        // Done: the handles go away
        c.host.down(x, y);
        step(3);
        c.host.up(x, y);
        step(50);
    }
}
