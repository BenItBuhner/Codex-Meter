package dev.bennett.codexmeter;

/**
 * In-app update policy gates. Builds before {@link #FIRST_IN_APP_UPDATE_VERSION} pointed at a
 * stale update source or had no updater at all, so installing or remaining on them requires a
 * manual GitHub APK install and cannot be reversed through the in-app flow.
 */
public final class ReleaseUpdatePolicy {
    /** First release with a working in-app updater against the canonical repository. */
    public static final String FIRST_IN_APP_UPDATE_VERSION = "2.3.0";

    private ReleaseUpdatePolicy() {
    }

    /** True when {@code version} is strictly older than {@link #FIRST_IN_APP_UPDATE_VERSION}. */
    public static boolean isIrreversible(String version) {
        ReleaseVersion candidate = ReleaseVersion.parse(version);
        ReleaseVersion threshold = ReleaseVersion.parse(FIRST_IN_APP_UPDATE_VERSION);
        return candidate != null && threshold != null && candidate.compareTo(threshold) < 0;
    }

    public static String irreversibleSummary() {
        return "Irreversible · Manual GitHub update required";
    }

    public static String irreversibleDetail() {
        return "Builds before Codex Meter " + FIRST_IN_APP_UPDATE_VERSION
                + " lack working in-app updates against the canonical repository. Install or "
                + "recover from this release only via its GitHub release page; the app cannot "
                + "upgrade you back afterward.";
    }
}
