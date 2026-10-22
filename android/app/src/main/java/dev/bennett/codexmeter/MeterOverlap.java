package dev.bennett.codexmeter;

/**
 * Overlapped meters: a provider's short limit (5-hour) and long limit (weekly or monthly) share
 * one dial or bar. The short limit keeps the full color and the long one a lighter shade; the
 * larger of the two is drawn first so the smaller always shows on top of it. Pure Java so the
 * self-tests cover it.
 */
public final class MeterOverlap {
    private MeterOverlap() {
    }

    /** True when the long limit is the larger fill, so it goes underneath. */
    public static boolean longUnderneath(int shortValue, int longValue) {
        return clamp(longValue) >= clamp(shortValue);
    }

    /** The fill drawn underneath: the larger of the two. */
    public static int under(int shortValue, int longValue) {
        return Math.max(clamp(shortValue), clamp(longValue));
    }

    /** The fill drawn on top: the smaller of the two, or -1 when one limit is missing. */
    public static int over(int shortValue, int longValue) {
        if (shortValue < 0 || longValue < 0) return -1;
        return Math.min(clamp(shortValue), clamp(longValue));
    }

    /** "63 · 39", short limit first; a missing side reads "—". */
    public static String text(int shortValue, int longValue, boolean percent) {
        return part(shortValue, false) + " · " + part(longValue, percent);
    }

    private static String part(int value, boolean percent) {
        return value < 0 ? "—" : clamp(value) + (percent ? "%" : "");
    }

    private static int clamp(int value) {
        return value < 0 ? -1 : Math.min(100, value);
    }
}
