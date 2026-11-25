package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.demo.Demo;
import com.benitbuhner.glasse.demo.Photos;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/**
 * Every Liquid Glass sheet next to Glassé's own demo sheet (Photos › Info), for comparing the head
 * (title and close button): {@code ./gradlew :glass:test --tests '*SheetShotsTest*' -Dcap.out=<dir>}.
 */
public class SheetShotsTest {
    private static final double W = 393;
    private static final double H = 852;

    @Test
    public void shots() {
        String out = System.getProperty("cap.out");
        Assume.assumeTrue(out != null);
        for (String scheme : new String[] {"light", "dark"}) {
            demo(out, scheme);
            codex(out, scheme, "highlights", false);
            codex(out, scheme, "picker", false);
            codex(out, scheme, "reminder", false);
            codex(out, scheme, "more", true);
        }
    }

    private static void demo(String out, String scheme) {
        Map<String, ScreenMaker> makers = new LinkedHashMap<>(Demo.makers());
        Capture c = new Capture(W, H, 32, 24, 2, scheme, env -> Photos.make(env), makers);
        c.run(2500);
        c.tap(60, 300, 80); // a photo
        c.run(1500);
        c.tap(196, 786, 80); // Info
        c.run(1500);
        c.shot(out + "/sheet-demo-info-" + scheme + ".png");
    }

    private static void codex(String out, String scheme, String which, boolean moreTabs) {
        FakeSource src = new FakeSource();
        src.moreTabs = moreTabs;
        Capture c = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.dashboard(env, src),
                new LinkedHashMap<String, ScreenMaker>());
        c.run(2000);
        if ("more".equals(which)) {
            c.tap(MoreTabTest.MORE_X, MoreTabTest.BAR_Y, 80);
        } else if ("reminder".equals(which)) {
            src.showAmount(CodexGlass.DASHBOARD);
        } else {
            GlassModel.Dialog d = new GlassModel.Dialog();
            if ("highlights".equals(which)) {
                // Usage history › Customize
                d.title = "Highlights to show";
                d.items = new String[] {"Guide", "Window list", "Value estimates", "Insights"};
                d.checked = new boolean[] {true, true, false, true};
                d.buttons.add(new GlassModel.Dialog.Button("Done", GlassModel.Dialog.PROMINENT, null));
            } else {
                d.title = "Refresh every";
                d.items = new String[] {"5 minutes", "15 minutes", "30 minutes", "1 hour"};
                d.selected = 1;
            }
            src.dialog = d;
            src.dialogScreen = CodexGlass.DASHBOARD;
            src.changed();
        }
        c.run(1500);
        c.shot(out + "/sheet-codex-" + which + "-" + scheme + ".png");
    }
}
