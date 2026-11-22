package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.core.GlassStyle;
import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.After;
import org.junit.Test;

/**
 * Liquid Glass's one look (iOS 27-inspired): its tint runs from ultra clear to fully tinted. With
 * {@code -Dcap.out=<dir>} it also renders the dashboard at the tint's ends.
 */
public class LookShotsTest {
    private static final double W = 393;
    private static final double H = 852;

    @After
    public void reset() {
        GlassLook.apply(new GlassLook.Options(GlassLook.DEFAULT_TINT));
    }

    @Test
    public void theLookSetsMaterialDefaults() {
        assertNotNull(GlassLook.style(new GlassLook.Options(GlassLook.DEFAULT_TINT)));
    }

    @Test
    public void tintRunsFromUltraClearToFullyTinted() {
        GlassStyle clear = GlassLook.style(new GlassLook.Options(0.0));
        GlassStyle tinted = GlassLook.style(new GlassLook.Options(1.0));
        assertEquals(GlassLook.CLEAREST_TINT_ALPHA, clear.tintAlpha, 1e-9);
        assertEquals(GlassLook.FULL_TINT_ALPHA, tinted.tintAlpha, 1e-9);
        assertTrue(tinted.blur > clear.blur);
    }

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        if (out == null) {
            return;
        }
        String scheme = System.getProperty("cap.scheme", "light");
        Object[][] looks = {
                {"default", new GlassLook.Options(GlassLook.DEFAULT_TINT)},
                {"clear", new GlassLook.Options(0.0)},
                {"tinted", new GlassLook.Options(1.0)},
        };
        for (Object[] look : looks) {
            GlassLook.apply((GlassLook.Options) look[1]);
            FakeSource src = new FakeSource();
            Capture c = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.dashboard(env, src),
                    new LinkedHashMap<String, ScreenMaker>());
            c.run(2500);
            // scrolled so content sits under the floating bars
            c.drag(W / 2, 600, W / 2, 250, 300);
            c.run(1500);
            c.shot(out + "/look-" + look[0] + "-" + scheme + ".png");
        }
    }
}
