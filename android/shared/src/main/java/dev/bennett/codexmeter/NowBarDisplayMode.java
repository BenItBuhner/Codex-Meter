package dev.bennett.codexmeter;

/** Selects one mutually exclusive notification contract for the live usage monitor. */
public final class NowBarDisplayMode {
    public static final String AUTO = "auto";
    public static final String ANDROID_LIVE_UPDATE = "android_live_update";
    public static final String SAMSUNG_COMPATIBILITY = "samsung_compatibility";

    private NowBarDisplayMode() {
    }

    public static String normalize(String mode) {
        if (ANDROID_LIVE_UPDATE.equals(mode) || SAMSUNG_COMPATIBILITY.equals(mode)) {
            return mode;
        }
        return AUTO;
    }

    /**
     * Samsung currently gates Android 16 Live Updates by firmware policy. In automatic mode,
     * use its private compatibility payload only when the platform promotion path is unavailable.
     */
    public static String resolve(String selectedMode, boolean samsungDevice, int sdkInt,
            boolean canPostPromotedNotifications) {
        String normalized = normalize(selectedMode);
        if (!AUTO.equals(normalized)) return normalized;
        if (samsungDevice && (sdkInt < 36 || !canPostPromotedNotifications)) {
            return SAMSUNG_COMPATIBILITY;
        }
        return ANDROID_LIVE_UPDATE;
    }

    /**
     * Returns whether an active notification must be rebuilt after Android's promotion access
     * or the automatically resolved display mode changes.
     */
    public static boolean notificationContractChanged(String postedMode,
            boolean postedPromotionAllowed, String resolvedMode,
            boolean promotionAllowedNow) {
        return postedMode == null
                || !normalize(postedMode).equals(normalize(resolvedMode))
                || postedPromotionAllowed != promotionAllowedNow;
    }
}
