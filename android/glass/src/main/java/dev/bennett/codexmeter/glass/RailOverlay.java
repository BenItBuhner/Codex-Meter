package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.kit.Comp;
import com.benitbuhner.glasse.kit.KTheme;
import com.benitbuhner.glasse.kit.Kit;
import com.benitbuhner.glasse.kit.KitScreen;
import com.benitbuhner.glasse.kit.P;
import com.benitbuhner.glasse.kit.R;
import com.benitbuhner.glasse.ui.Host;
import com.benitbuhner.glasse.ui.RailButton;
import com.benitbuhner.glasse.ui.Screen;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jetbrains.skia.Canvas;

/**
 * The Duo column drawn once over the dashboard and Settings (Glassé's Host overlay), so it never
 * slides with a page. Its buttons are one glass group keyed by identity: when Settings comes up the
 * dashboard's gear morphs into Back (the same identity, its icon swapping) and Edit merges into it;
 * a Settings page's own buttons split out of Back and merge back into it. The tab bar belongs to
 * the dashboard: it slides down off the screen as Settings pushes in and comes back up with a back
 * swipe, following the finger.
 */
final class RailOverlay implements Screen {
    private static volatile boolean dirty = true;
    private final Host host;
    private final KitScreen kit;
    private double lastV = -1;
    private String lastTop;

    RailOverlay(Host host) {
        this.host = host;
        Comp layer = p -> layer(host);
        kit = Kit.makeKitScreen(host.env, layer, CodexGlass.ACCENT);
        kit.transparent = true;
    }

    /** A screen published new buttons or tabs: the column renders again on the next frame. */
    static void changed() {
        dirty = true;
    }

    private static com.benitbuhner.glasse.kit.El layer(Host host) {
        KTheme t = Kit.useTheme();
        double rail = CodexGlass.rail(t);
        if (rail <= 0) {
            return null;
        }
        CodexGlass.RailState dash = CodexGlass.dashboardRail;
        CodexGlass.RailState settings = CodexGlass.settingsRail;
        boolean inSettings = CodexGlass.SETTINGS.equals(host.topApp()) && settings != null;
        CodexGlass.RailState shown = inSettings ? settings : dash;
        List<RailButton> buttons = shown != null ? shown.buttons : Collections.emptyList();
        // how far Settings covers the dashboard: 0 on the dashboard, 1 with Settings on top; a back
        // swipe drags it down with the finger
        double v = host.app == null ? 0 : Math.max(0, Math.min(1, host.push.value));
        int tabs = dash != null && dash.tabs != null ? dash.tabs.size() : 0;
        double tabsH = tabs * CodexGlass.RAIL_TAB + 8;
        double shift = v * (tabsH + t.B + 24);
        // where the buttons start stays put from page to page (room for three)
        return Kit.Box(P.of("fill", true), CodexGlass.duoColumn(t, rail, Math.max(3, buttons.size()), tabs,
                P.of("buttons", buttons,
                        "tabs", tabs > 0 && shift < tabsH + t.B + 23 ? dash.tabs : null,
                        "value", dash != null ? dash.value : null,
                        "onChange", dash != null ? dash.onChange : null,
                        "tabsShift", shift)));
    }

    @Override
    public void draw(Canvas cv) {
        kit.draw(cv);
    }

    @Override
    public boolean step(double dt) {
        double v = host.push.value;
        String top = host.topApp();
        if (dirty || v != lastV || !Objects.equals(top, lastTop)) {
            dirty = false;
            lastV = v;
            lastTop = top;
            kit.rerender();
        }
        return kit.step(dt);
    }

    @Override
    public boolean hits(double x, double y) {
        return kit.hits(x, y);
    }

    @Override
    public void down(double x, double y) {
        kit.down(x, y);
    }

    @Override
    public void move(double x, double y) {
        kit.move(x, y);
    }

    @Override
    public void up(double x, double y, boolean cancel) {
        kit.up(x, y, cancel);
    }

    @Override
    public void onScheme() {
        kit.onScheme();
    }
}
