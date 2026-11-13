package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Frames of the Duo layout's rail tabs moving, at 60 Hz, on a Galaxy Z Fold 8's inner screen:
 * a tap on another tab, a drag down across every tab and back, and a pull past the top end.
 * Only with {@code -Dcap.motion=<dir>} (frames f0000.png on; ffmpeg makes them a video).
 */
public class RailMotionShots {
    @Test
    public void railMotion() {
        String out = System.getProperty("cap.motion");
        if (out == null) {
            return;
        }
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.DASHBOARD, env -> CodexGlass.dashboard(env, src));
        double w = DuoLayoutTest.FOLD8_W;
        double h = DuoLayoutTest.FOLD8_H;
        Capture c = new Capture(w, h, 32, 24, 1.5, System.getProperty("cap.scheme", "light"),
                env -> CodexGlass.dashboard(env, src), makers);
        int n = Math.min(src.tabs().size(), CodexGlass.railTabsFit(h, 32, 24, 2));
        double top = h - 24 - 8 - (n * CodexGlass.RAIL_TAB + 8) + 4;
        double x = w - 42;
        StringBuilder s = new StringBuilder("open " + CodexGlass.DASHBOARD + "\nrec\nwait 20\n");
        // a tap on the third tab: the lens flies there and lands
        s.append("tap ").append(x).append(' ').append(top + CodexGlass.RAIL_TAB * 2.5).append("\nwait 50\n");
        // touch the selected tab, drag down over the last one, back up to the first, let go
        s.append("down ").append(x).append(' ').append(top + CodexGlass.RAIL_TAB * 2.5).append('\n');
        s.append("wait 10\n");
        s.append("move ").append(x).append(' ').append(top + CodexGlass.RAIL_TAB * (n - 0.5)).append(" 30\n");
        s.append("move ").append(x).append(' ').append(top + CodexGlass.RAIL_TAB * 0.5).append(" 60\n");
        s.append("wait 10\nup\nwait 50\n");
        // a pull past the top end rubber-bands, then settles on the first tab
        s.append("down ").append(x).append(' ').append(top + CodexGlass.RAIL_TAB * 0.5).append('\n');
        s.append("move ").append(x).append(' ').append(top - 80).append(" 20\n");
        s.append("wait 10\nup\nwait 50\n");
        c.scenario(s.toString(), out);
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
    }
}
