package dev.bennett.codexmeter;

import org.json.JSONObject;

/** Transaction boundary for legacy migration; backend keeps old ciphertext until commit. */
final class AccountStorage {
    interface Backend {
        String migrationId() throws Exception;
        JSONObject load(String key) throws Exception;
        void copyLegacyCaches(String id) throws Exception;
        void commit(JSONObject document) throws Exception;
    }

    static AccountState read(Backend backend) throws Exception {
        JSONObject document = backend.load("accounts_v2");
        if (document != null) return AccountState.fromJson(document);
        JSONObject legacy = backend.load("blob");
        AuthTokens tokens = legacy == null ? null : AuthTokens.fromJson(legacy);
        AccountState state = AccountState.migrate(tokens, tokens == null ? "" : backend.migrationId());
        if (!state.accounts.isEmpty()) backend.copyLegacyCaches(state.selectedId);
        backend.commit(state.toJson());
        return state;
    }
}
