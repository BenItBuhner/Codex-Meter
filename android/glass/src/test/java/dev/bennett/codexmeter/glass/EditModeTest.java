package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The bar's Edit puts the tab itself into Edit mode (Photos' Select → ✓ morph): every section of
 * the tab in one list, a check to show or hide each and a handle to move it, and Done back to the
 * page. Screenshots with {@code -Dcap.out=<dir>}; every 60 Hz frame of the walk with
 * {@code -Dcap.frames=<dir>}.
 */
public class EditModeTest {
    static final double W = 393;
    static final double H = 852;
    /** Edit, the capsule left of Settings in the bar */
    static final double EX = Double.parseDouble(System.getProperty("cap.editX", "296"));
    static final double EY = 32 + 26;
    /** the first section row in Edit mode, and the rows' pitch */
    static final double ROW0 = Double.parseDouble(System.getProperty("cap.row0", "232"));
    static final double PITCH = CodexGlass.EDIT_ROW_H;

    private Capture c;
    private String frames;
    private int frame;

    private FakeSource open(boolean priority, String name) {
        String root = System.getProperty("cap.frames");
        frames = root == null ? null : root + "/" + name;
        FakeSource src = new FakeSource();
        src.priority = priority;
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        c = new Capture(W, H, 32, 24, frames != null ? 1 : 2, System.getProperty("cap.scheme", "light"),
                env -> CodexGlass.dashboard(env, src), makers);
        c.run(2500);
        if (frames != null) {
            new File(frames).mkdirs();
        }
        frame = 0;
        return src;
    }

    /** Runs {@code ms}, a frame at a time when recording. */
    private void run(double ms) {
        if (frames == null) {
            c.run(ms);
            return;
        }
        for (double t = 0; t < ms; t += 1000.0 / 60) {
            c.run(1000.0 / 60);
            c.shot(String.format("%s/f%04d.png", frames, frame++));
        }
    }

    private void tap(double x, double y) {
        c.host.down(x, y);
        run(80);
        c.host.up(x, y);
    }

    private void shot(String name) {
        String out = System.getProperty("cap.out");
        if (out != null) {
            c.shot(out + "/" + System.getProperty("cap.scheme", "light") + "-" + name + ".png");
        }
    }

    @Test
    public void priorityEditShowsHidesAndComesBack() {
        FakeSource src = open(true, "priority");
        run(300);
        shot("01-priority");
        tap(EX, EY);
        run(900);
        shot("02-priority-edit");
        // the first row (Outages and slowdowns) off, then Used up (the fourth) off
        tap(W / 2, ROW0);
        run(500);
        assertTrue(src.hidden.contains("Outages and slowdowns"));
        tap(W / 2, ROW0 + 3 * PITCH);
        run(600);
        shot("03-priority-edit-hidden");
        assertTrue(src.hidden.contains("Used up"));
        // and both back on: a hidden section can always be switched on again
        tap(W / 2, ROW0 + 3 * PITCH);
        run(500);
        assertFalse(src.hidden.contains("Used up"));
        tap(W / 2, ROW0 + 3 * PITCH);
        run(500);
        assertTrue(src.hidden.contains("Used up"));
        // Done: back to the page, without Used up
        tap(W - 16 - 22, EY);
        run(900);
        shot("04-priority-done");
        tap(EX, EY);
        run(900);
        tap(W / 2, ROW0);
        run(300);
        tap(W / 2, ROW0 + 3 * PITCH);
        run(500);
        assertTrue(src.hidden.isEmpty());
        tap(W - 16 - 22, EY);
        run(900);
    }

    @Test
    public void priorityEditReordersByHandle() {
        FakeSource src = open(true, "reorder");
        tap(EX, EY);
        run(900);
        // New models (the last row) up to the top by its handle
        double gx = W - 16 - 22 - 4;
        double from = ROW0 + 6 * PITCH;
        c.host.down(gx, from);
        run(120);
        for (int i = 1; i <= 30; i++) {
            c.host.move(gx, from - (6 * PITCH + 10) * i / 30.0);
            run(1000.0 / 60);
        }
        run(200);
        shot("05-priority-edit-dragging");
        c.host.up(gx, ROW0 - 10);
        run(700);
        shot("06-priority-edit-moved");
        assertEquals("New models", src.priorityOrder.get(0));
        tap(W - 16 - 22, EY);
        run(900);
        shot("07-priority-moved-done");
    }

    @Test
    public void providerEditIsTheSameMode() {
        open(false, "provider");
        shot("08-provider");
        tap(EX, EY);
        run(900);
        shot("09-provider-edit");
    }
}
