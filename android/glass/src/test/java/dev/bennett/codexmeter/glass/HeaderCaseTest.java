package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.kit.KitScreen;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Section headers keep the case they are written in: Settings → Appearance reads "Theme" and
 * "Design", never "THEME" (iOS 26 doesn't upper-case them, and neither do we). Walks the
 * settings root and Appearance and fails on any drawn text that was turned into capitals.
 * With {@code -Dcap.out=<dir>} it also saves both pages as PNGs.
 */
public class HeaderCaseTest {
    private static final double W = 393;
    private static final double H = 852;
    private static final Pattern TEXT = Pattern.compile("text [-0-9.]+,[-0-9.]+,[-0-9.]+,[-0-9.]+ \"(.*)\"");

    @Test
    public void settingsHeadersKeepTheirCase() {
        String out = System.getProperty("cap.out");
        String scheme = System.getProperty("cap.scheme", "light");
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        makers.put(CodexGlass.SETTINGS, env -> CodexGlass.settings(env, src));
        Capture c = new Capture(W, H, 32, 24, 2, scheme, env -> CodexGlass.dashboard(env, src), makers);
        c.run(1500);
        c.host.open(CodexGlass.SETTINGS, true);
        c.run(2000);
        List<String> root = texts(c);
        if (out != null) {
            c.shot(out + "/header-case-settings-" + scheme + ".png");
        }
        c.tap(W / 2, ShotsTest.rowY(c, "Appearance"), 80);
        c.run(1500);
        List<String> appearance = texts(c);
        if (out != null) {
            c.shot(out + "/header-case-appearance-" + scheme + ".png");
        }

        assertTrue("Appearance shows its Theme header as written: " + appearance, appearance.contains("Theme"));
        assertTrue("Appearance shows its Design header as written: " + appearance, appearance.contains("Design"));
        List<String> shouting = new ArrayList<>();
        for (List<String> page : List.of(root, appearance)) {
            for (String s : page) {
                if (forcedCaps(s)) {
                    shouting.add(s);
                }
            }
        }
        assertTrue("text drawn in forced capitals: " + shouting, shouting.isEmpty());
    }

    /** A word of four or more letters written entirely in capitals ("THEME", "LIQUID GLASS"). */
    static boolean forcedCaps(String s) {
        return s.matches(".*\\b\\p{Lu}{4,}\\b.*") && s.equals(s.toUpperCase(java.util.Locale.ROOT));
    }

    private static List<String> texts(Capture c) {
        KitScreen kit = (KitScreen) c.host.apps.get(c.host.app);
        kit.scene.layout();
        List<String> out = new ArrayList<>();
        for (String line : c.dump(kit.scene.root).split("\n")) {
            Matcher m = TEXT.matcher(line.trim());
            if (m.matches() && !m.group(1).isEmpty()) {
                out.add(m.group(1));
            }
        }
        return out;
    }
}
