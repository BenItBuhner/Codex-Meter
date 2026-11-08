package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
@SuppressLint({"ApplySharedPref"})
public final class SecureTokenStore {
    private static final String KEY_ALIAS = "codex_meter_auth_key_v1";
    private static final String KEY_BLOB = "blob";
    private static final Object LOCK = new Object();
    /**
     * Whether each provider has usable tokens, by prefs file. Asking means a Keystore decrypt,
     * which the screens did on the main thread for every provider on every redraw; the answer only
     * changes on save and clear, which keep it current.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SIGNED_IN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String PREFS = "secure_auth_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SecureTokenStore() {
    }

    /** Codex (ChatGPT) credentials: the original, unchanged storage slot. */
    public static void save(Context context, AuthTokens authTokens) throws Exception {
        save(context, UsageProvider.CODEX, authTokens);
    }

    public static AuthTokens load(Context context) {
        return load(context, UsageProvider.CODEX);
    }

    public static boolean isSignedIn(Context context) {
        return load(context) != null;
    }

    public static void clear(Context context) {
        clear(context, UsageProvider.CODEX);
    }

    public static boolean isSignedIn(Context context, UsageProvider provider) {
        Boolean known = SIGNED_IN.get(prefsName(provider));
        return known != null ? known : load(context, provider) != null;
    }

    /** Each provider gets its own prefs file and Keystore key so sign-outs never cross over. */
    private static String prefsName(UsageProvider provider) {
        return provider == UsageProvider.CODEX ? PREFS : "secure_auth_" + provider.id + "_v1";
    }

    private static String keyAlias(UsageProvider provider) {
        return provider == UsageProvider.CODEX ? KEY_ALIAS
                : "codex_meter_" + provider.id + "_auth_key_v1";
    }

    public static void save(Context context, UsageProvider provider, AuthTokens authTokens)
            throws Exception {
        synchronized (LOCK) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(1, getOrCreateKey(keyAlias(provider)));
            byte[] bArrDoFinal = cipher.doFinal(authTokens.toJson().toString().getBytes(StandardCharsets.UTF_8));
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("iv", Base64.getEncoder().encodeToString(cipher.getIV()));
            jSONObject.put("ct", Base64.getEncoder().encodeToString(bArrDoFinal));
            if (!context.getSharedPreferences(prefsName(provider), 0).edit().putString(KEY_BLOB, jSONObject.toString()).commit()) {
                throw new Exception("Could not persist encrypted credentials.");
            }
            SIGNED_IN.put(prefsName(provider), authTokens.isUsable());
        }
    }

    public static AuthTokens load(Context context, UsageProvider provider) {
        AuthTokens authTokens = null;
        synchronized (LOCK) {
            SharedPreferences sharedPreferences =
                    context.getSharedPreferences(prefsName(provider), 0);
            String string = sharedPreferences.getString(KEY_BLOB, null);
            if (string != null && !string.isEmpty()) {
                try {
                    JSONObject jSONObject = new JSONObject(string);
                    byte[] bArrDecode = Base64.getDecoder().decode(jSONObject.getString("iv"));
                    byte[] bArrDecode2 = Base64.getDecoder().decode(jSONObject.getString("ct"));
                    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                    cipher.init(2, getOrCreateKey(keyAlias(provider)), new GCMParameterSpec(128, bArrDecode));
                    AuthTokens authTokensFromJson = AuthTokens.fromJson(new JSONObject(new String(cipher.doFinal(bArrDecode2), StandardCharsets.UTF_8)));
                    if (!authTokensFromJson.isUsable()) {
                        authTokensFromJson = null;
                    }
                    authTokens = authTokensFromJson;
                } catch (Exception e) {
                    sharedPreferences.edit().remove(KEY_BLOB).commit();
                }
            }
            SIGNED_IN.put(prefsName(provider), authTokens != null);
        }
        return authTokens;
    }

    public static void clear(Context context, UsageProvider provider) {
        synchronized (LOCK) {
            SIGNED_IN.put(prefsName(provider), false);
            context.getSharedPreferences(prefsName(provider), 0).edit().clear().commit();
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(keyAlias(provider))) {
                    keyStore.deleteEntry(keyAlias(provider));
                }
            } catch (Exception e) {
            }
        }
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        Key key = keyStore.getKey(alias, null);
        if (key instanceof SecretKey) {
            return (SecretKey) key;
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return keyGenerator.generateKey();
    }
}
