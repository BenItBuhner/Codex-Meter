package dev.bennett.codexmeter;

/**
 * The app design ids saved in Settings → Appearance → App design, and how older saved values read.
 * Pure Java so the self-tests can check every mapping.
 *
 * <p>Liquid Glass comes in two inspired looks that share all of the glass screens:
 * {@code liquid_glass_ios_26} and {@code liquid_glass_ios_27}. Installs that saved the single
 * {@code liquid_glass} id before the split keep the look they had, which is the iOS 26-inspired one;
 * nothing moves them to the newer look.
 */
final class DesignIds {
    private DesignIds() {}

    static final String CLASSIC = "classic";
    static final String ONE_UI_9 = "one_ui_9";
    /** The id saved before Liquid Glass had two looks. Read as {@link #LIQUID_GLASS_IOS_26}. */
    static final String LIQUID_GLASS_LEGACY = "liquid_glass";
    static final String LIQUID_GLASS_IOS_26 = "liquid_glass_ios_26";
    static final String LIQUID_GLASS_IOS_27 = "liquid_glass_ios_27";
    /** Google's Material 3 Expressive, drawn with Material Components' own views. */
    static final String MATERIAL_3_EXPRESSIVE = "material_3_expressive";
    /** One UI 9 reports ro.build.version.oneui as 90000 (major * 10000 + minor * 100). */
    static final int ONE_UI_9_VERSION = 90000;

    /**
     * The design to use for a saved value. {@code legacyOneUi9} is the switch installs stored before
     * there was a design list.
     */
    static String normalize(String saved, boolean legacyOneUi9) {
        if (saved != null) {
            switch (saved) {
                case CLASSIC:
                case ONE_UI_9:
                case LIQUID_GLASS_IOS_26:
                case LIQUID_GLASS_IOS_27:
                case MATERIAL_3_EXPRESSIVE:
                    return saved;
                case LIQUID_GLASS_LEGACY:
                    return LIQUID_GLASS_IOS_26;
                default:
                    break;
            }
        }
        return legacyOneUi9 ? ONE_UI_9 : CLASSIC;
    }

    /** The id to save for a choice; anything unknown saves as classic. */
    static String forSave(String choice) {
        return normalize(choice, false);
    }

    static boolean isLiquidGlass(String id) {
        return LIQUID_GLASS_IOS_26.equals(id) || LIQUID_GLASS_IOS_27.equals(id)
                || LIQUID_GLASS_LEGACY.equals(id);
    }

    static boolean isIos27(String id) {
        return LIQUID_GLASS_IOS_27.equals(id);
    }

    static boolean isMaterialExpressive(String id) {
        return MATERIAL_3_EXPRESSIVE.equals(id);
    }

    /**
     * The design to start a new install on, from what the device says it is, or null to keep the
     * app's default. Advisory only: it is used once, on first run, when nothing is saved yet, and
     * Settings can change it any time.
     *
     * <p>Pixels and other Google-made phones get Material 3 Expressive. Samsung phones get One UI 9
     * only when the One UI version itself ({@code ro.build.version.oneui}) says 9 or later; the
     * Android version is not the One UI version, so a Samsung phone that doesn't report it keeps
     * the default (One UI 8.5).
     *
     * @param oneUiVersion {@code ro.build.version.oneui} as a number, or 0 when it can't be read
     */
    static String suggestFor(String manufacturer, String brand, int oneUiVersion) {
        if (is("google", manufacturer) || is("google", brand)) {
            return MATERIAL_3_EXPRESSIVE;
        }
        if (is("samsung", manufacturer) || is("samsung", brand)) {
            return oneUiVersion >= ONE_UI_9_VERSION ? ONE_UI_9 : CLASSIC;
        }
        return null;
    }

    private static boolean is(String want, String value) {
        return value != null && want.equalsIgnoreCase(value.trim());
    }

    /** The name Settings shows for a design. */
    static String label(String id) {
        switch (normalize(id, false)) {
            case ONE_UI_9:
                return "One UI 9";
            case LIQUID_GLASS_IOS_26:
                return "Liquid Glass (iOS 26-inspired)";
            case LIQUID_GLASS_IOS_27:
                return "Liquid Glass (iOS 27-inspired)";
            case MATERIAL_3_EXPRESSIVE:
                return "Material 3 Expressive";
            default:
                return "One UI 8.5";
        }
    }
}
