package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** Dashboard tabs reorder with Glassé's ReorderList: Edit shows the handles, a handle drag moves the row. */
public class ReorderTest {
    static final double W = 393;
    static final double H = 852;

    static Capture open(FakeSource src) {
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        Capture c = new Capture(W, H, 32, 24, 2, System.getProperty("cap.scheme", "light"),
                env -> CodexGlass.page(env, src, "x:tabs"), makers);
        c.run(2500);
        return c;
    }

    @Test
    public void editThenDragAHandle() {
        FakeSource src = new FakeSource();
        Capture c = open(src);
        String out = System.getProperty("cap.out");
        c.tap(W - 50, 32 + 26, 80);
        c.run(1200);
        if (out != null) {
            c.shot(out + "/page-tabs-editing.png");
        }
        // the first row's handle, dragged down past two rows
        c.drag(W - 43, ReorderTestRows.firstRowY, W - 43, ReorderTestRows.firstRowY + ReorderTestRows.step, 600.0);
        c.run(1200);
        if (out != null) {
            c.shot(out + "/page-tabs-dragged.png");
        }
        assertEquals(Arrays.asList("Claude Code", "Cursor", "Codex", "Copilot"), src.tabOrderView());
    }

    @Test
    public void touchAndHoldDragsWithoutEdit() {
        FakeSource src = new FakeSource();
        Capture c = open(src);
        double x = 150;
        double y = ReorderTestRows.firstRowY;
        c.host.down(x, y);
        c.run(700);
        for (int i = 1; i <= 30; i++) {
            c.host.move(x, y + ReorderTestRows.step * i / 30.0);
            c.run(1000.0 / 60);
        }
        c.host.up(x, y + ReorderTestRows.step);
        c.run(1200);
        assertEquals(Arrays.asList("Claude Code", "Cursor", "Codex", "Copilot"), src.tabOrderView());
    }
}

final class ReorderTestRows {
    static double firstRowY = 239;
    static double step = 160;
}
