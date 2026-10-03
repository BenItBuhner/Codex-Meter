package dev.bennett.codexmeter.wear;

import org.json.JSONObject;

/** Testable watch-local state. Phone selection and watch override are independent. */
public final class WearAccountStore {
    public interface Preferences {
        String get(String key);
        boolean put(String key, String value);
    }
    private final Preferences preferences;

    public WearAccountStore(Preferences preferences) { this.preferences = preferences; }

    public WearUsageState state() {
        try {
            String value = preferences.get("account_state");
            return value == null ? null : WearUsageState.fromJson(new JSONObject(value));
        } catch (Exception ignored) { return null; }
    }

    public String selection() {
        String value = preferences.get("local_account_id");
        return value == null ? "" : value;
    }

    public boolean apply(WearUsageState incoming) {
        if (incoming == null) return false;
        WearUsageState previous = state();
        if (previous != null && incoming.updatedAtMillis <= previous.updatedAtMillis) return false;
        try { return preferences.put("account_state", incoming.toJson().toString()); }
        catch (Exception ignored) { return false; }
    }

    public boolean select(String id) {
        String value = id == null ? "" : id;
        WearUsageState state = state();
        if (!value.isEmpty() && (state == null || state.selectedAccount(value) == null
                || !value.equals(state.selectedAccount(value).accountId))) return false;
        return preferences.put("local_account_id", value);
    }
}
