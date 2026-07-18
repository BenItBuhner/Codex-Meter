package dev.bennett.codexmeter;

/** Stable persisted values for the independently selectable Android design system. */
public final class AppDesignStyle {
    public static final String MATERIAL_EXPRESSIVE = WidgetOptions.SURFACE_MATERIAL;
    public static final String ONE_UI = WidgetOptions.SURFACE_ONE_UI;

    private AppDesignStyle() {
    }

    public static String normalize(String value) {
        return MATERIAL_EXPRESSIVE.equals(value) ? MATERIAL_EXPRESSIVE : ONE_UI;
    }

    public static boolean isMaterialExpressive(String value) {
        return MATERIAL_EXPRESSIVE.equals(normalize(value));
    }
}
