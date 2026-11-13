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
import org.jetbrains.skia.Picture;

/**
 * The Duo column drawn once over the dashboard and Settings (Glassé's Host overlay), so it never
 * slides with a page, like the iPhone Duo's vertical bar. Its buttons are one glass group keyed by
 * identity, morphing in place with Glassé's toolbar morph; its glass samples the pages under it
 * (Screen.under), so it takes the same fused path as glass over a page. The tab bar belongs to the
 * dashboard: it slides down off the screen as Settings pushes in and comes back up with a back
 * swipe, following the finger.
 */
final class RailOverlay implements Screen {
    private static volatile boolean dirty = true;
    /** The column's width when it last rendered. */
    private static volatile double railW;
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
        railW = rail;
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
    public void under(Picture pic) {
        // the column's strip, with room for a morph's swell: the part of the pages its glass samples
        double rail = railW;
        double m = 24;
        kit.glassArea = new double[] {host.env.getW() - rail - m, 0, rail + m, host.env.getH()};
        kit.under(pic);
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
