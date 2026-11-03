package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/** The pages ported from the One UI activities, with sample data: {@code -Dcap.out=/dir}. */
public class PagesShotsTest {
    private static final double W = 393;
    private static final double H = 852;

    private static void shoot(String out, String scheme, String start, String name, boolean sheet) {
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        Capture c = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.page(env, src, start), makers);
        c.run(2500);
        if (sheet) {
            src.showAmount(CodexGlass.SETTINGS);
            c.run(1500);
        }
        c.shot(out + "/page-" + name + "-" + scheme + ".png");
    }

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        Assume.assumeTrue(out != null);
        String scheme = System.getProperty("cap.scheme", "light");
        shoot(out, scheme, "x:history", "history", false);
        shoot(out, scheme, "x:tabs", "tabs", false);
        shoot(out, scheme, "x:widget", "widget", false);
        shoot(out, scheme, "x:widget", "reminder", true);
    }
}
