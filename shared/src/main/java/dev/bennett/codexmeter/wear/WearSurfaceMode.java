package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.NowBarDisplayMode;

public enum WearSurfaceMode {
    ONGOING_ACTIVITY,
    LIVE_UPDATE;

    /**
     * Phone Now Bar is not Wear Now Bar: phones use Android 16 Live Updates
     * (ProgressStyle plus requestPromotedOngoing) and Samsung private
     * android.ongoingActivityNoti.* extras for the One UI phone Now Bar.
     * Wear OS uses the androidx.wear:wear-ongoing Ongoing Activity API for
     * watch-face chips and Recents, which is the Wear-native analogue.
     *
     * Wear OS 7+ can post local Live Updates, but MetricStyle is not supported
     * on Wear and bridged phone Live Updates are OEM-optional and unreliable.
     * Samsung Galaxy Watch "Now Bar" belongs to the One UI Watch shell; third
     * parties cannot reuse the phone Samsung private extras on Wear, so the
     * phone samsung_compatibility setting maps to Wear Ongoing Activity.
     */
    public static WearSurfaceMode resolve(String selectedPhoneMode, int wearSdkInt,
            boolean liveUpdatesAvailable) {
        String normalized = NowBarDisplayMode.normalize(selectedPhoneMode);
        if (NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(normalized)) {
            return ONGOING_ACTIVITY;
        }
        if (NowBarDisplayMode.ANDROID_LIVE_UPDATE.equals(normalized)) {
            return wearSdkInt >= 36 && liveUpdatesAvailable ? LIVE_UPDATE : ONGOING_ACTIVITY;
        }
        return wearSdkInt >= 36 && liveUpdatesAvailable ? LIVE_UPDATE : ONGOING_ACTIVITY;
    }
}
