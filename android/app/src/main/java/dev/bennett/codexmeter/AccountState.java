package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Pure state transitions, persisted together by AccountRepository. */
final class AccountState {
    final List<AccountProfile> accounts = new ArrayList<>();
    String selectedId = "";

    AccountProfile find(String id) {
        for (AccountProfile account : accounts) if (account.id.equals(id)) return account;
        return null;
    }

    AccountProfile add(AuthTokens tokens, String identity) {
        if (!identity.isEmpty()) {
            for (AccountProfile account : accounts) {
                if (identity.equals(account.identity)) {
                    account.tokens = tokens;
                    return account;
                }
            }
        }
        int number = 1;
        while (hasLabel("Account " + number)) number++;
        AccountProfile account = new AccountProfile(UUID.randomUUID().toString(),
                "Account " + number, identity, tokens);
        accounts.add(account);
        if (selectedId.isEmpty()) selectedId = account.id;
        return account;
    }

    private boolean hasLabel(String label) {
        for (AccountProfile account : accounts) if (account.label.equals(label)) return true;
        return false;
    }

    void updateTokens(String id, AuthTokens tokens) {
        AccountProfile account = find(id);
        if (account == null) throw new IllegalArgumentException("Account no longer exists.");
        account.tokens = tokens;
    }

    void select(String id) {
        if (find(id) == null) throw new IllegalArgumentException("Account no longer exists.");
        selectedId = id;
    }

    void remove(String id) {
        accounts.removeIf(account -> account.id.equals(id));
        if (selectedId.equals(id)) selectedId = accounts.isEmpty() ? "" : accounts.get(0).id;
    }

    JSONObject toJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (AccountProfile account : accounts) list.put(account.toJson());
        return new JSONObject().put("version", 2).put("selected_id", selectedId)
                .put("accounts", list);
    }

    static AccountState fromJson(JSONObject json) throws JSONException {
        if (json.getInt("version") != 2) throw new JSONException("Unsupported account state.");
        AccountState state = new AccountState();
        JSONArray list = json.getJSONArray("accounts");
        for (int i = 0; i < list.length(); i++) {
            AccountProfile account = AccountProfile.fromJson(list.getJSONObject(i));
            if (state.find(account.id) != null) throw new JSONException("Duplicate profile ID.");
            state.accounts.add(account);
        }
        state.selectedId = json.optString("selected_id", "");
        if (state.find(state.selectedId) == null)
            state.selectedId = state.accounts.isEmpty() ? "" : state.accounts.get(0).id;
        return state;
    }

    static AccountState migrate(AuthTokens legacy) {
        return migrate(legacy, UUID.randomUUID().toString());
    }

    static AccountState migrate(AuthTokens legacy, String id) {
        AccountState state = new AccountState();
        if (legacy != null && legacy.isUsable()) {
            AccountProfile account = new AccountProfile(id,
                    "Account 1", AccountIdentity.fromTokens(legacy), legacy);
            state.accounts.add(account);
            state.selectedId = account.id;
        }
        return state;
    }
}
