package dev.bennett.codexmeter;

/** Auditable reset-announcement classification returned by the local intent scorer. */
public final class ResetSignal {
    public static final String NONE = "none";
    public static final String RESET_IMMINENT = "reset_imminent";
    public static final String RESET_CREDIT = "reset_credit";
    public static final String RESET_CONFIRMED = "reset_confirmed";
    public static final String GENERAL_UPDATE = "general_update";

    public final String category;
    public final int likelihood;

    ResetSignal(String category, int likelihood) {
        this.category = normalize(category);
        this.likelihood = Math.max(0, Math.min(100, likelihood));
    }

    public boolean isRelevant() {
        return !NONE.equals(category);
    }

    public boolean isActionable() {
        return likelihood >= 70
                && (RESET_IMMINENT.equals(category)
                || RESET_CREDIT.equals(category)
                || RESET_CONFIRMED.equals(category));
    }

    public String title() {
        if (RESET_CREDIT.equals(category)) return "Reset-bank credit may be coming";
        if (RESET_IMMINENT.equals(category)) return "Codex reset looks imminent";
        if (RESET_CONFIRMED.equals(category)) return "Codex reset announced";
        if (GENERAL_UPDATE.equals(category)) return "Codex usage update";
        return "No reset signal";
    }

    public String likelihoodLabel() {
        if (likelihood >= 90) return "Very likely";
        if (likelihood >= 70) return "Likely";
        if (likelihood >= 50) return "Possible";
        return "Low confidence";
    }

    static String normalize(String value) {
        if (RESET_IMMINENT.equals(value) || RESET_CREDIT.equals(value)
                || RESET_CONFIRMED.equals(value) || GENERAL_UPDATE.equals(value)) {
            return value;
        }
        return NONE;
    }
}
