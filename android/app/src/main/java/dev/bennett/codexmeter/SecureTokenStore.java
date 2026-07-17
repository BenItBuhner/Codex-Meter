package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
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
    private static final String PREFS = "secure_auth_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SecureTokenStore() {
    }

    public static void save(Context context, AuthTokens authTokens) throws Exception {
        synchronized (LOCK) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(1, getOrCreateKey());
            byte[] bArrDoFinal = cipher.doFinal(authTokens.toJson().toString().getBytes(StandardCharsets.UTF_8));
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("iv", Base64.getEncoder().encodeToString(cipher.getIV()));
            jSONObject.put("ct", Base64.getEncoder().encodeToString(bArrDoFinal));
            if (!context.getSharedPreferences(PREFS, 0).edit().putString(KEY_BLOB, jSONObject.toString()).commit()) {
                throw new Exception("Could not persist encrypted credentials.");
            }
        }
    }

    public static AuthTokens load(Context context) {
        AuthTokens authTokens = null;
        synchronized (LOCK) {
            SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS, 0);
            String string = sharedPreferences.getString(KEY_BLOB, null);
            if (string != null && !string.isEmpty()) {
                try {
                    JSONObject jSONObject = new JSONObject(string);
                    byte[] bArrDecode = Base64.getDecoder().decode(jSONObject.getString("iv"));
                    byte[] bArrDecode2 = Base64.getDecoder().decode(jSONObject.getString("ct"));
                    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                    cipher.init(2, getOrCreateKey(), new GCMParameterSpec(128, bArrDecode));
                    AuthTokens authTokensFromJson = AuthTokens.fromJson(new JSONObject(new String(cipher.doFinal(bArrDecode2), StandardCharsets.UTF_8)));
                    if (!authTokensFromJson.isUsable()) {
                        authTokensFromJson = null;
                    }
                    authTokens = authTokensFromJson;
                } catch (Exception e) {
                    sharedPreferences.edit().remove(KEY_BLOB).commit();
                }
            }
        }
        return authTokens;
    }

    public static boolean isSignedIn(Context context) {
        return load(context) != null;
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            context.getSharedPreferences(PREFS, 0).edit().clear().commit();
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
            } catch (Exception e) {
            }
        }
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
