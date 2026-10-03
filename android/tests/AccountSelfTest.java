package dev.bennett.codexmeter;

import dev.bennett.codexmeter.wear.WearAccount;
import dev.bennett.codexmeter.wear.WearAccountStore;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearUsageState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AccountSelfTest {
    public static void main(String[] args) throws Exception {
        migrationAndInterruptedCommit();
        independentAccountsAndRotation();
        reliableIdentity();
        refreshAndPartialFailure();
        wearRoundTripAndSelection();
        encryptedEnvelopeIntegrity();
        System.out.println("AccountSelfTest: all account/security/Wear checks passed");
    }

    private static AuthTokens tokens(String account) {
        return new AuthTokens("access-secret-" + account, "refresh-secret-" + account,
                "jwt-secret-" + account, 9999999999999L, account, "private@example.invalid");
    }

    private static void migrationAndInterruptedCommit() throws Exception {
        MemoryBackend backend = new MemoryBackend(tokens("legacy"));
        backend.failCache = true;
        expectFailure(() -> AccountStorage.read(backend));
        check(backend.legacy != null && backend.document == null, "cache failure preserves legacy");
        backend.failCache = false;
        backend.failCommit = true;
        expectFailure(() -> AccountStorage.read(backend));
        String copiedId = backend.copiedId;
        check(backend.legacy != null && backend.document == null, "commit failure preserves legacy");
        backend.failCommit = false;
        AccountState migrated = AccountStorage.read(backend);
        check(migrated.accounts.size() == 1, "one migrated profile");
        check(migrated.selectedId.equals(copiedId), "retry reuses cache namespace");
        check(migrated.find(copiedId).tokens.accessToken.equals("access-secret-legacy"), "session retained");
        int commits = backend.commits;
        AccountState second = AccountStorage.read(backend);
        check(second.selectedId.equals(copiedId) && backend.commits == commits, "idempotent migration");
        migrated.remove(copiedId);
        backend.commit(migrated.toJson());
        check(AccountStorage.read(backend).accounts.isEmpty(), "removal cannot resurrect legacy");
    }

    private static void independentAccountsAndRotation() throws Exception {
        AccountState state = new AccountState();
        String first = state.add(tokens("a"), "identity-a").id;
        String second = state.add(tokens("b"), "identity-b").id;
        check(!first.equals(second) && !first.contains("@"), "independent UUIDs");
        check(state.selectedId.equals(first), "adding second retains default");
        state.select(second);
        state = AccountState.fromJson(state.toJson());
        check(state.selectedId.equals(second), "selected account survives persistence");
        state.updateTokens(second, tokens("rotated-b"));
        check(state.find(first).tokens.refreshToken.equals("refresh-secret-a"), "rotation isolates first");
        check(state.find(second).tokens.refreshToken.equals("refresh-secret-rotated-b"), "rotation updates target");
        String deduplicated = state.add(tokens("reauth-a"), "identity-a").id;
        check(first.equals(deduplicated) && state.accounts.size() == 2, "reliable identity deduplicates");
        state.add(tokens("unknown"), "");
        state.add(tokens("unknown"), "");
        check(state.accounts.size() == 4, "missing identity never guessed from email");
        state.remove(second);
        check(state.find(first).isAuthenticated() && state.find(second) == null, "removal isolates sessions");
        check(state.selectedId.equals(first), "removing default picks survivor");
        state.add(tokens("after-removal"), "identity-new");
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (AccountProfile account : state.accounts) check(labels.add(account.label),
                "generated labels remain distinct after removal");
        AccountState finalState = state;
        expectFailure(() -> finalState.updateTokens(second, tokens("late")));
        expectFailure(() -> finalState.select("missing"));
        check(AccountIdentity.fromTokens(tokens("organization-only")).isEmpty(), "organization alone not identity");
    }

    private static void reliableIdentity() throws Exception {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new JSONObject().put("iss", "https://auth.openai.com").put("sub", "user-a")
                        .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AuthTokens first = new AuthTokens("a", "r", "header." + payload + ".signature", 99,
                "shared-organization", "same@example.invalid");
        AuthTokens other = new AuthTokens("b", "s", "header." + payload + ".signature", 99,
                "other-organization", "same@example.invalid");
        check(!AccountIdentity.fromTokens(first).isEmpty(), "OAuth subject/account identity available");
        check(!AccountIdentity.fromTokens(first).equals(AccountIdentity.fromTokens(other)),
                "same user in different accounts not deduplicated");
        MemoryBackend backend = new MemoryBackend(first);
        AccountState migrated = AccountStorage.read(backend);
        String id = migrated.add(first, AccountIdentity.fromTokens(first)).id;
        check(migrated.accounts.size() == 1 && id.equals(migrated.selectedId),
                "legacy session deduplicates after OAuth reauthentication");
        backend.document.put("version", 999);
        expectFailure(() -> AccountStorage.read(backend));
        check(backend.document.getInt("version") == 999, "unreadable state never overwritten");
    }

    private static void refreshAndPartialFailure() throws Exception {
        List<String> called = new ArrayList<>();
        Map<String, Exception> specific = AccountRefresh.run(List.of("b"), called::add);
        check(called.equals(List.of("b")) && specific.isEmpty(), "explicit account refresh");
        called.clear();
        Map<String, Exception> failures = AccountRefresh.run(List.of("a", "b", "c"), id -> {
            called.add(id);
            if (id.equals("b")) throw new Exception("simulated token refresh failure");
        });
        check(called.equals(List.of("a", "b", "c")), "refresh-all remains sequential");
        check(failures.size() == 1 && failures.containsKey("b"), "only failing account reported");
        Thread.currentThread().interrupt();
        try { AccountRefresh.run(List.of("a"), called::add); throw new AssertionError("cancel ignored"); }
        catch (InterruptedException expected) { Thread.interrupted(); }
    }

    private static UsageSnapshot snapshot(int used, long time) {
        return new UsageSnapshot("plus", true, false,
                new UsageWindow(used, 18000, 1000, 2000000000),
                new UsageWindow(used + 5, 604800, 5000, 2000001000), time);
    }

    private static void wearRoundTripAndSelection() throws Exception {
        String a = "0a2bc77c-4ee7-4ab1-a708-cdc5beef88f1";
        String b = "453e32b2-f35b-46c5-9a83-f4d1b008dd60";
        WearUsageState state = new WearUsageState(snapshot(10, 100), 200,
                WearSettingsState.SOURCE_PHONE, true,
                List.of(new WearAccount(a, "Personal", true, snapshot(10, 100)),
                        new WearAccount(b, "Work", true, snapshot(60, 150), true)), a);
        String wire = state.toJson().toString();
        for (String secret : List.of("access_token", "refresh_token", "id_token", "credentials",
                "Authorization", "private@example.invalid", "access-secret", "refresh-secret", "jwt-secret"))
            check(!wire.contains(secret), "no sensitive data in Wear payload: " + secret);
        state = WearUsageState.fromJson(new JSONObject(wire));
        check(state.accounts.size() == 2, "multiple Wear profiles round trip");
        WearUsageState similarlyNamed = new WearUsageState(null, 300, "phone", true,
                List.of(new WearAccount(a, "Account 1", true, null),
                        new WearAccount(b, "Account 2", true, null)), a);
        check(!similarlyNamed.shortLabel(a, 7).equals(similarlyNamed.shortLabel(b, 7)),
                "compact complication labels distinguish default names");
        check(state.selectedAccount(b).refreshFailed && !state.selectedAccount(a).refreshFailed,
                "Wear failure state belongs to the selected account");
        check(state.selectedSnapshot("").fiveHour.usedPercent == 10, "follows phone initially");
        Map<String, String> preferences = new java.util.HashMap<>();
        WearAccountStore.Preferences memory = new WearAccountStore.Preferences() {
            @Override public String get(String key) { return preferences.get(key); }
            @Override public boolean put(String key, String value) { preferences.put(key, value); return true; }
        };
        WearAccountStore store = new WearAccountStore(memory);
        check(store.apply(state), "watch state committed");
        check(store.select(b), "local selection accepted");
        store = new WearAccountStore(memory);
        String persistedSelection = store.selection();
        check(persistedSelection.equals(b), "watch override persists across store recreation");
        check(!store.apply(new WearUsageState(snapshot(99, 1), 100, "phone")), "stale phone state rejected");
        check(!store.select("missing"), "unknown watch profile rejected");
        check(store.state().selectedSnapshot(store.selection()).fiveHour.usedPercent == 60, "Tile resolver selects work");
        preferences.put("account_state", "malformed");
        check(store.state() == null, "malformed persisted watch state is safe");
        check(store.apply(state), "valid state repairs malformed storage");
        check(state.selectedSnapshot(persistedSelection).fiveHour.usedPercent == 60, "persisted watch selection");
        check(state.selectedSnapshot(persistedSelection).weekly.usedPercent == 65, "same account for weekly tile");
        check(state.selectedSnapshot("removed").fiveHour.usedPercent == 10, "removed selection falls back");
        JSONObject malformed = state.toJson().put("accounts", new JSONArray().put("bad")
                .put(new JSONObject().put("account_id", "invalid")));
        check(WearUsageState.fromJson(malformed).accounts.isEmpty(), "malformed entries ignored");
        WearUsageState oldPhone = WearUsageState.fromJson(new JSONObject().put("usage", snapshot(25, 50).toJson()));
        check(oldPhone.selectedSnapshot(b).fiveHour.usedPercent == 25, "legacy Wear payload compatibility");
        check(WearUsageState.fromJson(new JSONObject()).selectedSnapshot(b) == null, "empty state safe");
    }

    private static void encryptedEnvelopeIntegrity() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        AccountState state = AccountState.migrate(tokens("secure"));
        JSONObject first = EncryptedAccountCodec.encrypt(state.toJson(), key);
        JSONObject second = EncryptedAccountCodec.encrypt(state.toJson(), key);
        check(!first.getString("iv").equals(second.getString("iv")), "fresh randomized IV");
        check(!first.toString().contains("access-secret"), "ciphertext hides credentials");
        check(AccountState.fromJson(EncryptedAccountCodec.decrypt(first, key)).accounts.size() == 1,
                "encrypted state round trip");
        byte[] altered = java.util.Base64.getDecoder().decode(first.getString("ct"));
        altered[0] ^= 1;
        first.put("ct", java.util.Base64.getEncoder().encodeToString(altered));
        expectFailure(() -> EncryptedAccountCodec.decrypt(first, key));
    }

    private static final class MemoryBackend implements AccountStorage.Backend {
        JSONObject legacy;
        JSONObject document;
        boolean failCache, failCommit;
        int commits;
        String copiedId;
        final String migrationId = java.util.UUID.randomUUID().toString();
        MemoryBackend(AuthTokens tokens) throws Exception { legacy = tokens.toJson(); }
        @Override public String migrationId() { return migrationId; }
        @Override public JSONObject load(String key) { return key.equals("blob") ? legacy : document; }
        @Override public void copyLegacyCaches(String id) throws Exception {
            copiedId = id;
            if (failCache) throw new Exception("simulated interruption");
        }
        @Override public void commit(JSONObject value) throws Exception {
            if (failCommit) throw new Exception("simulated disk failure");
            document = new JSONObject(value.toString());
            legacy = null;
            commits++;
        }
    }

    interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected failure");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
