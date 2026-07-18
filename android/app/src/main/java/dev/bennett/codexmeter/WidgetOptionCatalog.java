package dev.bennett.codexmeter;

import android.widget.Spinner;

/* JADX INFO: loaded from: classes.dex */
final class WidgetOptionCatalog {
    static final String[] THEME_LABELS = {"Follow system", "Dark", "Light"};
    static final String[] THEME_VALUES = {WidgetOptions.THEME_SYSTEM, WidgetOptions.THEME_DARK, WidgetOptions.THEME_LIGHT};
    static final String[] SURFACE_LABELS = {"One UI"};
    static final String[] SURFACE_VALUES = {WidgetOptions.SURFACE_ONE_UI};
    static final String[] STYLE_LABELS = {"Two usage dials"};
    static final String[] STYLE_VALUES = {WidgetOptions.STYLE_RINGS};
    static final String[] DENSITY_LABELS = {"Auto", "Compact", "Comfortable"};
    static final String[] DENSITY_VALUES = {"auto", "compact", WidgetOptions.DENSITY_COMFORTABLE};
    static final String[] GRAPHIC_LABELS = {"Fit automatically", "Large", "Maximum"};
    static final String[] GRAPHIC_VALUES = {"auto", WidgetOptions.GRAPHIC_LARGE, WidgetOptions.GRAPHIC_MAX};
    static final String[] ACCENT_LABELS = {"Mint", "Blue", "Amber", "Violet", "Rose", "Cyan", "Lime", "Monochrome"};
    static final String[] ACCENT_VALUES = {WidgetOptions.ACCENT_MINT, WidgetOptions.ACCENT_BLUE, WidgetOptions.ACCENT_AMBER, WidgetOptions.ACCENT_VIOLET, WidgetOptions.ACCENT_ROSE, WidgetOptions.ACCENT_CYAN, WidgetOptions.ACCENT_LIME, WidgetOptions.ACCENT_MONO};
    /** One UI 7-style discrete fill strengths when the widget background is enabled. */
    static final String[] OPACITY_LABELS = {"56%", "88%", "100%"};
    static final int[] OPACITY_VALUES = WidgetOptions.OPACITY_LEVELS;
    static final String[] RESET_LABELS = {"Absolute time", "Relative time", "Both", "Hide reset time"};
    static final String[] RESET_VALUES = {WidgetOptions.RESET_ABSOLUTE, WidgetOptions.RESET_RELATIVE, "both", WidgetOptions.RESET_HIDDEN};
    static final String[] METRIC_LABELS = {"5-hour and weekly"};
    static final String[] METRIC_VALUES = {"both"};
    static final String[] DISPLAY_LABELS = {"Percent remaining", "Percent used"};
    static final String[] DISPLAY_VALUES = {WidgetOptions.DISPLAY_REMAINING, WidgetOptions.DISPLAY_USED};

    private WidgetOptionCatalog() {
    }

    static void selectString(Spinner spinner, String[] values, String selected) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }
}
