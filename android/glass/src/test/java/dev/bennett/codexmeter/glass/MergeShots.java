package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Edit merging into Settings and splitting back out (the app drops Edit on the Priority page), in
 * the top bar and in the Duo column, every frame. Only with -Dcap.merge=dir (bar/ and rail/).
 */
public class MergeShots {
    private void run(String duo, String out) {
        CodexGlass.setDuoMode(duo);
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        Capture c = new Capture(412, 891, 40, 24, 2, "light", env -> CodexGlass.dashboard(env, src), makers);
        CodexGlass.attach(c.host);
        c.run(2500);
        int f = 0;
        for (int k = 0; k < 2; k++) {
            src.setNoEdit(k == 0);
            c.run(1);
            src.setNoEdit(k == 0);
            for (int i = 0; i < 50; i++) {
                c.run(1000.0 / 60);
                c.shot(String.format("%s/f%04d.png", out, f++));
            }
        }
        CodexGlass.setDuoMode(CodexGlass.DUO_WIDE);
        CodexGlass.shared = false;
    }

    @Test
    public void merge() {
        String out = System.getProperty("cap.merge");
        if (out == null) {
            return;
        }
        new java.io.File(out + "/bar").mkdirs();
        new java.io.File(out + "/rail").mkdirs();
        run(CodexGlass.DUO_OFF, out + "/bar");
        run(CodexGlass.DUO_ALWAYS, out + "/rail");
    }
}
