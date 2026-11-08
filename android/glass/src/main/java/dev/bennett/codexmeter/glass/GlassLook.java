package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.core.GlassStyle;
import com.benitbuhner.glasse.core.GlassStyles;

/**
 * The Liquid Glass look the screens draw: iOS 27-inspired, with softer diffusion, darker edges,
 * brighter highlights and an adjustable tint, over Glassé's primitives. It is an inspired design
 * drawn by Glassé on Android, not Apple's renderer, and does not claim pixel parity with iOS.
 *
 * <p>Accessibility comes last and wins over the look: Reduce Transparency turns glass into a nearly
 * opaque frosted fill, and Increase Contrast darkens the rims and raises the tint.
 */
public final class GlassLook {
    private GlassLook() {}

    /** The look and the settings that shape it. Immutable. */
    public static final class Options {
        /** 0 is ultra clear, 1 is fully tinted. */
        public final double tint;
        public final boolean reduceTransparency;
        public final boolean increaseContrast;

        public Options(double tint, boolean reduceTransparency, boolean increaseContrast) {
            this.tint = Double.isNaN(tint) ? DEFAULT_TINT : Math.max(0, Math.min(1, tint));
            this.reduceTransparency = reduceTransparency;
            this.increaseContrast = increaseContrast;
        }
    }

    /** Where the tint slider starts: a little tint. */
    public static final double DEFAULT_TINT = 0.25;

    /** The tint's alpha at the slider's ends. */
    static final double CLEAREST_TINT_ALPHA = 0.02;
    static final double FULL_TINT_ALPHA = 0.82;

    private static volatile Options current = new Options(DEFAULT_TINT, false, false);

    public static Options current() {
        return current;
    }

    /** Makes {@code options} the look of every glass surface drawn from now on. */
    public static void apply(Options options) {
        current = options;
        GlassStyles.setDefaults(style(options));
    }

    /** The material defaults for {@code o}: the look, then the accessibility switches. */
    public static GlassStyle style(Options o) {
        GlassStyle s;
        {
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
                    .tintAlpha(Math.max(0.45, s.tintAlpha != null ? s.tintAlpha : 0))
                    .light(GlassStyle.EMPTY.tint("#ffffff"))
                    .dark(GlassStyle.EMPTY.tint("#000000"));
            s = s.then(contrast);
        }
        if (o.reduceTransparency) {
            GlassStyle solid = GlassStyle.EMPTY
                    .blur(14.0)
                    .aberration(0.0)
                    .magnification(0.0)
                    .tintAlpha(0.94)
                    .light(GlassStyle.EMPTY.tint("#f2f2f7"))
                    .dark(GlassStyle.EMPTY.tint("#1c1c1e"));
            s = s.then(solid);
        }
        return s;
    }
}
