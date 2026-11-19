package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Debug: every 60 Hz frame of the New models list's touch and hold menu and its swipe to dismiss,
 * for before / after recordings. Only with {@code -Dcap.frames=<dir>}; each scenario gets its own
 * folder of PNGs, and {@code timing.txt} lists the CPU time of each frame (no screenshot in it).
 */
public class PriorityGestureFrames {
    private static final double W = 393;
    private static final double H = 852;
    /** the second New models row's centre (the card starts under Recent activity and Used up) */
    private static final double ROW_Y = 551;

    private Capture c;
    private String dir;
    private int frame;
    private final List<String> timing = new ArrayList<>();

    private void step(int n) {
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            c.run(1000.0 / 60);
            timing.add(String.format("%s %d %.2f", new File(dir).getName(), frame, (System.nanoTime() - t0) / 1e6));
            c.shot(String.format("%s/f%04d.png", dir, frame++));
        }
    }

    private FakeSource open(String out, String name) {
        dir = out + "/" + name;
        frame = 0;
        FakeSource src = new FakeSource();
        src.priority = true;
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        c = new Capture(W, H, 32, 24, 1, System.getProperty("cap.scheme", "dark"),
                env -> CodexGlass.dashboard(env, src), makers);
        c.run(2500);
        return src;
    }

    @Test
    public void record() throws IOException {
        String out = System.getProperty("cap.frames");
        if (out == null) {
            return;
        }
        // 1. touch and hold a row, let go, then tap outside the menu to close it
        open(out, "hold-close");
        step(6);
        c.host.down(W / 2, ROW_Y);
        step(45);
        c.host.up(W / 2, ROW_Y);
        step(50);
        c.host.down(W / 2, 120);
        step(4);
        c.host.up(W / 2, 120);
        step(60);

        // 2. touch and hold, slide onto Dismiss and let go there (press, drag, release)
        open(out, "hold-pick");
        step(6);
        c.host.down(W / 2, ROW_Y);
        step(45);
        double pick = Double.parseDouble(System.getProperty("cap.pickY", "626"));
        for (int i = 1; i <= 12; i++) {
            c.host.move(W / 2 - 60.0 * i / 12, ROW_Y + (pick - ROW_Y) * i / 12);
            step(1);
        }
        step(10);
        c.host.up(W / 2 - 60, pick);
        step(80);

        // 3. a swipe that opens the action, then a tap on it
        open(out, "swipe-open-tap");
        step(6);
        c.host.down(W - 40, ROW_Y);
        for (int i = 1; i <= 14; i++) {
            c.host.move(W - 40 - 130.0 * i / 14, ROW_Y);
            step(1);
        }
        c.host.up(W - 170, ROW_Y);
        step(40);
        c.host.down(W - 50, ROW_Y);
        step(4);
        c.host.up(W - 50, ROW_Y);
        step(80);

        // 4. one full swipe across the row at a natural speed
        open(out, "swipe-full");
        step(6);
        c.host.down(W - 30, ROW_Y);
        for (int i = 1; i <= 16; i++) {
            c.host.move(W - 30 - (W - 80) * i / 16.0, ROW_Y);
            step(1);
        }
        c.host.up(50, ROW_Y);
        step(80);

        Files.write(new File(out, "timing.txt").toPath(), timing, StandardCharsets.UTF_8);
    }
}
