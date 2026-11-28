package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.core.Spring;
import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.kit.GlassNodes;
import com.benitbuhner.glasse.kit.Hit;
import com.benitbuhner.glasse.kit.KNode;
import com.benitbuhner.glasse.kit.KitScreen;
import com.benitbuhner.glasse.port.GlassInteract;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * A mouse, trackpad or stylus resting over Liquid Glass (a Fold on DeX, a tablet with a pen): as on
 * iPadOS the glass button under the pointer lights where the pointer is, without the press's growth,
 * and a list row shows a softer pressed colour. Android's host never passed hover on, so nothing
 * reacted until a click.
 */
public class PointerHoverTest {
    private Capture c;

    private KitScreen settings() {
        return (KitScreen) c.host.apps.get(CodexGlass.SETTINGS);
    }

    private KNode target(double x, double y, boolean glass) {
        for (Hit h : settings().scene.hitPath(x, y)) {
            if (glass ? h.n.kind.equals("glass") && h.n.props.b("interactive") || h.n.kind.equals("glass") && h.n.props.has("onPress")
                    : !h.n.kind.equals("glass") && h.n.props.has("onPress") && h.n.props.b("pressBg")) {
                return h.n;
            }
        }
        return null;
    }

    private void open() {
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        c = new Capture(412, 891, 40, 24, 1, "light", env -> CodexGlass.dashboard(env, src), makers);
        c.run(1500);
        c.host.open(CodexGlass.SETTINGS, true);
        c.run(900);
    }

    @Test
    public void hoverLightsTheSearchFieldWithoutGrowingIt() {
        open();
        double x = 120;
        double y = 891 - 24 - 14 - 24;
        KNode field = target(x, y, true);
        assertNotNull("no glass under the pointer at the search field", field);
        c.host.hover(x, y);
        c.run(400);
        GlassInteract g = GlassNodes.interactFor(field);
        assertNotNull(g);
        assertTrue("the pointer didn't light the glass: glow " + g.getGlow(), g.getGlow() > 0.3);
        assertEquals("hover grew the glass", 1.0, g.getM()[0], 0.002);
        c.host.hoverExit();
        c.run(600);
        assertEquals("the light stayed after the pointer left", 0.0, g.getGlow(), 0.01);
    }

    @Test
    public void hoverHighlightsARowAndATouchClearsIt() {
        open();
        KNode row = null;
        double y = 200;
        for (; y < 700 && row == null; y += 20) {
            row = target(200, y, false);
        }
        assertNotNull("no row with a pressed colour under the pointer", row);
        y -= 20;
        c.host.hover(200, y);
        c.run(300);
        Spring hov = (Spring) row.ctl.get("hov");
        assertNotNull("hover never reached the row", hov);
        assertTrue("row not lit: " + hov.value, hov.value > 0.9);
        // a touch starts: the hover goes
        c.host.down(200, y);
        c.run(300);
        assertTrue("row still lit by the pointer under a touch: " + hov.value, hov.value < 0.05);
        c.host.up(200, y, true);
    }
}
