package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.core.DefaultGlass;
import com.benitbuhner.glasse.outside.OutsideGlass;
import com.benitbuhner.glasse.port.Sk;
import com.benitbuhner.glasse.port.Smooth;
import com.benitbuhner.glasse.ui.Widgets;
import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.Surface;

/**
 * The Liquid Glass panel behind a home-screen widget, drawn with Glassé's glass for widgets
 * ({@link OutsideGlass}): bevel rim, blur and tint, the same glass as the app's screens.
 *
 * <p>A launcher gives a widget no pixels from behind it, and Codex Meter does not ask for access to
 * the wallpaper, so a home-screen widget has no backdrop to refract or blur: it gets a translucent
 * frosted fill, as strong as the widget's background setting, under the glass's rim and
 * highlights, and the wallpaper shows through it. Previews inside the app pass their own backdrop
 * and so refract and blur it. Pure Skia, so the JVM tests can render it.
 */
public final class GlassWidgetArt {
    private GlassWidgetArt() {}

    /**
     * The transparent border around the glass (dp). It holds only a thin contact shadow: the
     * launcher clips a widget to its own bounds, so Glassé's floating drop shadow (26 dp of blur)
     * was cut off at the widget's edge and showed as a dark band under it.
     */
    public static final double MARGIN = 4;
    /** The contact shadow: offset and blur (dp), small enough to fade out inside {@link #MARGIN}. */
    static final double SHADOW_DY = 1;
    static final double SHADOW_BLUR = 2.5;
    /** Corner radius of the glass (dp), close to Android 12+'s widget corners. */
    public static final double RADIUS = 24;

    /**
     * The glass's tint over a backdrop for the widget's background strength (the widget setting's
     * 56, 88 or 100; 0 means no background and draws no glass).
     */
    public static double tint(int opacity) {
        if (opacity <= 0) {
            return 0;
        }
        return Math.min(0.7, 0.25 + 0.45 * (opacity - 56) / 44.0);
    }

    /**
     * The frosted fill's alpha with no backdrop (a home-screen widget): stronger than the tint,
     * since nothing is blurred behind it, so text stays readable over a busy wallpaper.
     */
    public static double fill(int opacity, boolean dark) {
        if (opacity <= 0) {
            return 0;
        }
        double base = 0.36 + 0.46 * (opacity - 56) / 44.0;
        return Math.min(0.86, dark ? base + 0.06 : base);
    }

    /**
     * Renders the glass for a widget of {@code wPx} x {@code hPx} device pixels at {@code density}.
     * {@code backdrop} (null for a home-screen widget) holds what is behind the widget, with the
     * widget's top-left at ({@code bx}, {@code by}) in it. The result is the widget's size: glass
     * inset by {@link #MARGIN} with a thin contact shadow, transparent elsewhere.
     */
    public static Image render(Image backdrop, double bx, double by, int wPx, int hPx,
            double density, boolean dark, int opacity) {
        double w = Math.max(8, wPx / density - 2 * MARGIN);
        double h = Math.max(8, hPx / density - 2 * MARGIN);
        double r = Math.min(RADIUS, Math.min(w, h) / 2);
        OutsideGlass.Spec spec = new OutsideGlass.Spec(w, h, r, density, dark ? "dark" : "light",
                MARGIN, false, 5.0, DefaultGlass.bezel, DefaultGlass.thickness, tint(opacity));
        Image glass = OutsideGlass.render(backdrop, bx + MARGIN * density, by + MARGIN * density, spec);
        Surface surface = Surface.Companion.makeRasterN32Premul(glass.getWidth(), glass.getHeight());
        Canvas c = surface.getCanvas();
        c.save();
        c.scale((float) density, (float) density);
        Widgets.INSTANCE.shadow(c, MARGIN, MARGIN, w, h, r, 0, SHADOW_DY, SHADOW_BLUR, 0,
                Sk.col(0, 0, 0, dark ? 0.30 : 0.14));
        if (backdrop == null) {
            // No backdrop: a frosted fill in the glass's outline, then the glass (rim, highlights) over it.
            Paint p = dark ? Sk.paint(Sk.col(28, 28, 30, fill(opacity, dark)))
                    : Sk.paint(Sk.col(250, 250, 252, fill(opacity, dark)));
            Smooth.drawRRect(c, Sk.rrect(MARGIN, MARGIN, w, h, r), p);
        }
        c.restore();
        c.drawImage(glass, 0, 0);
        return surface.makeImageSnapshot();
    }
}
