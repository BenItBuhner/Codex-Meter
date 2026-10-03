package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
@SuppressLint({"ApplySharedPref"})
public final class SecureTokenStore {
    private static final String KEY_ALIAS = "codex_meter_auth_key_v1";
    private static final String KEY_BLOB = "blob";
    private static final Object LOCK = new Object();
    private static final String PREFS = "secure_auth_v1";

    private SecureTokenStore() {
    }

    static void saveDocument(Context context, JSONObject document) throws Exception {
        synchronized (LOCK) {
            JSONObject jSONObject = EncryptedAccountCodec.encrypt(document, getOrCreateKey());
            if (!context.getSharedPreferences(PREFS, 0).edit().putString("accounts_v2", jSONObject.toString()).remove(KEY_BLOB).remove("migration_id").commit()) {
                throw new Exception("Could not persist encrypted credentials.");
            }
        }
    }

    static JSONObject loadDocument(Context context, String key) throws Exception {
        String value = context.getSharedPreferences(PREFS, 0).getString(key, null);
        if (value == null || value.isEmpty()) return null;
        return EncryptedAccountCodec.decrypt(new JSONObject(value), getOrCreateKey());
    }

    public static void save(Context context, AuthTokens tokens) throws Exception {
        AccountRepository.updateTokens(context, AccountRepository.selectedId(context), tokens);
    }

    public static AuthTokens load(Context context) {
        try { return AccountRepository.tokens(context, AccountRepository.selectedId(context)); }
        catch (Exception ignored) { return null; }
    }

    public static boolean isSignedIn(Context context) { return load(context) != null; }

    public static void clear(Context context) {
        try { AccountRepository.remove(context, AccountRepository.selectedId(context)); }
        catch (Exception exception) { throw new IllegalStateException("Could not remove account.", exception); }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (key instanceof SecretKey) {
            return (SecretKey) key;
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setRandomizedEncryptionRequired(true).build());
        return keyGenerator.generateKey();
    }
}
