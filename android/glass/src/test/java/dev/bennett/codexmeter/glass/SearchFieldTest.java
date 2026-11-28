package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.kit.KNode;
import com.benitbuhner.glasse.kit.KeyboardLift;
import com.benitbuhner.glasse.kit.KitScreen;
import com.benitbuhner.glasse.kit.GlassNodes;
import com.benitbuhner.glasse.port.GlassInteract;
import com.benitbuhner.glasse.ui.FieldText;
import com.benitbuhner.glasse.ui.Keyboard;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A tap on the Settings search field, the keyboard sliding in, then the X. The X splits out of the
 * field, and the split's wobble used to stretch the whole 322 dp field by a tenth of its width, so it
 * swung past the screen's left edge and back while it rose (the "search bar jiggle"); the press also
 * grew it and pulled it toward the finger. Every 60 Hz frame the field's drawn box must keep its
 * left edge near the page margin and never grow past its resting width.
 * With {@code -Dcap.frames=<dir>} each frame is saved as a PNG, with {@code field.txt}: the field's
 * drawn box (layout, morph, keyboard lift, press scale and finger follow) and where the system text
 * input sits, frame by frame.
 */
public class SearchFieldTest {
    static final double W = 412;
    static final double H = 891;

    /** a host keyboard like the Android one: a real text input placed over the field every frame */
    static final class NativeKeyboard implements Keyboard {
        Function0<double[]> rect;
        boolean focused;

        @Override public boolean getNative() { return true; }
        @Override public void focus(String v, Function1<? super String, Unit> on, Function0<Unit> blur) { focused = true; }
        @Override public void focus(String v, Function1<? super String, Unit> on, Function0<Unit> blur,
                Function0<double[]> r, FieldText t) { focused = true; rect = r; }
        @Override public void blur() { focused = false; rect = null; }
        @Override public void setValue(String v) { }
        double[] input() { return focused && rect != null ? rect.invoke() : null; }
    }

    static KNode find(KNode n, String id) {
        if (n.kind.equals("glass") && id.equals(n.props.s("id"))) {
            return n;
        }
        for (KNode c : n.children) {
            KNode f = find(c, id);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    Capture c;
    NativeKeyboard kb;
    String dir;
    int frame;
    boolean save;
    final List<String> rows = new ArrayList<>();
    final List<Double> lefts = new ArrayList<>();
    final List<Double> widths = new ArrayList<>();

    Capture open(String scheme) {
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        c = new Capture(W, H, 40, 24, 1, scheme, env -> CodexGlass.dashboard(env, src), makers);
        kb = new NativeKeyboard();
        c.host.env.setKeyboard(kb);
        KeyboardLift.setKeyboardHeight(0);
        c.run(1500);
        c.host.open(CodexGlass.SETTINGS, true);
        c.run(900);
        return c;
    }

    KNode field() {
        return find(((KitScreen) c.host.apps.get(CodexGlass.SETTINGS)).scene.root, "field");
    }

    /** the field's drawn box on screen: [cx, cy, w, h] */
    double[] drawn() {
        KNode n = field();
        double[] r = KeyboardLift.screenRect(n);
        GlassInteract g = GlassNodes.interactFor(n);
        double sx = 1;
        double sy = 1;
        double tx = 0;
        double ty = 0;
        if (g != null) {
            double[] m = g.getM();
            sx = m[0];
            sy = m[3];
            tx = g.getTx();
            ty = g.getTy();
        }
        // a group morph draws the member at its own (spring) size from the moved corner
        double w = n.ctl.mo != null ? n.ctl.mo.w : r[2];
        double h = n.ctl.mo != null ? n.ctl.mo.h : r[3];
        return new double[] {r[0] + w / 2 + tx, r[1] + h / 2 + ty, w * sx, h * sy};
    }

    void step(int n) {
        for (int i = 0; i < n; i++) {
            c.run(1000.0 / 60);
            double[] d = drawn();
            double[] in = kb.input();
            rows.add(String.format("%s %4d  centre %7.2f %7.2f  size %7.2f x %6.2f  input %s", new File(dir).getName(),
                    frame, d[0], d[1], d[2], d[3],
                    in == null ? "-" : String.format("%7.2f %7.2f %7.2f", in[0], in[1], in[2])));
            lefts.add(d[0] - d[2] / 2);
            widths.add(d[2]);
            if (save) {
                c.shot(String.format("%s/f%04d.png", dir, frame));
            }
            frame++;
        }
    }

    /** the system keyboard sliding in or out over about 280 ms, as Android's IME insets do */
    void keyboard(double from, double to) {
        int n = 17;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double e = 1 - Math.pow(1 - t, 3);
            KeyboardLift.setKeyboardHeight(from + (to - from) * e);
            step(1);
        }
    }

    /** tap the field (a finger that rolls a few points), the keyboard comes up, then the X closes it */
    void play() {
        open(System.getProperty("cap.scheme", "light"));
        frame = 0;
        double[] d0 = drawn();
        step(6);
        c.host.down(d0[0] - 60, d0[1]);
        step(2);
        c.host.move(d0[0] - 57, d0[1] + 2);
        step(3);
        c.host.up(d0[0] - 57, d0[1] + 2);
        step(4);
        keyboard(0, 310);
        step(50);
        double[] x = drawn();
        // the X beside the field (10 dp gap, 48 dp wide)
        c.host.down(x[0] + x[2] / 2 + 10 + 24, x[1]);
        step(4);
        c.host.up(x[0] + x[2] / 2 + 10 + 24, x[1]);
        step(2);
        keyboard(310, 0);
        step(50);
    }

    @Test
    public void theFieldDoesNotJiggle() throws IOException {
        String out = System.getProperty("cap.frames");
        save = out != null;
        dir = (out != null ? out : System.getProperty("java.io.tmpdir")) + "/search-field";
        play();
        if (out != null) {
            Files.write(new File(out, "field.txt").toPath(), rows, StandardCharsets.UTF_8);
        }
        double rest = widths.get(0);
        assertTrue("the X never split out of the field", widths.stream().mapToDouble(Double::doubleValue).min().getAsDouble() < rest - 40);
        for (int i = 0; i < lefts.size(); i++) {
            double l = lefts.get(i);
            assertTrue("frame " + i + ": the field's left edge swung to " + l + " dp (margin 16)\n" + String.join("\n", rows),
                    l > 11.5 && l < 20.5);
            assertTrue("frame " + i + ": the field grew to " + widths.get(i) + " dp (rests at " + rest + ")",
                    widths.get(i) < rest + 0.5);
        }
    }
}
