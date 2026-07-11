package dev.bennett.codexmeter;

/* JADX INFO: loaded from: classes.dex */
final class WidgetOptionCatalog {
    static final String[] THEME_LABELS = {"Follow system", "Dark", "Light"};
    static final String[] THEME_VALUES = {WidgetOptions.THEME_SYSTEM, WidgetOptions.THEME_DARK, WidgetOptions.THEME_LIGHT};
    static final String[] SURFACE_LABELS = {"Material 3 expressive", "One UI 8+"};
    static final String[] SURFACE_VALUES = {WidgetOptions.SURFACE_MATERIAL, WidgetOptions.SURFACE_ONE_UI};
    static final String[] STYLE_LABELS = {"Adaptive by size", "Horizontal bars", "Circular rings", "Gauge dials", "Minimal"};
    static final String[] STYLE_VALUES = {"auto", WidgetOptions.STYLE_BARS, WidgetOptions.STYLE_RINGS, WidgetOptions.STYLE_DIALS, WidgetOptions.STYLE_MINIMAL};
    static final String[] DENSITY_LABELS = {"Auto", "Compact", "Comfortable"};
    static final String[] DENSITY_VALUES = {"auto", "compact", WidgetOptions.DENSITY_COMFORTABLE};
    static final String[] GRAPHIC_LABELS = {"Fit automatically", "Large", "Maximum"};
    static final String[] GRAPHIC_VALUES = {"auto", WidgetOptions.GRAPHIC_LARGE, WidgetOptions.GRAPHIC_MAX};
    static final String[] ACCENT_LABELS = {"Mint", "Blue", "Amber", "Violet", "Rose", "Cyan", "Lime", "Monochrome"};
    static final String[] ACCENT_VALUES = {WidgetOptions.ACCENT_MINT, WidgetOptions.ACCENT_BLUE, WidgetOptions.ACCENT_AMBER, WidgetOptions.ACCENT_VIOLET, WidgetOptions.ACCENT_ROSE, WidgetOptions.ACCENT_CYAN, WidgetOptions.ACCENT_LIME, WidgetOptions.ACCENT_MONO};
    static final String[] OPACITY_LABELS = {"Fully transparent", "56% translucent", "72% translucent", "88% translucent", "100% opaque"};
    static final int[] OPACITY_VALUES = {0, 56, 72, 88, 100};
    static final String[] RESET_LABELS = {"Absolute time", "Relative time", "Both", "Hide reset time"};
    static final String[] RESET_VALUES = {WidgetOptions.RESET_ABSOLUTE, WidgetOptions.RESET_RELATIVE, "both", WidgetOptions.RESET_HIDDEN};
    static final String[] METRIC_LABELS = {"5-hour and weekly", "5-hour only", "Weekly only"};
    static final String[] METRIC_VALUES = {"both", "five_hour", "weekly"};
    static final String[] DISPLAY_LABELS = {"Percent remaining", "Percent used"};
    static final String[] DISPLAY_VALUES = {WidgetOptions.DISPLAY_REMAINING, WidgetOptions.DISPLAY_USED};

    private WidgetOptionCatalog() {
    }
}
