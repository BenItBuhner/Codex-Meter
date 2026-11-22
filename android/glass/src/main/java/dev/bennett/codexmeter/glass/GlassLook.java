package dev.bennett.codexmeter.glass;

import com.benitbuhner.glasse.core.GlassStyle;
import com.benitbuhner.glasse.core.GlassStyles;

/**
 * The Liquid Glass look the screens draw: iOS 27-inspired, with softer diffusion, darker edges,
 * brighter highlights and an adjustable tint, over Glassé's primitives. It is an inspired design
 * drawn by Glassé on Android, not Apple's renderer, and does not claim pixel parity with iOS.
 */
public final class GlassLook {
    private GlassLook() {}

    /** The look and the settings that shape it. Immutable. */
    public static final class Options {
        /** 0 is ultra clear, 1 is fully tinted. */
        public final double tint;

        public Options(double tint) {
            this.tint = Double.isNaN(tint) ? DEFAULT_TINT : Math.max(0, Math.min(1, tint));
        }
    }

    /** Where the tint slider starts: a little tint. */
    public static final double DEFAULT_TINT = 0.25;

    /** The tint's alpha at the slider's ends. */
    static final double CLEAREST_TINT_ALPHA = 0.02;
    static final double FULL_TINT_ALPHA = 0.82;

    private static volatile Options current = new Options(DEFAULT_TINT);

    public static Options current() {
        return current;
    }

    /** Makes {@code options} the look of every glass surface drawn from now on. */
    public static void apply(Options options) {
        current = options;
        GlassStyles.setDefaults(style(options));
    }

    /** The material defaults for {@code o}. */
    public static GlassStyle style(Options o) {
        double alpha = CLEAREST_TINT_ALPHA + (FULL_TINT_ALPHA - CLEAREST_TINT_ALPHA) * o.tint;
        return GlassStyle.EMPTY
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
}
