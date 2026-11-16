package dev.bennett.codexmeter;

/** The one place text from servers, settings and users is trimmed and bounded before it is kept or shown. */
public final class Text {
    private Text() {
    }

    /** {@code value} without surrounding whitespace, or "" when there is none. */
    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** {@link #clean} of {@code value}, or {@code fallback} when that is empty. */
    public static String orDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /** {@link #clean} of {@code value}, cut to at most {@code max} chars without splitting a character. */
    public static String clip(String value, int max) {
        String cleaned = clean(value);
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, cutPoint(cleaned, max));
    }

    /** Like {@link #clip}, but a cut string ends in "…" and still fits in {@code max} chars. */
    public static String ellipsize(String value, int max) {
        String cleaned = clean(value);
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, cutPoint(cleaned, max - 1)).trim() + "…";
    }

    private static int cutPoint(String value, int max) {
        int end = Math.max(0, max);
        return end > 0 && Character.isHighSurrogate(value.charAt(end - 1)) ? end - 1 : end;
    }
}
