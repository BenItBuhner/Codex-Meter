package dev.bennett.codexmeter;

/* JADX INFO: loaded from: classes.dex */
public final class ResetConsumeResult {
    public static final String ALREADY_REDEEMED = "already_redeemed";
    public static final String NOTHING_TO_RESET = "nothing_to_reset";
    public static final String NO_CREDIT = "no_credit";
    public static final String RESET = "reset";
    public final String outcome;
    public final String refreshWarning;
    public final int windowsReset;

    public ResetConsumeResult(String str, int i, String str2) {
        this.outcome = str == null ? "" : str;
        this.windowsReset = Math.max(0, i);
        this.refreshWarning = str2 == null ? "" : str2;
    }

    public boolean applied() {
        return RESET.equals(this.outcome);
    }

    public String userMessage() {
        String str;
        if (RESET.equals(this.outcome)) {
            if (this.windowsReset > 0) {
                str = "Reset applied to " + this.windowsReset + " usage window" + (this.windowsReset == 1 ? "." : "s.");
            } else {
                str = "Codex usage reset applied.";
            }
            if (!this.refreshWarning.isEmpty()) {
                return str + " " + this.refreshWarning;
            }
            return str;
        }
        if (NOTHING_TO_RESET.equals(this.outcome)) {
            return "There is no used Codex allowance to reset right now.";
        }
        if (NO_CREDIT.equals(this.outcome)) {
            return "No reset credit is currently available.";
        }
        return ALREADY_REDEEMED.equals(this.outcome) ? "That reset request was already redeemed." : "OpenAI returned an unrecognized reset result.";
    }
}
