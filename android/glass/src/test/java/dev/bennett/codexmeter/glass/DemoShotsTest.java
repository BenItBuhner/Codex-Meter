package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.demo.Demo;
import com.benitbuhner.glasse.demo.Music;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/**
 * Glassé's own demo screens at the same size and insets as {@link ShotsTest}, for side-by-side
 * comparison: {@code ./gradlew :glass:test -Dcap.out=/some/dir [-Dcap.scheme=dark]}.
 */
public class DemoShotsTest {
    private static final double W = 393;
    private static final double H = 852;
    private static final double TOP = 32;
    private static final double BOTTOM = 24;

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        Assume.assumeTrue(out != null);
        String scheme = System.getProperty("cap.scheme", "light");
        double dpr = Double.parseDouble(System.getProperty("cap.dpr", "2"));
        Map<String, ScreenMaker> makers = new LinkedHashMap<>(Demo.makers());
        Capture c = new Capture(W, H, TOP, BOTTOM, dpr, scheme, env -> Music.make(env), makers);
        c.run(2500);
        c.shot(out + "/demo-music-" + scheme + ".png");
        c.drag(W / 2, 600, W / 2, 250, 300);
        c.run(1500);
        c.shot(out + "/demo-music-scrolled-" + scheme + ".png");
        c.host.open("settings", true);
        c.run(2500);
        c.shot(out + "/demo-settings-" + scheme + ".png");
    }
}
