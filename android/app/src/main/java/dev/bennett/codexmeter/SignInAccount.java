package dev.bennett.codexmeter;

import java.util.Locale;

/** Pure: whether a new sign-in is the account that was signed in before. */
final class SignInAccount {
    enum Change {
        /** No earlier sign-in, or the same account: keep its numbers and history. */
        SAME,
        /** Another account: forget the old one's numbers, email and history. */
        DIFFERENT,
        /** Neither sign-in says which account it is: forget the last reading, keep history. */
        UNKNOWN
    }

    private SignInAccount() {
    }

    static Change compare(AuthTokens before, AuthTokens after) {
        if (before == null || after == null) return Change.SAME;
        String beforeId = clean(before.accountId);
        String afterId = clean(after.accountId);
        if (!beforeId.isEmpty() && !afterId.isEmpty()) {
            return beforeId.equals(afterId) ? Change.SAME : Change.DIFFERENT;
        }
        String beforeEmail = clean(before.email).toLowerCase(Locale.ROOT);
        String afterEmail = clean(after.email).toLowerCase(Locale.ROOT);
        if (!beforeEmail.isEmpty() && !afterEmail.isEmpty()) {
            return beforeEmail.equals(afterEmail) ? Change.SAME : Change.DIFFERENT;
        }
        return Change.UNKNOWN;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
