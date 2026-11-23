package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.File;
import java.util.LinkedHashMap;
import org.junit.Test;

/**
 * Debug: every 60 Hz frame of the bar switching between a provider tab (Edit, ⋯, Settings) and
 * Priority (Edit, Settings) and of the ⋯ menu opening and closing. Only with {@code -Dcap.frames=<dir>}.
 */
public class BarMorphFrames {
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
        dir = out + "/bar-morph";
        new File(dir).mkdirs();
        FakeSource src = new FakeSource();
        c = new Capture(393, 852, 32, 24, 1, System.getProperty("cap.scheme", "dark"),
                env -> CodexGlass.dashboard(env, src), new LinkedHashMap<String, ScreenMaker>());
        c.run(2500);
        step(15);
        for (int k = 0; k < 2; k++) {
            src.priority = true;
            src.changed();
            step(45);
            src.priority = false;
            src.changed();
            step(45);
        }
        c.host.down(ProviderMenuTest.MENU_X, ProviderMenuTest.BAR_Y);
        step(5);
        c.host.up(ProviderMenuTest.MENU_X, ProviderMenuTest.BAR_Y);
        step(60);
        c.host.down(196, 500);
        step(4);
        c.host.up(196, 500);
        step(50);
    }
}
