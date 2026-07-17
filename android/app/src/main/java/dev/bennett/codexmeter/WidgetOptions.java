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

    public WidgetOptions(String str, String str2, String str3, int i, String str4, String str5) {
        this(str, "auto", SURFACE_MATERIAL, "auto", str2, str3, i, str4, str5, "both", false, true, true, true, false, false);
    }

    public WidgetOptions(String str, String str2, String str3, String str4, int i, String str5, String str6, boolean z, boolean z2, boolean z3) {
        this(str, str2, SURFACE_MATERIAL, "auto", str3, str4, i, str5, str6, "both", false, z, z2, z3, false, false);
    }

    public WidgetOptions(String str, String str2, String str3, String str4, String str5, String str6, int i, String str7, String str8, boolean z, boolean z2, boolean z3) {
        this(str, str2, str3, str4, str5, str6, i, str7, str8, "both", false, z, z2, z3, false, false);
    }

    public WidgetOptions(String str, String str2, String str3, String str4, String str5, String str6, int i, String str7, String str8, boolean z, boolean z2, boolean z3, boolean z4) {
        this(str, str2, str3, str4, str5, str6, i, str7, str8, "both", z, z2, z3, z4, false, false);
    }

    public WidgetOptions(String str, String str2, String str3, String str4, String str5, String str6, int i, String str7, String str8, String str9, boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6) {
        this(str, str2, str3, str4, str5, str6, i, str7, str8, str9, z, z2, z3,
                z4, z5, z6, true);
    }

    private WidgetOptions(String str, String str2, String str3, String str4, String str5,
            String str6, int i, String str7, String str8, String str9, boolean z, boolean z2,
            boolean z3, boolean z4, boolean z5, boolean z6, boolean showPercentSymbol) {
        this.layout = normalizeStyle(str);
        this.density = oneOf(str2, "auto", "compact", DENSITY_COMFORTABLE) ? str2 : "auto";
        this.surfaceStyle = oneOf(str3, SURFACE_MATERIAL, SURFACE_ONE_UI) ? str3 : SURFACE_MATERIAL;
        this.graphicScale = oneOf(str4, "auto", GRAPHIC_LARGE, GRAPHIC_MAX) ? str4 : "auto";
        this.theme = oneOf(str5, THEME_SYSTEM, THEME_DARK, THEME_LIGHT) ? str5 : THEME_SYSTEM;
        this.accent = validAccent(str6) ? str6 : ACCENT_MINT;
        if (i != 0 && i != 15 && i != 40 && i != 56 && i != 70 && i != 72 && i != 88 && i != 94 && i != 100) {
            i = 88;
        }
        this.opacity = i;
        this.resetMode = oneOf(str7, RESET_ABSOLUTE, RESET_RELATIVE, "both", RESET_HIDDEN) ? str7 : RESET_ABSOLUTE;
        this.displayMode = DISPLAY_USED.equals(str8) ? DISPLAY_USED : DISPLAY_REMAINING;
        this.metricMode = oneOf(str9, "both", "five_hour", "weekly") ? str9 : "both";
        this.showTitle = z;
        this.showPlan = z2;
        this.showUpdated = z3;
        this.showRefresh = z4;
        this.showResetCredits = z5;
        this.showResetAction = z6;
        this.showPercentSymbol = showPercentSymbol;
    }

    public WidgetOptions withPercentSymbol(boolean show) {
        return new WidgetOptions(this.layout, this.density, this.surfaceStyle,
                this.graphicScale, this.theme, this.accent, this.opacity, this.resetMode,
                this.displayMode, this.metricMode, this.showTitle, this.showPlan,
                this.showUpdated, this.showRefresh, this.showResetCredits,
                this.showResetAction, show);
    }

    public static WidgetOptions defaults() {
        return new WidgetOptions(STYLE_RINGS, "auto", SURFACE_ONE_UI, "auto", THEME_SYSTEM, ACCENT_BLUE, 88, RESET_HIDDEN, DISPLAY_REMAINING, "both", false, false, false, false, false, false);
    }

    public boolean showsFiveHour() {
        return !"weekly".equals(this.metricMode);
    }

    public boolean showsWeekly() {
        return !"five_hour".equals(this.metricMode);
    }

    public boolean singleMetric() {
        return !"both".equals(this.metricMode);
    }

    public static String normalizeStyle(String str) {
        if (LAYOUT_DETAILED.equals(str)) {
            return STYLE_BARS;
        }
        if ("compact".equals(str)) {
            return STYLE_MINIMAL;
        }
        return !oneOf(str, "auto", STYLE_BARS, STYLE_RINGS, STYLE_DIALS, STYLE_MINIMAL) ? "auto" : str;
    }

    public static String normalizeTapAction(String value) {
        if (TAP_REFRESH.equals(value) || TAP_USE_RESET.equals(value)) {
            return value;
        }
        return TAP_OPEN_APP;
    }

    private static boolean validAccent(String str) {
        return oneOf(str, ACCENT_APP, ACCENT_MINT, ACCENT_BLUE, ACCENT_AMBER,
                ACCENT_VIOLET, ACCENT_ROSE, ACCENT_CYAN, ACCENT_LIME, ACCENT_MONO);
    }

    private static boolean oneOf(String str, String... strArr) {
        if (str == null) {
            return false;
        }
        for (String str2 : strArr) {
            if (str2.equals(str)) {
                return true;
            }
        }
        return false;
    }
}
