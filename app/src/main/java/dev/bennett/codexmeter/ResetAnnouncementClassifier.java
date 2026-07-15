package dev.bennett.codexmeter;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tiny, deterministic linear intent scorer for @thsottiaux posts.
 *
 * <p>The available public examples are too sparse for defensible statistical training. These
 * explicit features make every result reviewable and can later be replaced with coefficients
 * fitted from an authorized, hand-labeled X timeline export.</p>
 */
public final class ResetAnnouncementClassifier {
    public static final int MODEL_VERSION = 1;

    private static final Pattern TIME_WINDOW = Pattern.compile(
            "\\b(next|within|over)\\s+(the\\s+)?\\d+\\s*(minute|minutes|min|hour|hours|hr|hrs|day|days)\\b");
    private static final Pattern RESET_NEGATION = Pattern.compile(
            "\\b(no|not|never|without|won't|will not|isn't|is not|aren't|are not)\\b[^.!?]{0,28}\\breset");
    private static final Pattern COMPLETED = Pattern.compile(
            "\\b(has|have|is|are|just|now|already)\\b[^.!?]{0,24}\\b(reset|resetting|restored|refilled)\\b"
                    + "|\\breset\\b[^.!?]{0,20}\\b(complete|completed|done|landed|live)\\b");

    private ResetAnnouncementClassifier() {
    }

    public static ResetSignal classify(String text) {
        String value = normalize(text);
        if (value.isEmpty()) return new ResetSignal(ResetSignal.NONE, 0);

        boolean product = containsAny(value, "codex", "chatgpt work", "chatgpt team",
                "chatgpt enterprise");
        boolean usage = containsAny(value, "usage limit", "usage limits", "rate limit",
                "rate limits", "allowance", "quota");
        boolean reset = containsAny(value, "reset", "resetting", "refill", "refilling");
        boolean bank = containsAny(value, "reset bank", "reset-bank", "banked reset",
                "bankable reset", "credit", "credits", "into your bank");
        boolean future = containsAny(value, "will reset", "will be reset", "going to reset",
                "resetting soon", "coming soon", "later today", "rolling out",
                "should land", "will land", "over the next", "in the next",
                "another usage limit reset", "another rate limit reset");
        boolean timed = TIME_WINDOW.matcher(value).find();
        boolean completed = COMPLETED.matcher(value).find();
        boolean negated = RESET_NEGATION.matcher(value).find();

        if (negated || !reset || (!product && !usage && !bank)) {
            return new ResetSignal(ResetSignal.NONE, negated ? 5 : 0);
        }

        int score = 20;
        if (product) score += 25;
        if (usage) score += 20;
        if (bank) score += 20;
        if (future) score += 15;
        if (timed) score += 15;
        if (completed) score += 10;

        if (bank) {
            return new ResetSignal(ResetSignal.RESET_CREDIT, score);
        }
        if (future || timed) {
            return new ResetSignal(ResetSignal.RESET_IMMINENT, score);
        }
        if (completed) {
            return new ResetSignal(ResetSignal.RESET_CONFIRMED, score);
        }
        return new ResetSignal(ResetSignal.GENERAL_UPDATE, Math.min(score, 65));
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
