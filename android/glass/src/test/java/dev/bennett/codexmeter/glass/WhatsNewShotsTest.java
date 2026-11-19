package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

/**
 * The Liquid Glass What's new page: Continue, pinned at the bottom, closes it. With
 * {@code -Dcap.out=/dir} it also saves the page, light and dark.
 */
public class WhatsNewShotsTest {
    private static final double W = 393;
    private static final double H = 852;
    private static final double T = 54;
    private static final double B = 34;

    private static GlassModel.Intro intro(AtomicInteger done) throws IOException {
        GlassModel.Intro intro = new GlassModel.Intro();
        intro.title = "What's new in Codex Meter 3.0";
        Path logo = Paths.get("../../ios/CodexMeter/Assets.xcassets/AppLogo.imageset/AppLogo.png");
        intro.logo = Files.exists(logo) ? Files.readAllBytes(logo) : null;
        String[][] features = {
            {"grid", "Every AI service in one place", "Claude Code, Cursor, Copilot, Grok, OpenRouter and more join Codex, each with its own tab."},
            {"flag", "Priority", "One tab for what needs you now: limits to watch, used up, coming up, outages and new models."},
            {"paintbrush", "Four designs", "Liquid Glass, One UI 9, Material 3 Expressive and One UI 8.5, in Settings › Appearance."},
            {"refresh", "Saved resets", "Claude Code resets and Codex reset credits, used for you before they expire or when you run low."},
            {"bolt", "Live usage for any service", "The Now Bar or a live notification, starting on its own while an AI app is open."},
            {"bell", "Smarter alerts", "Hear when usage is back, when a limit refills early and when new models land."},
            {"creditcard", "Estimated value", "What your plan buys at API prices, from dated, sourced figures."},
            {"waveform", "Outages and model speed", "Official incidents on the dashboard, and each model's live speed and uptime."},
            {"widget", "Widgets for every service", "Home and lock screen widgets for any provider, in every design."},
            {"sidebar", "Made for foldables", "The Duo layout keeps Back, buttons and tabs in a column beside the page."},
            {"desktop", "On your computer too", "Codex Meter for Windows, Mac and Linux, with your limits in the tray or menu bar."},
            {"gear", "Simpler settings", "The same groups on every device, with settings export and import."},
        };
        for (String[] f : features) {
            GlassModel.Row row = new GlassModel.Row(GlassModel.Row.Kind.INFO, f[0]);
            row.icon = f[0];
            row.title = f[1];
            row.summary = f[2];
            intro.features.add(row);
        }
        intro.onDone = done::incrementAndGet;
        return intro;
    }

    private static Capture capture(String scheme, GlassModel.Intro intro) {
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        return new Capture(W, H, T, B, 2, scheme, env -> CodexGlass.whatsNew(env, intro), makers);
    }

    @Test
    public void continueClosesThePage() throws IOException {
        AtomicInteger done = new AtomicInteger();
        Capture c = capture("light", intro(done));
        c.run(1500);
        // Continue sits at the bottom, centred, above the home indicator.
        c.tap(W / 2, H - B - 16 - CodexGlass.INTRO_BUTTON_H / 2, 80);
        c.run(600);
        assertEquals(1, done.get());
    }

    @Test
    public void shots() throws IOException {
        String out = System.getProperty("cap.out");
        Assume.assumeTrue(out != null);
        for (String scheme : new String[] {"light", "dark"}) {
            Capture c = capture(scheme, intro(new AtomicInteger()));
            c.run(2000);
            c.shot(out + "/whats-new-liquid-glass-" + scheme + ".png");
        }
    }
}
