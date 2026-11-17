package dev.bennett.codexmeter;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import dev.bennett.codexmeter.glass.CodexGlass;

/**
 * The app's one haptics setting (Settings › Appearance › Haptics): Off, Light or Strong. Every
 * design plays its touch feedback through here, so Off silences all of it.
 */
final class AppHaptics {
    static final String OFF = CodexGlass.HAPTICS_OFF;
    static final String LIGHT = CodexGlass.HAPTICS_LIGHT;
    static final String STRONG = CodexGlass.HAPTICS_STRONG;

    private AppHaptics() {
    }

    static String normalize(String level) {
        return OFF.equals(level) || STRONG.equals(level) ? level : LIGHT;
    }

    /** A light tick: chart scrubbing, a switch, a tab. Skipped when haptics are off. */
    static void tick(View view) {
        play(view, HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /** A confirmation: a dismissed row, a long press. Skipped when haptics are off. */
    static void confirm(View view) {
        play(view, HapticFeedbackConstants.CONTEXT_CLICK,
                android.os.Build.VERSION.SDK_INT >= 30
                        ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
    }

    private static void play(View view, int light, int strong) {
        if (view == null) return;
        String level = AppPreferences.getHaptics(view.getContext());
        if (OFF.equals(level)) return;
        view.performHapticFeedback(STRONG.equals(level) ? strong : light);
    }

    /** Hands the setting to the Liquid Glass toolkit, whose own controls play its haptics. */
    static void apply(Context context) {
        CodexGlass.setHaptics(AppPreferences.getHaptics(context));
    }
}
