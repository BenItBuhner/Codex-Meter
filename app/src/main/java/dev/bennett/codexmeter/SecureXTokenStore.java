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

/** Android Keystore-backed storage isolated from the user's ChatGPT credentials. */
@SuppressLint("ApplySharedPref")
public final class SecureXTokenStore {
    private static final String KEY_ALIAS = "codex_meter_x_auth_key_v1";
    private static final String KEY_BLOB = "blob";
    private static final String PREFS = "secure_x_auth_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final Object LOCK = new Object();

    private SecureXTokenStore() {
    }

    public static void save(Context context, XOAuthTokens tokens) throws Exception {
        if (tokens == null || !tokens.isUsable()) {
            throw new IllegalArgumentException("X returned incomplete credentials.");
        }
        synchronized (LOCK) {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(
                    tokens.toJson().toString().getBytes(StandardCharsets.UTF_8));
            JSONObject blob = new JSONObject()
                    .put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                    .put("ct", Base64.getEncoder().encodeToString(ciphertext));
            if (!prefs(context).edit().putString(KEY_BLOB, blob.toString()).commit()) {
                throw new Exception("Could not persist encrypted X credentials.");
            }
        }
    }

    public static XOAuthTokens load(Context context) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            String stored = preferences.getString(KEY_BLOB, "");
            if (stored == null || stored.isEmpty()) return null;
            try {
                JSONObject blob = new JSONObject(stored);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                        new GCMParameterSpec(128,
                                Base64.getDecoder().decode(blob.getString("iv"))));
                XOAuthTokens tokens = XOAuthTokens.fromJson(new JSONObject(new String(
                        cipher.doFinal(Base64.getDecoder().decode(blob.getString("ct"))),
                        StandardCharsets.UTF_8)));
                return tokens.isUsable() ? tokens : null;
            } catch (Exception exception) {
                preferences.edit().remove(KEY_BLOB).commit();
                return null;
            }
        }
    }

    public static boolean isConnected(Context context) {
        return load(context) != null;
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().clear().commit();
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
            } catch (Exception ignored) {
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Key key = store.getKey(KEY_ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM")
                .setEncryptionPaddings("NoPadding")
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
