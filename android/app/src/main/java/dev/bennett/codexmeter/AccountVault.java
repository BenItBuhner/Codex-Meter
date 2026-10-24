package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Several accounts per provider. The active account keeps living in the provider's normal
 * slots (tokens, cached usage, history), so refresh, widgets and the live notification need no
 * changes. Every other account waits here with its tokens encrypted; switching swaps the two.
 */
@SuppressLint({"ApplySharedPref"})
public final class AccountVault {
    private static final String PREFS = "codex_meter_account_vault_v1";
    private static final String KEY_ALIAS = "codex_meter_account_vault_key_v1";
    private static final String SUFFIX_ACCOUNTS = "_accounts";
    private static final String SUFFIX_ADDING = "_adding";
    private static final Object LOCK = new Object();
    private static final String[] HISTORY_KINDS = {
            UsageHistory.FIVE_HOUR, UsageHistory.WEEKLY, UsageHistory.MONTHLY};

    private AccountVault() {
    }

    /** One account row: the active one or a saved one. */
    public static final class Account {
        public final String id;
        public final String email;
        public final String plan;
        public final UsageSnapshot snapshot;
        public final boolean active;

        Account(String id, String email, String plan, UsageSnapshot snapshot, boolean active) {
            this.id = id;
            this.email = email == null ? "" : email;
            this.plan = plan == null ? "" : plan;
            this.snapshot = snapshot;
            this.active = active;
        }

        /** What the switcher shows when an account shares no email. */
        public String label(int index) {
            return email.isEmpty() ? "Account " + (index + 1) : email;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** The active account (when signed in) first, then saved ones in the order they were added. */
    public static List<Account> list(Context context, UsageProvider provider) {
        List<Account> out = new ArrayList<>();
        boolean signedIn = ProviderAccounts.isSignedIn(context, provider);
        String activeEmail = signedIn ? ProviderAccounts.email(context, provider) : "";
        if (signedIn) {
            out.add(new Account("active", activeEmail, ProviderAccounts.plan(context, provider),
                    ProviderPreferences.loadSnapshot(context, provider), true));
        }
        for (JSONObject entry : saved(context, provider)) {
            String email = entry.optString("email", "");
            // Signing in to an account that was already saved replaces the saved copy.
            if (signedIn && !email.isEmpty() && email.equalsIgnoreCase(activeEmail)) {
                remove(context, provider, entry.optString("id"));
                continue;
            }
            UsageSnapshot snapshot = null;
            JSONObject snap = entry.optJSONObject("snapshot");
            if (snap != null) {
                try {
                    snapshot = UsageSnapshot.fromJson(snap);
                } catch (Exception ignored) {
                    snapshot = null;
                }
            }
            String plan = snapshot == null || snapshot.planType == null ? ""
                    : provider == UsageProvider.CODEX ? UsageFormat.planLabel(snapshot.planType)
                    : snapshot.planType;
            out.add(new Account(entry.optString("id"), email, plan, snapshot, false));
        }
        return out;
    }

    public static int count(Context context, UsageProvider provider) {
        return list(context, provider).size();
    }

    public static boolean hasSaved(Context context, UsageProvider provider) {
        return !saved(context, provider).isEmpty();
    }

    /** Makes a saved account active; the account that was active is saved in its place. */
    public static boolean switchTo(Context context, UsageProvider provider, String id) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            List<JSONObject> saved = saved(app, provider);
            JSONObject target = null;
            for (JSONObject entry : saved) {
                if (id.equals(entry.optString("id"))) target = entry;
            }
            if (target == null) return false;
            AuthTokens tokens;
            try {
                tokens = AuthTokens.fromJson(new JSONObject(
                        SecureTokenStore.decrypt(KEY_ALIAS, target.getString("tokens"))));
            } catch (Exception e) {
                DiagnosticLog.error(app, "accounts", "switch_decrypt_failed", e);
                return false;
            }
            saved.remove(target);
            JSONObject current = captureActive(app, provider);
            if (current != null) saved.add(current);
            clearActive(app, provider);
            try {
                restore(app, provider, target, tokens);
            } catch (Exception e) {
                DiagnosticLog.error(app, "accounts", "switch_restore_failed", e);
                return false;
            }
            write(app, provider, saved);
        }
        DiagnosticLog.info(app, "user", "account_switched", "provider", provider.id);
        afterChange(app);
        return true;
    }

    /**
     * Starts adding another account: the signed-in one is saved and the provider's slot is left
     * empty for the new sign-in. Its tab stays on.
     */
    public static void prepareToAdd(Context context, UsageProvider provider) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            List<JSONObject> saved = saved(app, provider);
            JSONObject current = captureActive(app, provider);
            if (current != null) saved.add(current);
            write(app, provider, saved);
            clearActive(app, provider);
            prefs(app).edit().putBoolean(provider.id + SUFFIX_ADDING, true).commit();
        }
        DiagnosticLog.info(app, "user", "account_add_started", "provider", provider.id);
        afterChange(app);
    }

    public static boolean isAdding(Context context, UsageProvider provider) {
        return prefs(context).getBoolean(provider.id + SUFFIX_ADDING, false);
    }

    public static void finishAdding(Context context, UsageProvider provider) {
        prefs(context).edit().remove(provider.id + SUFFIX_ADDING).apply();
    }

    /** Sign-in for a new account was abandoned: bring back the most recent saved account. */
    public static void cancelAdding(Context context, UsageProvider provider) {
        finishAdding(context, provider);
        if (ProviderAccounts.isSignedIn(context, provider)) return;
        List<JSONObject> saved = saved(context, provider);
        if (!saved.isEmpty()) {
            switchTo(context, provider, saved.get(saved.size() - 1).optString("id"));
        }
    }

    /** Forgets a saved (not active) account on this device. */
    public static void remove(Context context, UsageProvider provider, String id) {
        synchronized (LOCK) {
            List<JSONObject> saved = saved(context, provider);
            List<JSONObject> kept = new ArrayList<>();
            for (JSONObject entry : saved) {
                if (!id.equals(entry.optString("id"))) kept.add(entry);
            }
            write(context, provider, kept);
        }
    }

    /** Signing out of the active account: the next saved one, if any, takes over. */
    public static boolean promoteNext(Context context, UsageProvider provider) {
        List<JSONObject> saved = saved(context, provider);
        if (saved.isEmpty() || ProviderAccounts.isSignedIn(context, provider)) return false;
        return switchTo(context, provider, saved.get(0).optString("id"));
    }

    // --- storage ---

    private static List<JSONObject> saved(Context context, UsageProvider provider) {
        List<JSONObject> out = new ArrayList<>();
        String raw = prefs(context).getString(provider.id + SUFFIX_ACCOUNTS, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry != null) out.add(entry);
            }
        } catch (Exception ignored) {
            // Unreadable list: treat as empty.
        }
        return out;
    }

    private static void write(Context context, UsageProvider provider, List<JSONObject> list) {
        JSONArray array = new JSONArray();
        for (JSONObject entry : list) array.put(entry);
        prefs(context).edit().putString(provider.id + SUFFIX_ACCOUNTS, array.toString()).commit();
    }

    /** Everything that belongs to the active account, or null when signed out. */
    private static JSONObject captureActive(Context context, UsageProvider provider) {
        AuthTokens tokens = SecureTokenStore.load(context, provider);
        if (tokens == null) return null;
        try {
            JSONObject entry = new JSONObject();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("email", ProviderAccounts.email(context, provider));
            entry.put("tokens", SecureTokenStore.encrypt(KEY_ALIAS, tokens.toJson().toString()));
            UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(context, provider);
            if (snapshot != null) entry.put("snapshot", snapshot.toJson());
            JSONObject history = new JSONObject();
            for (String kind : HISTORY_KINDS) {
                UsageHistory h = ProviderPreferences.loadUsageHistory(context, provider, kind);
                if (h != null) history.put(kind, h.toJson());
            }
            entry.put("history", history);
            if (provider == UsageProvider.CODEX) {
                ResetCreditsSnapshot resets = AppPreferences.loadResetCredits(context);
                if (resets != null) entry.put("reset_credits", resets.toJson());
            } else {
                ClaudeSavedResets resets = ProviderPreferences.loadSavedResets(context, provider);
                if (resets != null) entry.put("saved_resets", resets.toJson());
                entry.put("organization", ProviderPreferences.getOrganization(context, provider));
                entry.put("scope", ProviderPreferences.getGrantedScope(context, provider));
            }
            return entry;
        } catch (Exception e) {
            DiagnosticLog.error(context, "accounts", "capture_failed", e);
            return null;
        }
    }

    /** Empties the provider's slot without revoking tokens or turning its tab off. */
    private static void clearActive(Context context, UsageProvider provider) {
        SecureTokenStore.clear(context, provider);
        if (provider == UsageProvider.CODEX) {
            AppPreferences.clearSnapshot(context);
        } else {
            ProviderPreferences.clearProvider(context, provider);
        }
    }

    private static void restore(Context context, UsageProvider provider, JSONObject entry,
            AuthTokens tokens) throws Exception {
        SecureTokenStore.save(context, provider, tokens);
        JSONObject snap = entry.optJSONObject("snapshot");
        if (snap != null) {
            ProviderPreferences.saveSnapshot(context, provider, UsageSnapshot.fromJson(snap));
        }
        JSONObject history = entry.optJSONObject("history");
        if (history != null) {
            for (String kind : HISTORY_KINDS) {
                JSONObject h = history.optJSONObject(kind);
                if (h != null) {
                    ProviderPreferences.saveUsageHistory(context, provider,
                            UsageHistory.fromJson(h, kind));
                }
            }
        }
        if (provider == UsageProvider.CODEX) {
            JSONObject resets = entry.optJSONObject("reset_credits");
            if (resets != null) {
                AppPreferences.saveResetCredits(context, ResetCreditsSnapshot.fromJson(resets));
            }
        } else {
            ProviderPreferences.setEmail(context, provider, entry.optString("email", ""));
            JSONObject resets = entry.optJSONObject("saved_resets");
            if (resets != null) {
                ProviderPreferences.saveSavedResets(context, provider,
                        ClaudeSavedResets.fromJson(resets));
            }
            ProviderPreferences.setOrganization(context, provider,
                    entry.optString("organization", ""));
            ProviderPreferences.setGrantedScope(context, provider, entry.optString("scope", ""));
        }
    }

    private static void afterChange(Context app) {
        RefreshScheduler.schedulePeriodic(app);
        WidgetRenderer.updateAll(app);
        dev.bennett.codexmeter.wear.PhoneWearSync.pushAll(app);
    }
}
