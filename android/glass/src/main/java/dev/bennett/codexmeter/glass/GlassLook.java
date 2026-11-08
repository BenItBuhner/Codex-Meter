package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.core.GlassStyle;
import com.benitbuhner.glasse.core.GlassStyles;

/**
 * Which Liquid Glass look the screens draw: the iOS 26-inspired look (the design as it shipped) or
 * the iOS 27-inspired one. Both share every Glassé primitive and screen; only the glass material's
 * defaults and a few chrome rules differ. These are inspired designs drawn by Glassé on Android, not
 * Apple's renderer, and they do not claim pixel parity with either iOS release.
 *
 * <p>Accessibility comes last and wins over the look: Reduce Transparency turns glass into a nearly
 * opaque frosted fill, and Increase Contrast darkens the rims and raises the tint.
 */
public final class GlassLook {
    private GlassLook() {}

    public enum Generation {
        /** iOS 26-inspired: Glassé's own material, unchanged from the original Liquid Glass design. */
        IOS_26,
        /** iOS 27-inspired: softer diffusion, darker edges, brighter highlights, adjustable tint. */
        IOS_27
    }

    /** The look and the settings that shape it. Immutable. */
    public static final class Options {
        public final Generation generation;
        /** iOS 27 only: 0 is ultra clear, 1 is fully tinted. */
        public final double tint;
        public final boolean reduceTransparency;
        public final boolean increaseContrast;

        public Options(Generation generation, double tint, boolean reduceTransparency,
                boolean increaseContrast) {
            this.generation = generation == null ? Generation.IOS_26 : generation;
            this.tint = Double.isNaN(tint) ? DEFAULT_TINT : Math.max(0, Math.min(1, tint));
            this.reduceTransparency = reduceTransparency;
            this.increaseContrast = increaseContrast;
        }
    }

    /** Where the iOS 27 tint slider starts: a little tint, close to the iOS 26 look. */
    public static final double DEFAULT_TINT = 0.25;

    /** The tint's alpha at the slider's ends. */
    static final double CLEAREST_TINT_ALPHA = 0.02;
    static final double FULL_TINT_ALPHA = 0.82;

    private static volatile Options current = new Options(Generation.IOS_26, DEFAULT_TINT, false, false);

    public static Options current() {
        return current;
    }

    /** Makes {@code options} the look of every glass surface drawn from now on. */
    public static void apply(Options options) {
        current = options;
        GlassStyles.setDefaults(style(options));
    }

    /** True when the floating bars get a uniform backdrop once content scrolls under them. */
    public static boolean uniformToolbar() {
        Options o = current;
        return o.generation == Generation.IOS_27 || o.reduceTransparency;
    }

    /**
     * The material defaults for {@code o}, or null for Glassé's own defaults (iOS 26 with no
     * accessibility setting on, which is exactly the original design).
     */
    public static GlassStyle style(Options o) {
        GlassStyle s = null;
        if (o.generation == Generation.IOS_27) {
            double alpha = CLEAREST_TINT_ALPHA + (FULL_TINT_ALPHA - CLEAREST_TINT_ALPHA) * o.tint;
            s = GlassStyle.EMPTY
                    // more blur and saturation: busy backgrounds diffuse instead of showing through sharply
                    .blur(2.4 + 6.0 * o.tint)
                    .saturation(1.45)
                    // a brighter highlight and darker, heavier edges than iOS 26
                    .specular(1.35)
                    .shadow(1.15)
                    .bezel(18.0)
                    .tintAlpha(alpha)
                    .light(GlassStyle.EMPTY.tint("#ffffff"))
                    .dark(GlassStyle.EMPTY.tint("#1c1c1e"));
        }
        if (o.increaseContrast) {
            GlassStyle contrast = GlassStyle.EMPTY
                    .shadow(1.4)
                    .specular(0.8)
                    .tintAlpha(Math.max(0.45, s != null && s.tintAlpha != null ? s.tintAlpha : 0))
                    .light(GlassStyle.EMPTY.tint("#ffffff"))
                    .dark(GlassStyle.EMPTY.tint("#000000"));
            s = s == null ? contrast : s.then(contrast);
        }
        if (o.reduceTransparency) {
            GlassStyle solid = GlassStyle.EMPTY
                    .blur(14.0)
                    .aberration(0.0)
                    .magnification(0.0)
                    .tintAlpha(0.94)
                    .light(GlassStyle.EMPTY.tint("#f2f2f7"))
                    .dark(GlassStyle.EMPTY.tint("#1c1c1e"));
            s = s == null ? solid : s.then(solid);
        }
        return s;
    }
}
