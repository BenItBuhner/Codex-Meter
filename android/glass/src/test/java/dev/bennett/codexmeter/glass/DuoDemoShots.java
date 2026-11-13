package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Every 60 Hz frame of the Duo column at work: switching tabs (tap and drag), opening Settings from
 * the column's gear, About's buttons splitting out of Back and merging back, Dashboard tabs' Edit,
 * closing Settings with Back, the app's own back swipe (scrubbed back and forth, then let go) and
 * the system's predictive back (cancelled once, then committed). Only with
 * {@code -Dcap.demo=<dir>}; cap.w, cap.h, cap.duo (always, wide), cap.scheme and cap.dpr pick the
 * screen. ffmpeg makes the frames a video.
 */
public class DuoDemoShots {
    private Capture c;
    private String out;
    private int frame;

    private void step(int frames) {
        for (int i = 0; i < frames; i++) {
            c.run(1000.0 / 60);
            c.shot(String.format("%s/f%04d.png", out, frame++));
        }
    }

    private void tap(double x, double y) {
        c.host.down(x, y);
        step(5);
        c.host.up(x, y);
    }

    private void drag(double x0, double y0, double x1, double y1, int frames) {
        for (int i = 1; i <= frames; i++) {
            double k = (double) i / frames;
            c.host.move(x0 + (x1 - x0) * k, y0 + (y1 - y0) * k);
            step(1);
        }
    }

    @Test
    public void duoDemo() {
        out = System.getProperty("cap.demo");
        if (out == null) {
            return;
        }
        double w = Double.parseDouble(System.getProperty("cap.w", "412"));
        double h = Double.parseDouble(System.getProperty("cap.h", "891"));
        double top = 40;
        double bottom = 24;
        CodexGlass.setDuoMode(System.getProperty("cap.duo", CodexGlass.DUO_ALWAYS));
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        c = new Capture(w, h, top, bottom, Double.parseDouble(System.getProperty("cap.dpr", "2")),
                System.getProperty("cap.scheme", "light"), env -> CodexGlass.dashboard(env, src), makers);
        CodexGlass.attach(c.host);
        src.onSettings = () -> c.host.open(CodexGlass.SETTINGS, false);
        c.run(2500);

        double x = w - CodexGlass.railWidth(w, h) / 2;
        int tabs = Math.min(src.tabs().size(), CodexGlass.railTabsFit(h, top, bottom, 3));
        double buttons = CodexGlass.railTop(h, top, bottom, 3, tabs);
        double tab0 = h - bottom - 8 - (tabs * CodexGlass.RAIL_TAB + 8) + 4 + CodexGlass.RAIL_TAB / 2;

        step(20);
        // tabs: a tap on the third, then a drag back up to the first
        tap(x, tab0 + 2 * CodexGlass.RAIL_TAB);
        step(45);
        c.host.down(x, tab0 + 2 * CodexGlass.RAIL_TAB);
        step(6);
        drag(x, tab0 + 2 * CodexGlass.RAIL_TAB, x, tab0, 40);
        c.host.up(x, tab0);
        step(45);
        // the gear (the second button, under Edit) opens Settings, which pushes in with its own column
        tap(x, buttons + 22 + 54);
        step(70);
        // About: GitHub and App info split out of Back, then merge back into it
        src.navigate(new GlassSource.Nav(false, "x:about"));
        step(70);
        tap(x, buttons + 22);
        step(70);
        // Dashboard tabs: Edit splits out of Back
        src.navigate(new GlassSource.Nav(false, "x:tabs"));
        step(60);
        tap(x, buttons + 22);
        step(60);
        // Back on Settings' first page closes it
        tap(x, buttons + 22);
        step(80);
        // the app's back swipe: open Settings, drag from the left edge, back and forth, let go
        tap(x, buttons + 22 + 54);
        step(70);
        c.host.down(12, h / 2);
        drag(12, h / 2, w * 0.45, h / 2, 40);
        step(10);
        drag(w * 0.45, h / 2, w * 0.2, h / 2, 20);
        drag(w * 0.2, h / 2, w * 0.6, h / 2, 16);
        c.host.up(w * 0.6, h / 2);
        step(80);
        // the system's predictive back: once cancelled, then committed
        tap(x, buttons + 22 + 54);
        step(70);
        c.host.backStart();
        for (int i = 1; i <= 24; i++) {
            c.host.backProgress(i * 4.0);
            step(1);
        }
        c.host.backCancel(0.0);
        step(40);
        c.host.backStart();
        for (int i = 1; i <= 30; i++) {
            c.host.backProgress(i * 5.0);
            step(1);
        }
        c.host.backCommit(true, -0.5);
        step(80);

        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
        CodexGlass.shared = false;
    }
}
