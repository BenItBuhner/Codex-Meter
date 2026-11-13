package dev.bennett.codexmeter;

/**
 * Selects which notification contract the live usage monitor carries. Automatic picks exactly
 * one; {@link #BOTH} is an opt-in for firmware that only shows one of them reliably.
 */
public final class NowBarDisplayMode {
    public static final String AUTO = "auto";
    /** Android 16 Live Update ("live notification"). */
    public static final String ANDROID_LIVE_UPDATE = "android_live_update";
    /** Samsung's private ongoing-activity payload ("Now Bar"). */
    public static final String SAMSUNG_COMPATIBILITY = "samsung_compatibility";
    /** Both payloads on the one notification. */
    public static final String BOTH = "both";

    private NowBarDisplayMode() {
    }

    public static String normalize(String mode) {
        if (ANDROID_LIVE_UPDATE.equals(mode) || SAMSUNG_COMPATIBILITY.equals(mode)
                || BOTH.equals(mode)) {
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

    /** Whether a resolved mode carries Samsung's Now Bar payload. */
    public static boolean usesSamsungPayload(String resolvedMode) {
        String normalized = normalize(resolvedMode);
        return SAMSUNG_COMPATIBILITY.equals(normalized) || BOTH.equals(normalized);
    }

    /** Whether a resolved mode requests Android Live Update promotion. */
    public static boolean usesAndroidLiveUpdate(String resolvedMode) {
        String normalized = normalize(resolvedMode);
        return !SAMSUNG_COMPATIBILITY.equals(normalized);
    }
}
