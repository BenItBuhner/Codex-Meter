package dev.bennett.codexmeter;

/** Fixed public source monitored by this feature. */
public final class ResetWatchSource {
    public static final String HANDLE = "thsottiaux";
    public static final String PROFILE_URL = "https://x.com/" + HANDLE;

    private ResetWatchSource() {
    }

    public static String postUrl(String id) {
        return PROFILE_URL + "/status/" + id;
    }
}
