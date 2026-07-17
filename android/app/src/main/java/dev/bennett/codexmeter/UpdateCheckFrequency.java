package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;

/**
 * Supported automatic GitHub release-check intervals. Pure Java so self-tests can
 * validate normalization without Android.
 */
public final class UpdateCheckFrequency {
    public static final int HOURLY = 1;
    public static final int EVERY_6_HOURS = 6;
    public static final int EVERY_12_HOURS = 12;
    public static final int DAILY = 24;
    public static final int WEEKLY = 168;

    public static final int[] SUPPORTED_HOURS = {
            HOURLY, EVERY_6_HOURS, EVERY_12_HOURS, DAILY, WEEKLY
    };

    private UpdateCheckFrequency() {
    }

    /** Maps stored hours onto a supported interval; unknown values become daily. */
    public static int normalize(int hours) {
        for (int supported : SUPPORTED_HOURS) {
            if (hours == supported) {
                return supported;
            }
        }
        return DAILY;
    }

    public static long periodMillis(int hours) {
        return TimeUnit.HOURS.toMillis(normalize(hours));
    }

    /**
     * Flex window for {@link android.app.job.JobInfo#setPeriodic(long, long)}. Kept well
     * under the period so checks stay near the chosen cadence while Android may still
     * batch work for battery.
     */
    public static long flexMillis(int hours) {
        switch (normalize(hours)) {
            case HOURLY:
                return TimeUnit.MINUTES.toMillis(15);
            case EVERY_6_HOURS:
                return TimeUnit.HOURS.toMillis(1);
            case EVERY_12_HOURS:
                return TimeUnit.HOURS.toMillis(2);
            case WEEKLY:
                return TimeUnit.HOURS.toMillis(12);
            case DAILY:
            default:
                return TimeUnit.HOURS.toMillis(6);
        }
    }

    public static String label(int hours) {
        switch (normalize(hours)) {
            case HOURLY:
                return "Every hour";
            case EVERY_6_HOURS:
                return "Every 6 hours";
            case EVERY_12_HOURS:
                return "Every 12 hours";
            case WEEKLY:
                return "Weekly";
            case DAILY:
            default:
                return "Daily";
        }
    }

    public static String summary(int hours) {
        return "Check signed GitHub releases " + label(normalize(hours)).toLowerCase();
    }
}
