package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import dev.bennett.codexmeter.wear.WearAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Central phone account store. Selection/removal serialize with in-flight API requests. */
public final class AccountRepository {
    private static final Object STORE_LOCK = new Object();
    private AccountRepository() { }

    private static AccountState read(Context context) throws Exception {
        return AccountStorage.read(new AccountStorage.Backend() {
            @Override public String migrationId() throws Exception {
                SharedPreferences prefs = context.getSharedPreferences("secure_auth_v1", 0);
                String id = prefs.getString("migration_id", null);
                if (id == null) {
                    id = java.util.UUID.randomUUID().toString();
                    if (!prefs.edit().putString("migration_id", id).commit())
                        throw new Exception("Could not start account migration.");
                }
                return id;
            }
            @Override public JSONObject load(String key) throws Exception {
                return SecureTokenStore.loadDocument(context, key);
            }
            @Override public void commit(JSONObject document) throws Exception {
                SecureTokenStore.saveDocument(context, document);
            }
            @Override public void copyLegacyCaches(String id) throws Exception {
                SharedPreferences.Editor editor = usagePreferences(context, id).edit();
                for (Map.Entry<String, ?> entry : context.getSharedPreferences(
                        "codex_meter_settings_v1", 0).getAll().entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                    else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                    else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                    else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                }
                if (!editor.commit()) throw new Exception("Could not migrate cached account state.");
            }
        });
    }

    public static List<AccountProfile> accounts(Context context) {
        synchronized (STORE_LOCK) {
            try {
                List<AccountProfile> accounts = new ArrayList<>(read(context).accounts);
                for (AccountProfile account : accounts) account.usage = snapshot(context, account.id);
                return accounts;
            }
            catch (Exception exception) {
                DiagnosticLog.warn(context, "auth", "secure_accounts_unavailable");
                return new ArrayList<>();
            }
        }
    }

    public static String selectedId(Context context) {
        synchronized (STORE_LOCK) {
            try { return read(context).selectedId; }
            catch (Exception exception) {
                DiagnosticLog.warn(context, "auth", "secure_selection_unavailable");
                return "";
            }
        }
    }

    public static boolean hasAuthenticatedAccounts(Context context) {
        for (AccountProfile account : accounts(context)) if (account.isAuthenticated()) return true;
        return false;
    }

    public static AccountProfile selectedAccount(Context context) {
        String id = selectedId(context);
        for (AccountProfile account : accounts(context)) if (account.id.equals(id)) return account;
        return null;
    }

    public static UsageSnapshot selectedUsageSnapshot(Context context) {
        return snapshot(context, selectedId(context));
    }

    public static AuthTokens tokens(Context context, String id) throws Exception {
        synchronized (STORE_LOCK) {
            AccountProfile account = read(context).find(id);
            return account != null && account.isAuthenticated() ? account.tokens : null;
        }
    }

    public static void updateTokens(Context context, String id, AuthTokens tokens) throws Exception {
        synchronized (STORE_LOCK) {
            AccountState state = read(context);
            state.updateTokens(id, tokens);
            SecureTokenStore.saveDocument(context, state.toJson());
        }
    }

    public static String add(Context context, AuthTokens tokens) throws Exception {
        synchronized (UsageApi.NETWORK_LOCK) {
            String id;
            synchronized (STORE_LOCK) {
                AccountState state = read(context);
                id = state.add(tokens, AccountIdentity.fromTokens(tokens)).id;
                SecureTokenStore.saveDocument(context, state.toJson());
            }
            PhoneWearSync.pushUsage(context, AppPreferences.loadSnapshot(context));
            return id;
        }
    }

    public static void rename(Context context, String id, String label) throws Exception {
        synchronized (STORE_LOCK) {
            AccountState state = read(context);
            AccountProfile account = state.find(id);
            if (account == null) throw new Exception("Account no longer exists.");
            String value = label == null ? "" : label.trim();
            if (value.isEmpty() || value.length() > 40) throw new Exception("Use a label of 1–40 characters.");
            account.label = value;
            SecureTokenStore.saveDocument(context, state.toJson());
            PhoneWearSync.pushUsage(context, AppPreferences.loadSnapshot(context));
        }
    }

    public static void select(Context context, String id) throws Exception {
        synchronized (UsageApi.NETWORK_LOCK) {
            synchronized (STORE_LOCK) {
                AccountState state = read(context);
                if (state.selectedId.equals(id)) return;
                state.select(id);
                SecureTokenStore.saveDocument(context, state.toJson());
            }
            updateSurfaces(context);
        }
    }

    public static AuthTokens remove(Context context, String id) throws Exception {
        synchronized (UsageApi.NETWORK_LOCK) {
            AuthTokens removedTokens;
            boolean selectedRemoved;
            synchronized (STORE_LOCK) {
                AccountState state = read(context);
                AccountProfile account = state.find(id);
                if (account == null) return null;
                removedTokens = account.tokens;
                selectedRemoved = state.selectedId.equals(id);
                state.remove(id);
                SecureTokenStore.saveDocument(context, state.toJson());
            }
            usagePreferences(context, id).edit().clear().commit();
            if (selectedRemoved) updateSurfaces(context);
            else PhoneWearSync.pushUsage(context, AppPreferences.loadSnapshot(context));
            return removedTokens;
        }
    }

    private static void updateSurfaces(Context context) {
        boolean monitorWasActive = NowBarManager.isActive(context);
        NowBarManager.stop(context);
        NowBarPreferences.clearSuppression(context);
        ResetNotificationManager.clearState(context);
        ResetAlertScheduler.cancelAll(context);
        ResetCreditExpiryScheduler.cancelAll(context);
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
        if (snapshot != null) {
            ResetAlertScheduler.scheduleFromSnapshot(context, snapshot);
            if (monitorWasActive) NowBarManager.start(context);
            else NowBarManager.onUsageUpdated(context, snapshot);
        }
        WidgetRenderer.updateAll(context);
        PhoneWearSync.pushAll(context);
        if (accounts(context).isEmpty()) RefreshScheduler.cancelAll(context);
        else {
            RefreshScheduler.schedulePeriodic(context);
            RefreshScheduler.scheduleAtNextReset(context, snapshot);
        }
    }

    static SharedPreferences usagePreferences(Context context, String id) {
        if (!id.isEmpty()) java.util.UUID.fromString(id);
        return context.getSharedPreferences("codex_meter_account_" + id, 0);
    }

    public static UsageSnapshot snapshot(Context context, String id) {
        try {
            String value = usagePreferences(context, id).getString("last_snapshot", null);
            return value == null ? null : UsageSnapshot.fromJson(new JSONObject(value));
        } catch (Exception ignored) { return null; }
    }

    public static List<WearAccount> wearAccounts(Context context) {
        List<WearAccount> result = new ArrayList<>();
        for (AccountProfile account : accounts(context)) {
            result.add(new WearAccount(account.id, account.label, account.isAuthenticated(),
                    snapshot(context, account.id), !usagePreferences(context, account.id)
                            .getString("last_error", "").isEmpty()));
        }
        return result;
    }
}
