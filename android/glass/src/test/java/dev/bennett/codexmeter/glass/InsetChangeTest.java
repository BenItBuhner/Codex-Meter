package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The nav bar follows the status bar inset, and its small title keeps clear of the buttons. */
public class InsetChangeTest {
    /** A foldable window whose status bar inset arrives after the first frame. */
    @Test
    public void navButtonsMoveBelowAStatusBarThatArrivesLate() {
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.DASHBOARD, env -> CodexGlass.dashboard(env, src));
        Capture c = new Capture(707, 823, 0, 0, 1, "light", env -> CodexGlass.dashboard(env, src), makers);
        c.host.open(CodexGlass.DASHBOARD, true);
        c.run(120);
        c.host.resize(707, 823, 52, 24);
        c.run(2500);
        com.benitbuhner.glasse.kit.KitScreen kit =
                (com.benitbuhner.glasse.kit.KitScreen) c.host.apps.get(CodexGlass.DASHBOARD);
        Matcher m = Pattern.compile("icon 6\\d\\d(?:\\.\\d+)?,([-0-9.]+),22,22").matcher(c.dump(kit.scene.root));
        int buttons = 0;
        while (m.find()) {
            buttons++;
            assertTrue("nav button at " + m.group(1), Double.parseDouble(m.group(1)) >= 52);
        }
        assertTrue("no nav buttons found", buttons >= 2);
    }

    @Test
    public void smallTitleHidesWhenItWouldRunUnderTheButtons() {
        double editAndSave = CodexGlass.buttonWidth("Edit") + CodexGlass.buttonWidth("Save");
        assertFalse(CodexGlass.navTitleFits("Customize widget", editAndSave, true, 411));
        assertTrue(CodexGlass.navTitleFits("Customize widget", editAndSave, true, 707));
        assertTrue(CodexGlass.navTitleFits("Appearance", 0, true, 360));
        assertTrue(CodexGlass.navTitleFits("Dashboard tabs", CodexGlass.buttonWidth("Edit"), true, 411));
    }
}
