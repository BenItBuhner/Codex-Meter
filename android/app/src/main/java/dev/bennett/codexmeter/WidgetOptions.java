package dev.bennett.codexmeter;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetOptions {
    public static final String ACCENT_APP = "app";
    public static final String ACCENT_AMBER = "amber";
    public static final String ACCENT_BLUE = "blue";
    public static final String ACCENT_CYAN = "cyan";
    public static final String ACCENT_LIME = "lime";
    public static final String ACCENT_MINT = "mint";
    public static final String ACCENT_MONO = "mono";
    public static final String ACCENT_ROSE = "rose";
    public static final String ACCENT_VIOLET = "violet";
    public static final String DENSITY_AUTO = "auto";
    public static final String DENSITY_COMFORTABLE = "comfortable";
    public static final String DENSITY_COMPACT = "compact";
    public static final String DISPLAY_REMAINING = "remaining";
    public static final String DISPLAY_USED = "used";
    public static final String GRAPHIC_AUTO = "auto";
    public static final String GRAPHIC_LARGE = "large";
    public static final String GRAPHIC_MAX = "maximum";
    public static final String LAYOUT_AUTO = "auto";
    public static final String LAYOUT_COMPACT = "compact";
    public static final String LAYOUT_DETAILED = "detailed";
    public static final String METRIC_BOTH = "both";
    public static final String METRIC_FIVE_HOUR = "five_hour";
    public static final String METRIC_WEEKLY = "weekly";
    public static final String RESET_ABSOLUTE = "absolute";
    public static final String RESET_BOTH = "both";
    public static final String RESET_HIDDEN = "hidden";
    public static final String RESET_RELATIVE = "relative";
    public static final String STYLE_AUTO = "auto";
    public static final String STYLE_BARS = "bars";
    public static final String STYLE_DIALS = "dials";
    public static final String STYLE_MINIMAL = "minimal";
    public static final String STYLE_RINGS = "rings";
    public static final String SURFACE_MATERIAL = "material";
    public static final String SURFACE_ONE_UI = "one_ui";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_SYSTEM = "system";
    public static final String TAP_OPEN_APP = "open_app";
    public static final String TAP_REFRESH = "refresh";
    public static final String TAP_USE_RESET = "use_reset";
    /** One UI 7-style discrete fill strengths when the widget background is enabled. */
    public static final int[] OPACITY_LEVELS = {56, 88, 100};
    public static final int DEFAULT_OPACITY = 88;
    public final String accent;
    public final String density;
    public final String displayMode;
    public final String graphicScale;
    public final String layout;
    public final String metricMode;
    public final int opacity;
    public final String resetMode;
    public final boolean showPlan;
    public final boolean showRefresh;
    public final boolean showResetAction;
    public final boolean showResetCredits;
    public final boolean showTitle;
    public final boolean showUpdated;
    public final boolean showPercentSymbol;
    public final String surfaceStyle;
    public final String theme;
    /** Ordered CSV of {@link WidgetMeters} keys; empty means migrate from {@link #metricMode}. */
    public final String visibleMeters;

    public WidgetOptions(String layout, String theme, String accent, int opacity, String resetMode, String displayMode) {
        this(layout, "auto", SURFACE_MATERIAL, "auto", theme, accent, opacity, resetMode, displayMode, "both", false, true, true, true, false, false);
    }

    public WidgetOptions(String layout, String density, String theme, String accent, int opacity, String resetMode, String displayMode, boolean showPlan, boolean showUpdated, boolean showRefresh) {
        this(layout, density, SURFACE_MATERIAL, "auto", theme, accent, opacity, resetMode, displayMode, "both", false, showPlan, showUpdated, showRefresh, false, false);
    }

    public WidgetOptions(String layout, String density, String surface, String graphicScale, String theme, String accent, int opacity, String resetMode, String displayMode, boolean showPlan, boolean showUpdated, boolean showRefresh) {
        this(layout, density, surface, graphicScale, theme, accent, opacity, resetMode, displayMode, "both", false, showPlan, showUpdated, showRefresh, false, false);
    }

    public WidgetOptions(String layout, String density, String surface, String graphicScale, String theme, String accent, int opacity, String resetMode, String displayMode, boolean showTitle, boolean showPlan, boolean showUpdated, boolean showRefresh) {
        this(layout, density, surface, graphicScale, theme, accent, opacity, resetMode, displayMode, "both", showTitle, showPlan, showUpdated, showRefresh, false, false);
    }

    public WidgetOptions(String layout, String density, String surface, String graphicScale, String theme, String accent, int opacity, String resetMode, String displayMode, String metricMode, boolean showTitle, boolean showPlan, boolean showUpdated, boolean showRefresh, boolean showResetCredits, boolean showResetAction) {
        this(layout, density, surface, graphicScale, theme, accent, opacity, resetMode, displayMode, metricMode, showTitle, showPlan, showUpdated,
                showRefresh, showResetCredits, showResetAction, true, "");
    }

    private WidgetOptions(String layout, String density, String surface, String graphicScale, String theme,
            String accent, int opacity, String resetMode, String displayMode, String metricMode, boolean showTitle, boolean showPlan,
            boolean showUpdated, boolean showRefresh, boolean showResetCredits, boolean showResetAction, boolean showPercentSymbol) {
        this(layout, density, surface, graphicScale, theme, accent, opacity, resetMode, displayMode, metricMode, showTitle, showPlan, showUpdated, showRefresh, showResetCredits, showResetAction,
                showPercentSymbol, "");
    }

    private WidgetOptions(String layout, String density, String surface, String graphicScale, String theme,
            String accent, int opacity, String resetMode, String displayMode, String metricMode, boolean showTitle, boolean showPlan,
            boolean showUpdated, boolean showRefresh, boolean showResetCredits, boolean showResetAction, boolean showPercentSymbol,
            String visibleMeters) {
        this.layout = normalizeStyle(layout);
        this.density = oneOf(density, "auto", "compact", DENSITY_COMFORTABLE) ? density : "auto";
        this.surfaceStyle = oneOf(surface, SURFACE_MATERIAL, SURFACE_ONE_UI) ? surface : SURFACE_MATERIAL;
        this.graphicScale = oneOf(graphicScale, "auto", GRAPHIC_LARGE, GRAPHIC_MAX) ? graphicScale : "auto";
        this.theme = oneOf(theme, THEME_SYSTEM, THEME_DARK, THEME_LIGHT) ? theme : THEME_SYSTEM;
        this.accent = validAccent(accent) ? accent : ACCENT_MINT;
        // Accept legacy four-step and drawable-aligned values, then snap to One UI's three
        // fill strengths (or fully off) so saved widgets migrate cleanly.
        if (opacity != 0 && opacity != 15 && opacity != 40 && opacity != 56 && opacity != 70 && opacity != 72 && opacity != 88 && opacity != 94
                && opacity != 100) {
            opacity = DEFAULT_OPACITY;
        } else if (opacity > 0) {
            opacity = snapOpacity(opacity);
        }
        this.opacity = opacity;
        this.resetMode = oneOf(resetMode, RESET_ABSOLUTE, RESET_RELATIVE, "both", RESET_HIDDEN) ? resetMode : RESET_ABSOLUTE;
        this.displayMode = DISPLAY_USED.equals(displayMode) ? DISPLAY_USED : DISPLAY_REMAINING;
        this.metricMode = oneOf(metricMode, "both", "five_hour", "weekly") ? metricMode : "both";
        this.showTitle = showTitle;
        this.showPlan = showPlan;
        this.showUpdated = showUpdated;
        this.showRefresh = showRefresh;
        this.showResetCredits = showResetCredits;
        this.showResetAction = showResetAction;
        this.showPercentSymbol = showPercentSymbol;
        this.visibleMeters = visibleMeters == null ? "" : visibleMeters.trim();
    }

    public WidgetOptions withPercentSymbol(boolean show) {
        return new WidgetOptions(this.layout, this.density, this.surfaceStyle,
                this.graphicScale, this.theme, this.accent, this.opacity, this.resetMode,
                this.displayMode, this.metricMode, this.showTitle, this.showPlan,
                this.showUpdated, this.showRefresh, this.showResetCredits,
                this.showResetAction, show, this.visibleMeters);
    }

    public WidgetOptions withVisibleMeters(String metersCsv) {
        return new WidgetOptions(this.layout, this.density, this.surfaceStyle,
                this.graphicScale, this.theme, this.accent, this.opacity, this.resetMode,
                this.displayMode, this.metricMode, this.showTitle, this.showPlan,
                this.showUpdated, this.showRefresh, this.showResetCredits,
                this.showResetAction, this.showPercentSymbol,
                metersCsv == null ? "" : metersCsv);
    }

    public static WidgetOptions defaults() {
        return new WidgetOptions(STYLE_AUTO, "auto", SURFACE_ONE_UI, "auto", THEME_SYSTEM,
                ACCENT_BLUE, DEFAULT_OPACITY, RESET_HIDDEN, DISPLAY_REMAINING, "both",
                false, false, false, false, false, false)
                .withVisibleMeters(WidgetMeters.serialize(WidgetMeters.defaultVisible()));
    }

    /** Nearest allowed opacity when background is on; {@code 0} stays fully off. */
    public static int snapOpacity(int opacity) {
        if (opacity <= 0) {
            return 0;
        }
        int best = DEFAULT_OPACITY;
        int distance = Integer.MAX_VALUE;
        for (int value : OPACITY_LEVELS) {
            int candidate = Math.abs(value - opacity);
            // Prefer the stronger fill when two levels are equidistant (e.g. 94 → 100).
            if (candidate < distance || (candidate == distance && value > best)) {
                best = value;
                distance = candidate;
            }
        }
        return best;
    }

    /** Slider index for a stored opacity; background-off restores the medium tick. */
    public static int opacityIndex(int opacity) {
        int snapped = snapOpacity(opacity <= 0 ? DEFAULT_OPACITY : opacity);
        for (int i = 0; i < OPACITY_LEVELS.length; i++) {
            if (OPACITY_LEVELS[i] == snapped) {
                return i;
            }
        }
        return 1;
    }

    /** Effective auto / dials / bars preference used by the renderer. */
    public String layoutPreference() {
        return WidgetMeters.layoutPreference(this.layout);
    }

    /** Resolved visible-meters CSV, migrating from metric_mode when unset. */
    public String effectiveVisibleMeters() {
        return WidgetMeters.effectiveVisibleCsv(this.visibleMeters, this.metricMode);
    }

    public boolean showsFiveHour() {
        return WidgetMeters.contains(
                WidgetMeters.parse(effectiveVisibleMeters()), WidgetMeters.FIVE_HOUR);
    }

    public boolean showsWeekly() {
        return WidgetMeters.contains(
                WidgetMeters.parse(effectiveVisibleMeters()), WidgetMeters.WEEKLY);
    }

    public boolean singleMetric() {
        return WidgetMeters.resolvedSingleUsageMetric(effectiveVisibleMeters(),
                WidgetMeters.availableKeys(null), metricMode);
    }

    public static String normalizeStyle(String style) {
        if (LAYOUT_DETAILED.equals(style)) {
            return STYLE_BARS;
        }
        if ("compact".equals(style)) {
            return STYLE_MINIMAL;
        }
        // Keep rings/minimal as stored values for transfer round-trips; layoutPreference()
        // maps them to adaptive auto at render time.
        return !oneOf(style, "auto", STYLE_BARS, STYLE_RINGS, STYLE_DIALS, STYLE_MINIMAL)
                ? "auto" : style;
    }

    public static String normalizeTapAction(String value) {
        if (TAP_REFRESH.equals(value) || TAP_USE_RESET.equals(value)) {
            return value;
        }
        return TAP_OPEN_APP;
    }

    private static boolean validAccent(String accent) {
        return oneOf(accent, ACCENT_APP, ACCENT_MINT, ACCENT_BLUE, ACCENT_AMBER,
                ACCENT_VIOLET, ACCENT_ROSE, ACCENT_CYAN, ACCENT_LIME, ACCENT_MONO);
    }

    private static boolean oneOf(String value, String... options) {
        if (value == null) {
            return false;
        }
        for (String option : options) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
