package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.core.GlassStyle;
import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import org.junit.After;
import org.junit.Test;

/**
 * The two Liquid Glass looks share every screen. The iOS 26-inspired look keeps Glassé's own
 * material (no defaults), so it draws exactly as before; iOS 27 and the accessibility switches set
 * material defaults. With {@code -Dcap.out=<dir>} it also renders the dashboard in each look.
 */
public class LookShotsTest {
    private static final double W = 393;
    private static final double H = 852;

    @After
    public void reset() {
        GlassLook.apply(new GlassLook.Options(GlassLook.Generation.IOS_26, GlassLook.DEFAULT_TINT, false, false));
    }

    @Test
    public void ios26KeepsGlasseDefaults() {
        assertNull(GlassLook.style(new GlassLook.Options(GlassLook.Generation.IOS_26, 1.0, false, false)));
    }

    @Test
    public void ios27TintRunsFromUltraClearToFullyTinted() {
        GlassStyle clear = GlassLook.style(new GlassLook.Options(GlassLook.Generation.IOS_27, 0.0, false, false));
        GlassStyle tinted = GlassLook.style(new GlassLook.Options(GlassLook.Generation.IOS_27, 1.0, false, false));
        assertEquals(GlassLook.CLEAREST_TINT_ALPHA, clear.tintAlpha, 1e-9);
        assertEquals(GlassLook.FULL_TINT_ALPHA, tinted.tintAlpha, 1e-9);
        assertTrue(tinted.blur > clear.blur);
    }

    @Test
    public void accessibilityWinsOverTheLook() {
        GlassStyle reduced = GlassLook.style(new GlassLook.Options(GlassLook.Generation.IOS_27, 0.0, true, false));
        assertTrue(reduced.tintAlpha > 0.9);
        assertEquals(0.0, reduced.aberration, 1e-9);
        GlassStyle contrast = GlassLook.style(new GlassLook.Options(GlassLook.Generation.IOS_26, 0.0, false, true));
        assertNotNull(contrast);
        assertTrue(contrast.tintAlpha >= 0.45);
    }

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        if (out == null) {
            return;
        }
        String scheme = System.getProperty("cap.scheme", "light");
        Object[][] looks = {
                {"ios26", new GlassLook.Options(GlassLook.Generation.IOS_26, GlassLook.DEFAULT_TINT, false, false)},
                {"ios27", new GlassLook.Options(GlassLook.Generation.IOS_27, GlassLook.DEFAULT_TINT, false, false)},
                {"ios27-clear", new GlassLook.Options(GlassLook.Generation.IOS_27, 0.0, false, false)},
                {"ios27-tinted", new GlassLook.Options(GlassLook.Generation.IOS_27, 1.0, false, false)},
                {"reduce-transparency", new GlassLook.Options(GlassLook.Generation.IOS_27, GlassLook.DEFAULT_TINT, true, false)},
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
